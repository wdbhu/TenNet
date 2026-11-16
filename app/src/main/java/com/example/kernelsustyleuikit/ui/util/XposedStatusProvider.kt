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
        if (registered) return
        registered = true
        runCatching {
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
        }
    }
}
