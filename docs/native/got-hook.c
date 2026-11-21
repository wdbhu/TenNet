#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <unistd.h>
#include <signal.h>
#include <pthread.h>
#include <stdarg.h>
#include <sys/syscall.h>
#include <sys/mman.h>
#include <link.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <android/log.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "TunNet", __VA_ARGS__)

/* libjiagu_64.so 的 GOT 偏移（静态解析 PLT 重定位表得出） */
/* libjiagu_64.so 全部相关 GOT 项（同一函数可能有多个表项，必须全改） */
typedef struct { unsigned long off; int kind; } GotEnt;
#define K_ABORT 0
#define K_KILL  1
#define K_EXIT  2
#define K__EXIT 3
#define K_RAISE 4
#define K_FOPEN 5
#define K_SYSC  6

static const GotEnt GOT[] = {
    { 0x6faf8,  K_ABORT },   /* abort */
    { 0x6fb60,  K_KILL  },   /* kill (私有) */
    { 0x35bef8, K_KILL  },   /* kill (公开) */
    { 0x6fdd0,  K__EXIT },   /* _exit */
    { 0x35bfa8, K_EXIT  },   /* exit */
    { 0x6fe60,  K_RAISE },   /* raise */
    { 0x70128,  K_RAISE },   /* raise (第二处) */
    { 0x6fcc0,  K_FOPEN },   /* fopen */
    { 0x35bf58, K_FOPEN },   /* fopen (第二处) */
    { 0x6f9b0,  K_SYSC  },   /* syscall */
};
#define GOT_N (sizeof(GOT)/sizeof(GOT[0]))
#define GOT_N (sizeof(GOT)/sizeof(GOT[0]))

static FILE *(*real_fopen)(const char *, const char *);
static char g_fake[512];
static volatile int g_ready, g_patched, g_dumped;

/* ---- 拦截实现 ---- */
static FILE *my_fopen(const char *path, const char *mode) {
    if (path && strcmp(path, "/proc/self/maps") == 0) {
        LOG("拦 fopen(maps)");
        if (g_ready) { FILE *f = real_fopen(g_fake, mode); if (f) return f; }
        return NULL;
    }
    return real_fopen(path, mode);
}
static void my_exit(int st)   { LOG("拦 exit(%d) — 不退出", st); pthread_exit(NULL); }
static void my__exit(int st)  { LOG("拦 _exit(%d) — 不退出", st); pthread_exit(NULL); }
static void my_abort(void)    { LOG("拦 abort — 不退出"); pthread_exit(NULL); }
static int  my_kill(pid_t p, int s) { LOG("拦 kill(pid=%d,sig=%d)", (int)p, s); return 0; }
static int  my_raise(int s)   { LOG("拦 raise(%d)", s); return 0; }

static long (*real_syscall)(long, ...);
static long my_syscall(long num, ...) {
    if (num == SYS_kill || num == SYS_tgkill || num == SYS_exit
            || num == SYS_exit_group) {
        LOG("拦 syscall(%ld) — 不执行", num);
        return 0;
    }
    if (!real_syscall) real_syscall = dlsym(RTLD_NEXT, "syscall");
    va_list ap; va_start(ap, num);
    long a1 = va_arg(ap, long), a2 = va_arg(ap, long), a3 = va_arg(ap, long);
    long a4 = va_arg(ap, long), a5 = va_arg(ap, long), a6 = va_arg(ap, long);
    va_end(ap);
    return real_syscall(num, a1, a2, a3, a4, a5, a6);
}

/* ---- 把 libjiagu 的可执行段 dump 出来（运行时解密的代码在这里） ---- */
static void dump_range(const char *path, void *addr, size_t len) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) { LOG("dump 打开失败"); return; }
    size_t done = 0;
    char buf[65536];
    while (done < len) {
        size_t n = len - done; if (n > sizeof(buf)) n = sizeof(buf);
        memcpy(buf, (char *)addr + done, n);
        ssize_t w = write(fd, buf, n);
        if (w <= 0) break;
        done += (size_t)w;
    }
    close(fd);
    LOG("dump 完成 %s (%zu 字节)", path, done);
}

/* ---- 改写 libjiagu 的 GOT ---- */
static int patch_cb(struct dl_phdr_info *info, size_t size, void *data) {
    if (!info->dlpi_name || !strstr(info->dlpi_name, "libjiagu")) return 0;
    void *base = (void *)info->dlpi_addr;
    long page = sysconf(_SC_PAGESIZE);
    LOG("找到 %s base=%p", info->dlpi_name, base);
    /* dump 可执行段：解密后的真实代码在这里 */
    {
        char out[512];
        out[0] = 0;
        {
            /* 从 fake maps 路径推导目录，免得多传一个环境变量 */
            const char *mp = getenv("TUNNET_MAPS_PATH");
            if (mp && mp[0]) {
                strncpy(out, mp, sizeof(out) - 1);
                char *slash = strrchr(out, '/');
                if (slash) { *slash = 0; strncat(out, "/jiagu_dump", sizeof(out) - strlen(out) - 1); }
            }
        }
        if (out[0] && !g_dumped) {
            g_dumped = 1;
            for (int i = 0; i < info->dlpi_phnum; i++) {
                const ElfW(Phdr) *ph = &info->dlpi_phdr[i];
                if (ph->p_type != PT_LOAD) continue;
                if (!(ph->p_flags & PF_X)) continue;
                char path[600];
                snprintf(path, sizeof(path), "%s.x%d", out, i);
                dump_range(path, (char *)base + ph->p_vaddr, (size_t)ph->p_memsz);
            }
        }
    }
    for (unsigned i = 0; i < GOT_N; i++) {
        void **slot = (void **)((char *)base + GOT[i].off);
        void *pg = (void *)((uintptr_t)slot & ~(uintptr_t)(page - 1));
        if (mprotect(pg, (size_t)page, PROT_READ | PROT_WRITE) != 0) continue;
        void *old = *slot;
        void *neu = NULL;
        switch (GOT[i].kind) {
            case K_ABORT: neu = (void *)my_abort;  break;
            case K_KILL:  neu = (void *)my_kill;   break;
            case K_EXIT:  neu = (void *)my_exit;   break;
            case K__EXIT: neu = (void *)my__exit;  break;
            case K_RAISE: neu = (void *)my_raise;  break;
            case K_FOPEN: neu = (void *)my_fopen;  break;
            case K_SYSC:  neu = (void *)my_syscall; break;
        }
        if (neu) { *slot = neu; LOG("GOT[0x%lx] %p -> %p", GOT[i].off, old, neu); }
        mprotect(pg, (size_t)page, PROT_READ);
    }
    g_patched = 1;
    return 1;
}

static void *watcher(void *a) {
    (void)a;
    for (int i = 0; i < 3000 && !g_patched; i++) {
        dl_iterate_phdr(patch_cb, NULL);
        if (!g_patched) usleep(500);
    }
    return NULL;
}

__attribute__((constructor))
static void init(void) {
    real_fopen = dlsym(RTLD_NEXT, "fopen");
    const char *p = getenv("TUNNET_MAPS_PATH");
    if (p && p[0]) { strncpy(g_fake, p, sizeof(g_fake)-1); g_ready = 1; }
    LOG("init, fake=%s", g_ready ? g_fake : "(none)");
    dl_iterate_phdr(patch_cb, NULL);
    if (!g_patched) { pthread_t t; pthread_create(&t, NULL, watcher, NULL); pthread_detach(t); }
}
