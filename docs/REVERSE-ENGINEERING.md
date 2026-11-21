# 逆向与加固对抗笔记

记录于 2026-11-21。这份文档是一次实战的完整沉淀：在**手机本地**搭出 native 工具链，
分析 360 加固（libjiagu），写 GOT 拦截库，以及一套广告 SDK 奖励校准链路的逆向结果。

留档目的：下次遇到同类壳 / 同类 SDK，不用从零再来。

---

## 一、native 工具链：在手机上编译 Android .so

### 为什么不用 NDK

官方 NDK 只提供 **x86_64 宿主**的编译器，这台机器是 aarch64，跑不了。
Termux 的包是 aarch64 原生二进制，可以用。

### 下载（约 70MB，比 NDK 的 664MB 省得多）

```sh
BASE=https://packages.termux.dev/apt/termux-main
# 索引里查 Filename 字段拿实际路径
wget $BASE/pool/main/c/clang/clang_21.1.8-3_aarch64.deb
wget $BASE/pool/main/libl/libllvm/libllvm_21.1.8-3_aarch64.deb
wget $BASE/pool/main/libc/libcompiler-rt/libcompiler-rt_21.1.8-3_aarch64.deb
wget $BASE/pool/main/n/ndk-sysroot/ndk-sysroot_30_aarch64.deb
wget $BASE/pool/main/b/binutils/binutils_2.47_aarch64.deb
wget $BASE/pool/main/l/lld/lld_21.1.8-3_aarch64.deb
```

运行时依赖（缺一个都起不来，挨个补齐）：

```
libffi  libxml2  zlib  libiconv  libicu  zstd
libandroid-support  libc++  ncurses  readline  liblzma  libbz2  lz4
```

### 解包

deb 是 ar 归档，data.tar 可能是 `.xz` / `.gz` / **`.zst`**（新版默认 zst，容易漏）：

```python
import io, tarfile, lzma, gzip, zstandard
data = open(deb,'rb').read()
idx = data.find(b'data.tar.')
e = data.find(b'\n', idx); name = data[idx:e].decode().strip()
start = e + 1
if start % 2: start += 1
raw = data[start:]
if '.zst' in name:
    dec = zstandard.ZstdDecompressor().decompress(raw, max_output_size=200*1024*1024)
elif '.xz' in name: dec = lzma.decompress(raw)
else: dec = gzip.decompress(raw)
tf = tarfile.open(fileobj=io.BytesIO(dec))
for m in tf.getmembers():
    if m.name.startswith('data/'): m.name = m.name[5:]
    tf.extract(m, out, filter='data')
```

### 使用

```sh
USR=<解包目录>/data/data/com.termux/files/usr
export LD_LIBRARY_PATH="$USR/lib"
$USR/bin/clang --version
# clang version 21.1.8 / Target: aarch64-unknown-linux-android24
```

编译：

```sh
$USR/bin/clang --target=aarch64-linux-android26 \
  -I"$USR/include" -L"$USR/lib" -B"$USR/bin" \
  -shared -fPIC -O2 -o libfoo.so foo.c -llog -ldl
```

**关键参数**：
- `-B"$USR/bin"` 让 clang 找到 `ld.lld`
- sysroot 不用单独指定，Termux 的 ndk-sysroot 直接合并进 `$USR/include` 和 `$USR/lib`
- 缺 `ld.lld` 会报 `unable to execute command: No such file or directory`（很误导，实际是链接器）

### 坑

| 现象 | 原因 |
|---|---|
| `library "libclang-cpp.so" not found` | 没设 `LD_LIBRARY_PATH` |
| 缺 `libffi.so` / `libz.so.1` / `libzstd.so.1` / `libiconv.so` | 依赖没补 |
| `invalid linker name in argument '-fuse-ld=lld'` | 没装 lld 包 |
| `unable to execute command: No such file or directory` | 找的是 `ld.lld`，看看装了没 |

---

## 二、ELF 静态分析（纯 Python）

工具：`pip install pyelftools capstone`

### 找关键字符串

```python
from elftools.elf.elffile import ELFFile
e = ELFFile(open('lib.so','rb'))
data = open('lib.so','rb').read()
segs = [(s['p_offset'], s['p_filesz'], s['p_vaddr'])
        for s in e.iter_segments() if s['p_type'] == 'PT_LOAD']

def va2off(va):
    for o, sz, v in segs:
        if v <= va < v + sz: return o + (va - v)

# 死字符串不一定在 .rodata，可能混在可执行段里
```

### 定位字符串引用（ADRP + ADD）

AArch64 用页寻址，必须 **配对计算**，只按页匹配会产生大量误报：

