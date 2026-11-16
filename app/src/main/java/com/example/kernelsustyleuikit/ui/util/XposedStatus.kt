package com.example.kernelsustyleuikit.ui.util

/**
 * LSPosed 框架状态。
 *
 * 模块 App 自身不会被注入，所以这里通过 [io.github.libxposed.service.XposedServiceHelper]
 * 绑定框架服务来判断「已启用 / 已连接」，并读取框架版本。
 */
data class XposedStatus(
    val bound: Boolean = false,
    val frameworkName: String = "",
    val frameworkVersion: String = "",
    val frameworkCode: Long = 0L,
    val apiVersion: Int = 0,
) {
    /** 框架已连接且拿到了 API 版本。 */
    val active: Boolean get() = bound && apiVersion > 0

    companion object {
        val Disconnected = XposedStatus()
    }
}
