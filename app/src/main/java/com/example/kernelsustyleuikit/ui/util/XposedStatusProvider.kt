package com.example.kernelsustyleuikit.ui.util

import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局 LSPosed 状态源。
 *
 * `XposedServiceHelper.registerListener` 只能注册一次，所以用一个单例保管，
 * 各个页面订阅 [status] 即可。
 */
object XposedStatusProvider {

    private var registered = false

    private val _status = MutableStateFlow(XposedStatus.Disconnected)
    val status: StateFlow<XposedStatus> = _status.asStateFlow()

    private var service: XposedService? = null

    /** 最近一次绑定的 XposedService，供设置页写远程偏好用。 */
    @JvmStatic
    fun service(): XposedService? = service

    @JvmStatic
    fun ensureRegistered() {
        // 必须先注册成功再置位：以前先置 true 再 runCatching，
        // 一旦注册抛异常就永久 true，本进程再也不重试，服务一直是 null
        //（连带所有设置写入静默走本地兜底、首页永远显示未连接）。
        if (registered) return
        val ok = runCatching {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(svc: XposedService) {
                    service = svc
                    _status.value = runCatching {
                        XposedStatus(
                            bound = true,
                            frameworkName = svc.frameworkName ?: "",
                            frameworkVersion = svc.frameworkVersion ?: "",
                            frameworkCode = svc.frameworkVersionCode,
                            apiVersion = svc.apiVersion,
                        )
                    }.getOrElse {
                        XposedStatus(bound = true, frameworkName = "LSPosed")
                    }
                }

                override fun onServiceDied(svc: XposedService) {
                    if (service === svc) service = null
                    _status.value = XposedStatus.Disconnected
                }
            })
            true
        }.getOrDefault(false)
        // 注册成功才置位；失败时保持 false，下次调用会再试一次
        if (ok) registered = true
    }
}
