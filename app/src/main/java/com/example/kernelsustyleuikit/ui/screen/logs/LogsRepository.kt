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
     * 超时时销毁进程并关闭流，让阻塞在管道读的线程立即退出，
     * 避免读线程和 su 子进程泄漏。
     */
    private fun suExec(cmd: String, timeoutSec: Long = 8L): String? = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = StringBuilder()
        try {
            p.inputStream.use { input ->
                val reader = input.bufferedReader()
                val done = java.util.concurrent.atomic.AtomicBoolean(false)
                val t = Thread {
                    try {
                        reader.forEachLine { out.appendLine(it) }
                    } catch (ignored: Throwable) {
                    } finally {
                        done.set(true)
                    }
                }
                t.isDaemon = true
                t.start()
                val finished = p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
                if (!finished) {
                    // 超时：先销毁进程，再关闭流。关闭流会让 readLine 抛异常、
                    // 读线程退出，不会残留。
                    runCatching { p.destroyForcibly() }
                }
                // 给读线程一点时间消化已读内容并退出
                runCatching { p.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
                // 关闭流（上面的 use 块结束时会关闭），触发读线程退出
            }
        } finally {
            runCatching { p.destroyForcibly() }
        }
        out.toString().ifBlank { null }
    }.getOrNull()

    /** 用 root 读一个文件；失败返回 null。 */
    private fun readWithRoot(path: String): String? = suExec("cat '$path' 2>/dev/null")

    /**
     * 用 root 列出所有目标 App 的日志文件。
     *
     * 三条前缀都扫，与 clear 的候选路径对齐，避免读写不对称。
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
            runCatching {
                val p = ProcessBuilder("su", "-c", ": > '$path' 2>/dev/null").start()
                p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)
                val ok = !p.isAlive && p.exitValue() == 0
                if (!ok) runCatching { p.destroyForcibly() }
                ok
            }.getOrDefault(false)
        }
    }
}
