package com.example.kernelsustyleuikit.ui.viewmodel

import android.content.SharedPreferences
import com.example.kernelsustyleuikit.ui.util.XposedStatusProvider
import com.fuckad.xx.Config

/**
 * TunNet 配置读写桥。
 *
 * 写入必须走 LSPosed 的 remote preferences：
 * hook 进程侧 [Config.load] 读的就是 `remotePreferences()` 返回的共享内存，
 * 只有通过 `XposedService.getRemotePreferences("ad_helper")` 写进去，目标 App
 * 才会在 `registerOnSharedPreferenceChangeListener` 回调里收到刷新。
 *
 * 以前这里只写模块 App 自己的 SharedPreferences（`Config.setLocal`），
 * 同一台设备上等于写进了一个 hook 侧永远读不到的文件，所以「设置改完不保存、
 * 勾选也没效果」。本地文件只作为服务未连接时的兜底缓存。
 */
object TunNetPrefs {

    private const val PREFS = Config.PREFS_NAME

    /** 取远程共享内存；框架未连接时返回 null。 */
    private fun remote(): SharedPreferences? = runCatching {
        XposedStatusProvider.ensureRegistered()
        XposedStatusProvider.service()?.getRemotePreferences(PREFS)
    }.getOrNull()

    /** 模块 App 自己的那份 prefs；只作为远程不可用时的兜底缓存。 */
    private fun local(): SharedPreferences? = runCatching { Config.local() }.getOrNull()

    fun read(): Snapshot {
        val sp = remote()
        if (sp != null) {
            // 先把远程值灌进 Config 静态缓存，界面显示的就是 hook 侧真实值
            Config.load(sp)
        }
        return Snapshot(
            enabled = Config.enabled(),
            injectOnSkip = Config.injectOnSkip(),
            autoClose = Config.autoClose(),
            riskBypass = Config.riskBypass(),
            rewardMultiplier = Config.rewardMultiplier(),
            autoCloseDelayMs = Config.autoCloseDelayMs(),
            rewardDelayMs = Config.rewardDelayMs(),
            toast = Config.toast(),
            dedupe = Config.dedupe(),
            debug = Config.debug(),
        )
    }

    /** 当前配置真正写在哪：true = LSPosed 远程，false = 仅本地兜底。 */
    fun remoteReady(): Boolean = remote() != null

    fun writeEnabled(v: Boolean) = writeBoolean(Config.KEY_ENABLED, v)

    /**
     * 写一个布尔值。成功写入远程时返回 true。
     * commit 而不是 apply：LSPosed 的共享内存由服务进程落盘，
     * apply 的异步刷盘在跨进程场景下容易丢。
     */
    fun writeBoolean(key: String, v: Boolean): Boolean {
        val sp = remote()
        if (sp == null) {
            writeLocalFallback(key, v)
            return false
        }
        val ok = runCatching { sp.edit().putBoolean(key, v).commit() }.getOrDefault(false)
        Config.load(sp)
        syncLocal(key, v)
        return ok
    }

    fun writeInt(key: String, v: Int): Boolean {
        val sp = remote()
        if (sp == null) {
            writeLocalFallback(key, v)
            return false
        }
        val ok = runCatching { sp.edit().putInt(key, v).commit() }.getOrDefault(false)
        Config.load(sp)
        syncLocal(key, v)
        return ok
    }

    fun writeString(key: String, v: String): Boolean {
        val sp = remote()
        if (sp == null) {
            writeLocalFallback(key, v)
            return false
        }
        val ok = runCatching { sp.edit().putString(key, v).commit() }.getOrDefault(false)
        Config.load(sp)
        syncLocal(key, v)
        return ok
    }

    /** 写字符串集合（勾选名单 scope_apps）。 */
    fun writeStringSet(key: String, v: Set<String>): Boolean {
        val sp = remote()
        if (sp == null) return false
        val ok = runCatching { sp.edit().putStringSet(key, v).commit() }.getOrDefault(false)
        Config.load(sp)
        return ok
    }

    /** 读字符串集合；不存在返回 null。 */
    fun readStringSet(key: String): Set<String>? {
        val sp = remote() ?: return null
        return runCatching { sp.getStringSet(key, null)?.let { java.util.HashSet(it) } }.getOrNull()
    }

    /** 远程不可用时写本地，至少让界面状态不丢。 */
    private fun writeLocalFallback(key: String, value: Any) {
        runCatching {
            when (value) {
                is Boolean -> Config.setLocal(key, value)
                is Int -> Config.setLocal(key, value)
                is String -> Config.setLocal(key, value)
            }
        }
    }

    /** 远程写成功后顺手同步一份到本地，服务掉线时界面不至于回滚成默认值。 */
    private fun syncLocal(key: String, value: Any) {
        runCatching {
            val sp = local() ?: return
            val e = sp.edit()
            when (value) {
                is Boolean -> e.putBoolean(key, value)
                is Int -> e.putInt(key, value)
                is String -> e.putString(key, value)
            }
            e.commit()
        }
    }

    data class Snapshot(
        val enabled: Boolean,
        val injectOnSkip: Boolean,
        val autoClose: Boolean,
        val riskBypass: Boolean,
        val rewardMultiplier: Int,
        val autoCloseDelayMs: Int,
        val rewardDelayMs: Int,
        val toast: Boolean,
        val dedupe: Boolean,
        val debug: Boolean,
    )
}
