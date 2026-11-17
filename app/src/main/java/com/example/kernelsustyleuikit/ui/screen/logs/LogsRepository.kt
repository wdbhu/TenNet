package com.example.kernelsustyleuikit.ui.screen.logs

import android.content.Context
import com.example.kernelsustyleuikit.ui.util.XposedStatusProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 单条日志。 */
data class LogItem(
    val packageName: String,
    val label: String,
    val text: String,
    val lines: List<String>,
)

/**
 * 读取各目标 App 的 hook 日志。
 *
 * 首发方案是让 hook 进程把日志写进 LSPosed 远程偏好（key = log_<包名>），
 * 但 libxposed 的 RemotePreferences 只保证「模块 → 目标」单向下发；
 * 目标进程侧 `edit().commit()` 是静默失败的（不抛异常也不落库），
 * 于是日志页永远空白。
 *
 * 现在改走文件：hook 进程本来就把日志写在自己进程的 cache/tunnet.log
 * （那是它自己的目录，写入必然成功），模块侧用 root 把文件内容读出来。
 * 没有 root 时退回远程偏好，至少不崩。
 */
object LogsRepository {

    private const val PREFS_NAME = "ad_helper"
    private const val KEY_PREFIX = "log_"

    /**
     * 目标进程的 cache 目录。模块进程的 mount namespace 里 /data/data/<pkg>
     * 不存在，只能走 /data_mirror；两个前缀都试一遍。
     */
    private fun candidateLogPaths(pkg: String): List<String> = listOf(
        "/data_mirror/data_ce/null/0/$pkg/cache/tunnet.log",
        "/data/data/$pkg/cache/tunnet.log",
        "/data/user/0/$pkg/cache/tunnet.log",
    ).distinct()

    /**
     * 用 root 跑一句命令，带超时。
     *
     * 以前没有超时：root 管理器弹出授权框而用户不响应时 su 会一直挂着，
     * load() 的 IO 协程永久阻塞，日志页卡在 loading=true，点刷新也没反应。
     */
    private fun suExec(cmd: String, timeoutSec: Long = 8L): String? = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val reader = p.inputStream.bufferedReader()
        val text = StringBuilder()
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val t = Thread {
            try {
                text.append(reader.readText())
            } catch (ignored: Throwable) {
            } finally {
                done.set(true)
            }
        }
        t.isDaemon = true
        t.start()
        val deadline = System.currentTimeMillis() + timeoutSec * 1000
        while (!done.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        if (!done.get()) {
            // 超时：杀掉 su，避免进程泄漏
            runCatching { p.destroyForcibly() }
            t.interrupt()
            return null
        }
        runCatching { p.waitFor() }
        text.toString().ifBlank { null }
    }.getOrNull()

    /** 用 root 读一个文件；失败返回 null。 */
    private fun readWithRoot(path: String): String? = suExec("cat '$path' 2>/dev/null")

    /**
     * 用 root 列出所有目标 App 的日志文件。
     *
     * 之前只扫 /data_mirror 一条前缀，而清空走的是三条候选路径，
     * 读写不对称：落在 /data/user/0 的设备上列表恒空、但"清空"却能成功。
     * 现在三条前缀都扫。
     */
    private fun listLogFiles(): List<String> = runCatching {
        val cmd = listOf(
            "/data_mirror/data_ce/null/0",
            "/data/user/0",
            "/data/data",
        ).joinToString(" ") { "ls -1 '$it'/*/cache/tunnet.log 2>/dev/null;" }
        suExec(cmd)?.lines()
            ?.filter { it.isNotBlank() && it.endsWith("tunnet.log") }
            ?.distinct()
            ?: emptyList()
    }.getOrDefault(emptyList())

    suspend fun load(context: Context): List<LogItem> = withContext(Dispatchers.IO) {
        val pm = context.packageManager

        // 优先 root 文件通道
        val fromFiles = runCatching {
            listLogFiles().mapNotNull { path ->
                val pkg = path.substringBefore("/cache/").substringAfterLast("/0/")
                if (pkg.isBlank() || pkg.contains("/")) return@mapNotNull null
                val raw = readWithRoot(path) ?: return@mapNotNull null
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                }.getOrDefault(pkg)
                LogItem(pkg, label, raw, raw.lines().filter { it.isNotBlank() }.takeLast(400))
            }.sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())

        if (fromFiles.isNotEmpty()) return@withContext fromFiles

        // 兜底：远程偏好（目标进程写入通常失败，但模块自身可能写过）
        runCatching {
            val svc = XposedStatusProvider.service() ?: return@withContext emptyList()
            val prefs = svc.getRemotePreferences(PREFS_NAME)
            prefs.all.filterKeys { it.startsWith(KEY_PREFIX) }
                .mapNotNull { (key, value) ->
                    val pkg = key.removePrefix(KEY_PREFIX)
                    val raw = (value as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val label = runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    }.getOrDefault(pkg)
                    LogItem(pkg, label, raw, raw.lines().filter { it.isNotBlank() }.takeLast(400))
                }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }

    /** 清空某个 App 的日志文件。 */
    suspend fun clear(packageName: String): Boolean = withContext(Dispatchers.IO) {
        candidateLogPaths(packageName).any { path ->
            // 走带超时的 suExec：授权框不响应时不至于把 IO 线程永久挂住
            suExec(": > '$path' 2>/dev/null || true") != null ||
                runCatching { java.io.File(path).let { !it.exists() || it.length() == 0L } }
                    .getOrDefault(false)
        }
    }
}