```python
for i in range(0, len(blob)-8, 4):
    w = struct.unpack_from('<I', blob, i)[0]
    if (w & 0x9F000000) != 0x90000000: continue      # ADRP
    immlo = (w>>29)&3; immhi = (w>>5)&0x7FFFF
    imm = (immhi<<2)|immlo
    if imm & (1<<20): imm -= (1<<21)                 # 符号扩展 21 位
    pc = va + i
    page = (pc & ~0xfff) + (imm << 12)
    rd = w & 0x1f
    # 后 1~3 条里找 ADD rd, rd, #imm12
    for step in (4, 8, 12):
        w2 = struct.unpack_from('<I', blob, i+step)[0]
        if (w2 & 0xFFC00000) == 0x91000000:          # ADD (immediate)
            rn=(w2>>5)&0x1f; rdd=w2&0x1f; imm12=(w2>>10)&0xfff
            if rn==rd and rdd==rd and page+imm12 == TARGET:
                print(f"引用 @ 0x{pc:x}")
```

> 只做 ADRP 页匹配会出现几百个假阳性（同页字符串太多），必须配 ADD 算完整地址。

### 解析导入符号（PLT / GOT）

节区表可能被裁剪（只剩 4 个 section），但 **动态段是完整的**：

```python
dyn = e.get_section_by_name('.dynamic')
for t in dyn.iter_tags():
    print(t.entry.d_tag, hex(t.entry.d_val))
# DT_SYMTAB / DT_STRTAB / DT_JMPREL / DT_PLTRELSZ / DT_PLTREL
```

手工解析 JMPREL 拿每个 GOT 项对应的符号名：

```python
for i in range(PLTRELSZ // 24):
    o = va2off(JMPREL + i*24)
    r_offset, r_info, _ = struct.unpack_from('<QQq', data, o)
    sym_idx = r_info >> 32
    so = va2off(SYMTAB + sym_idx*24)
    name_off = struct.unpack_from('<I', data, so)[0]
    print(hex(r_offset), cstr(STRTAB + name_off))
```

**注意：同一个函数可能有多个 GOT 表项**（私有 / 公开各一），
`kill` 就出现了两次（`0x6fb60` 和 `0x35bef8`）。漏改一个就白干。

### 找 BL 调用点

```python
if (w & 0xFC000000) == 0x94000000:      # BL
    imm = w & 0x3FFFFFF
    if imm & 0x2000000: imm -= 0x4000000
    target = pc + (imm << 2)
```

---

## 三、运行时改 GOT（绕过已加载库的符号绑定）

### 为什么符号覆盖不管用

在 `constructor` 里定义同名 `fopen` 只对**之后**加载的库生效。
如果目标 so 已经绑定好符号，覆盖无效。必须直接改写它的 GOT 表项。

### 实现

```c
static int patch_cb(struct dl_phdr_info *info, size_t size, void *data) {
    if (!info->dlpi_name || !strstr(info->dlpi_name, "libjiagu")) return 0;
    void *base = (void *)info->dlpi_addr;          // 加载基址
    long page = sysconf(_SC_PAGESIZE);
    for (i...) {
        void **slot = (void **)((char *)base + GOT[i].off);   // 静态分析得到的偏移
        void *pg = (void *)((uintptr_t)slot & ~(uintptr_t)(page-1));
        mprotect(pg, page, PROT_READ|PROT_WRITE);   // RELRO 需要先改权限
        *slot = (void *)my_func;
        mprotect(pg, page, PROT_READ);
    }
    return 1;
}
```

### 时机问题：目标可能比我们后加载

`constructor` 执行时目标 so 未必已在内存。起个线程高频轮询：

```c
static volatile int g_patched;
static void *watcher(void *a) {
    for (int i = 0; i < 3000 && !g_patched; i++) {
        dl_iterate_phdr(patch_cb, NULL);
        if (!g_patched) usleep(500);     // 0.5ms
    }
}
```

实测第 32 次（约 16ms）命中，足够快。

---

## 四、dump 目标 so 的运行时代码

判断"代码是否加密"的最快办法——把内存段抓下来和文件比：

```c
static void dump_range(const char *path, void *addr, size_t len) {
    int fd = open(path, O_WRONLY|O_CREAT|O_TRUNC, 0644);
    char buf[65536];
    size_t done = 0;
    while (done < len) {
        size_t n = len - done; if (n > sizeof(buf)) n = sizeof(buf);
        memcpy(buf, (char *)addr + done, n);
        ssize_t w = write(fd, buf, n);
        if (w <= 0) break;
        done += (size_t)w;
    }
    close(fd);
}
```

在 `patch_cb` 里对每个 `PT_LOAD` 且 `PF_X` 的段调用即可。

**结果解读**：
- 差异 0 字节 → 代码明文，静态分析可信
- 有差异 → 运行时解密，静态看到的是壳

> 360 加固这次是 **0 差异**，说明它的 so 不加密，难点在别处（见下文）。

---

## 五、加固识别与对抗策略

### 识别特征

| 加固商 | 特征文件 |
|---|---|
| 360 | `.jiagu`、`libjiagu.so`、`libjiagu_64.so` |
| 梆梆 | `libDexHelper.so`、`libSecShell.so` |
| 爱加密 | `libexec.so`、`libexecmain.so` |
| 网易易盾 | `libnesec.so` |
| 阿里聚安全 | `libmobisec.so` |

