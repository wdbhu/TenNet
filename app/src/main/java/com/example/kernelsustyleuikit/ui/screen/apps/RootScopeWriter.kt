package com.example.kernelsustyleuikit.ui.screen.apps

import android.content.Context
import java.io.File

/**
 * 用 root 直接改 LSPosed 的作用域表，勾选立即生效、不弹授权窗。
 *
 * ## 为什么一定要用 sqlite3 直写
 *
 * 旧版在没有系统 sqlite3 时走「拷出-改-拷回」（fallbackCopyWrite），这条路有致命缺陷：
 *
 *  1. LSPosed 是常驻进程，内存里握着数据库句柄。拷回只是替换文件，
 *     lspd 内存里仍是旧值，它下次落盘会把改动覆盖回去 —— 勾了没效果。
 *  2. 更糟的是那段代码删 `-wal`/`-shm`，直接毁掉 SQLite 一致性，
 *     lspd 挂着孤儿 fd，之后任何写操作都报 database is locked。
 *  3. 而且 `readScope()` 依赖 `sqlite3` 命令，没有它就返回 null，
 *     导致 setScoped 回读失败 → 上层误判「root 写库失败」→ 回落 requestScope 弹窗。
 *
 * 所以现在：**系统没有 sqlite3 就用 APK 内置的那份**（assets/bin/sqlite3_arm64，
 * arm64 静态链接，不依赖 libc），全程对线上库执行 SQL，由 SQLite 自己维护一致性。
 *
 * 关键约束：
 *  1. 路径不能写死，LSPosed 版本/root 方案不同，config 目录会变，所以探测。
 *  2. 写完必须回读校验，失败让调用方回滚 UI。
 */
object RootScopeWriter {

    /** LSPosed 数据库候选路径，按优先级。 */
    private val DB_CANDIDATES = listOf(
        "/data/adb/lspd/config/modules_config.db",
        "/data/adb/modules/zygisk_lsposed/config/modules_config.db",
        "/data/adb/lsposed/config/modules_config.db",
        "/data/misc/lspd/config/modules_config.db",
        "/data/adb/modules/riru_lsposed/config/modules_config.db",
    )

    /** 系统里可能已经存在的 sqlite3（优先用，省得解压）。 */
    private val SYSTEM_SQLITE = listOf(
        "/data/adb/ksu/bin/sqlite3",
        "/data/adb/magisk/sqlite3",
        "/system/bin/sqlite3",
        "/system/xbin/sqlite3",
    )

    @Volatile
    private var cachedSqlite: String? = null

    /** 执行 root 命令，返回 stdout+stderr；无法执行返回 null。 */
    private fun su(cmd: String): String? = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        out
    }.getOrNull()

    fun hasRoot(): Boolean = su("id -u")?.trim()?.lines()?.lastOrNull() == "0"

    /**
     * 取得可用的 sqlite3 路径。
     *
     * 先找系统自带的；没有再把这个 APK 里 assets/bin/sqlite3_arm64 解压到私有目录，
     * chmod 755 后用绝对路径调用。这份二进制是静态链接的，arm64 Android 可直接跑。
     */
    private fun sqlite3(ctx: Context?): String? {
        cachedSqlite?.let { if (File(it).canExecute()) return it }

        // 1) 系统里已有的
        val quoted = SYSTEM_SQLITE.joinToString(" ") { "'$it'" }
        val found = su("for f in $quoted; do [ -x \"\$f\" ] && echo \"\$f\" && break; done")
            ?.lines()?.firstOrNull { it.startsWith("/") && it.contains("sqlite3") }
        if (found != null) {
            cachedSqlite = found
            return found
        }

        // 2) 用 APK 自带的
        val c = ctx ?: return null
        val dst = File(c.filesDir, "bin/sqlite3")
        if (!dst.canExecute()) {
            runCatching {
                dst.parentFile?.mkdirs()
                c.assets.open("bin/sqlite3_arm64").use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
            }.onFailure { return null }
            // assets 解出来没有执行位，必须补上
            su("chmod 755 '${dst.absolutePath}'")
        }
        if (!dst.exists()) return null
        // 用 root 执行一次 -version 验证真的能跑（架构不符会在这里暴露）
        val ver = su("'${dst.absolutePath}' -version 2>&1")
        if (ver == null || !ver.contains("3.")) return null
        cachedSqlite = dst.absolutePath
        return dst.absolutePath
    }

    private fun locateDb(): String? {
        val quoted = DB_CANDIDATES.joinToString(" ") { "'$it'" }
        val out = su("for f in $quoted; do [ -f \"\$f\" ] && echo \"\$f\" && break; done")
        return out?.lines()?.firstOrNull { it.startsWith("/") && it.endsWith(".db") }
    }

    /** 读当前作用域；失败返回 null（区别于「空集合」）。 */
    fun readScope(ctx: Context, modulePkg: String): Set<String>? {
        if (modulePkg.isEmpty()) return null
        val db = locateDb() ?: return null
        val sqlite = sqlite3(ctx) ?: return null
        val out = su(
            "'$sqlite' '$db' \"SELECT app_pkg_name FROM scope " +
                "WHERE module_pkg_name='$modulePkg' AND user_id=0;\" 2>/dev/null"
        ) ?: return null
        return out.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("Error") && !it.contains(" ") }
            .toSet()
    }

    /**
     * 写入 / 删除一条作用域记录。
     *
     * 直接对线上库执行 INSERT/DELETE，由 SQLite 保证一致性。
     * **没有任何"拷贝改写"的分支** —— 那条路会毁掉 lspd 手里的 wal。
     */
    fun setScoped(ctx: Context, pkg: String, scoped: Boolean, modulePkg: String): Boolean {
        val db = locateDb() ?: return false
        val sqlite = sqlite3(ctx) ?: return false

        val sql = if (scoped) {
            "INSERT OR IGNORE INTO scope(module_pkg_name, app_pkg_name, user_id) " +
                "VALUES('$modulePkg','$pkg',0);"
        } else {
            "DELETE FROM scope WHERE module_pkg_name='$modulePkg' " +
                "AND app_pkg_name='$pkg' AND user_id=0;"
        }
        // 顺手解锁，避免 LSPosed 拦截后续 scope 请求
        val unlock = "UPDATE modules_state SET scope_request_blocked=0 " +
            "WHERE module_pkg_name='$modulePkg';"

        val run = su(
            "'$sqlite' '$db' \"$sql\" \"$unlock\" 2>&1"
        ) ?: return false

        // 出现 locked 说明这次没写成功，直接判失败让 UI 回滚
        if (run.contains("database is locked") || run.contains("Error")) {
            return false
        }

        // 回读确认
        val now = readScope(ctx, modulePkg) ?: return false
        return if (scoped) pkg in now else pkg !in now
    }
}
