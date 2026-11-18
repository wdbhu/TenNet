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

    @Volatile
    private var registered = false

    private val _status = MutableStateFlow(XposedStatus.Disconnected)
    val status: StateFlow<XposedStatus> = _status.asStateFlow()

    @Volatile
    private var service: XposedService? = null

    /** 最近一次绑定的 XposedService，供设置页写远程偏好用。 */
    @JvmStatic
    fun service(): XposedService? = service

    @JvmStatic
    fun ensureRegistered() {
        // 快路径：已注册直接返回。registered 用 @Volatile，配合下面的 synchronized
        // 避免主线程与 IO 线程并发首次进入时重复调用 registerListener。
        if (registered) return
        synchronized(this) {
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
                        // 服务进程重启后 helper 未必会自动重绑，复位 registered，
                        // 让下一次 ensureRegistered 有机会重新注册。
                        registered = false
                    }
                })
                true
            }.getOrDefault(false)
            // 注册成功才置位；失败时保持 false，下次调用会再试一次
            if (ok) registered = true
        }
    }
}