检测点：`ApplicationInfo.dataDir` 和 `nativeLibraryDir` 下有没有这些文件。

### Java 层能挡住的

- `ClassLoader.loadClass` 查 Xposed 类 → 抛 `ClassNotFoundException`
- `PackageManager.getInstalledPackages` → 隐藏模块自己
- `Throwable.getOurStackTrace` → 过滤 Xposed 帧
- `SystemProperties.get` → 过滤 xposed / lsposed
- `Process.killProcess` / `System.exit` → 拦掉

### 实测结论（2026-11-21，菜菜拼图 com.relax.ccpt）

做过的尝试和结果：

| 手段 | 结果 |
|---|---|
| Java 层隐藏类/包/堆栈 | 装上了，进程照样死 |
| native 覆盖 `fopen` 符号 | 无效（目标先加载） |
| GOT 改写，9 个表项全改 | **改写成功**，但拦截函数一次没调 |
| 阻断 `/proc/self/maps` 读取 | **拦截成功**（4 次），非死因 |
| 阻断 `exit/_exit/abort/kill/raise/syscall` | 全部 patch 成功，**一次没触发** |
| 内存 dump 比对 | 代码明文，0 差异 |
| **加载一个完全空的 .so** | **照样死** |

**决定性实验**：加载一个只打日志、什么都不做的空库，进程同样退出。

说明它检测的不是「你做了什么」，而是「有东西被注入进来」这个事实本身。

退出方式是 `signal 9 (SIGKILL)`，但：
- libc 的 `kill` 没被调用（GOT 已改）
- `syscall()` 没被调用（GOT 已改）
- Java 的 `Process.killProcess` 没被调用（hook 住了）

**SIGKILL 的来源在 ART 运行时或系统服务侧，Xposed 模块层够不着。**

### 结论

这类带注入检测的商业壳，模块层面无解。要么：
1. Zygisk 层隐藏（Shamiko / NoHello 那类，不是 Xposed 模块的活）
2. 重打包 so 去掉自毁逻辑（so 可能有自校验）

**普通加固（不自毁的）用上面的 GOT 框架可以直接用。**

---

## 六、广告奖励链路逆向（hexiang com.hexiang.jhfc）

### 架构

```
快手/穿山甲 SDK → TopOn 聚合 → App 业务层 → H5(WebView) → 服务端
```

### 接口

- 域名 `https://arrow.tv91.cn`
- 头部 **无签名**，只有秒级 `tv-time`
- AES 密钥明文写在页面里：`arrow01234567890`（CBC/PKCS7，key==iv）
- 设备指纹 `tv-bundle` / `tv-aid` 也是 AES 加密后传，格式：

```
tv-bundle = AES("包名|品牌|系统版本|app版本|机型")
tv-aid    = AES("androidId|IMEI|IMEI2|OAID")     ← 空位也要占位
```

### 关键接口

```
GET  /api/ad/task/list              任务列表
GET  /api/ad/reward/index           奖励配置
POST /api/ad/report/detail          观看上报（19 字段）
POST /api/ad/report/ecpm            eCPM 上报
POST /api/ad/task/reward            领奖  {ad_id, task_id, kind}
```

`report/detail` 完整字段：

```json
{"req_success_time":..,"callback_success_time":..,"ad_type":1,
 "ad_source":"CSJ","ecpm":"6500.00","code_id":"..","is_click":0,
 "code":2,"scene":0,"ad_group_id":"..","load_id":"哈希_代码位_时间戳",
 "playback_start_time":..,"playback_end_time":..,
 "brand":"meizu","device":"meizu20","battery":56,
 "is_reward":1,"is_completed_playback":1,"ad_time_interval":0}
```

### 结论

上报接口全部 `success`，领奖照样返回「观看完整视频才有奖励哦」。

服务端**不采信客户端上报的任何数据**，只认广告平台回传的转化事件（S2S）。
客户端伪造绕不过去。

### 例外：纯观看类任务

如果 App 的奖励判定完全在客户端（如 Unity/Cocos 游戏），
把**自动关闭延迟调够**（10 秒）就能拿到——两段式广告需要时间从落地页过渡到激励视频。

实测：杰青（Unity）2 秒失败、10 秒成功；脑力我最强（Cocos）同理。

---

## 七、通用经验

1. **先做对照实验**。加载空库、关掉模块、只 patch 一处——用排除法定位，比猜快得多。
2. **同一个函数可能有多个 GOT 表项**，必须全部改写。
3. **ADRP 必须配 ADD**，否则几百个假阳性。
4. **deb 的 data.tar 可能是 zst**，不是 xz。
5. **target 比我们后加载**时，constructor 里 patch 无效，要起线程轮询。
6. **别急着改文件**。改 `libjiagu.so` 的字节会导致它启动失败（自校验或流程破坏），
   而且改完要能恢复——先备份，改完立即验证 MD5。
7. **服务端判定优先级**：客户端伪造 < 广告平台 S2S。看到「上报 success 但领奖失败」
   就是这个信号。
