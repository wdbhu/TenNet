package com.example.kernelsustyleuikit.ui.viewmodel

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.kernelsustyleuikit.templateApp
import com.example.kernelsustyleuikit.ui.screen.home.HomeUiState
import com.example.kernelsustyleuikit.ui.screen.home.SystemInfo
import com.example.kernelsustyleuikit.ui.screen.home.getAppVersion
import com.example.kernelsustyleuikit.ui.util.LatestVersionInfo
import com.example.kernelsustyleuikit.ui.util.checkNewVersion
import com.example.kernelsustyleuikit.ui.util.resolveDeviceName
import com.example.kernelsustyleuikit.ui.util.XposedStatusProvider

class HomeViewModel : ViewModel() {

    // 先用空状态起手，避免构造时同步跑 buildState()（内含 Binder 调用会卡首屏）
    private val _uiState = MutableStateFlow(
        HomeUiState(
            checkUpdateEnabled = true,
            latestVersionInfo = LatestVersionInfo(),
            currentAppVersionCode = 0L,
            systemInfo = SystemInfo(
                appVersion = "",
                deviceModel = "",
                androidVersion = "",
            ),
        )
    )

    init {
        XposedStatusProvider.ensureRegistered()
        // 构造后立刻异步填充一次，UI 不会等
        refresh()
        // LSPosed 的服务绑定是异步的：首次 buildState() 读到的往往还是 Disconnected，
        // 于是首页要停一会儿才从"未连接"翻成"工作中"。这里直接订阅状态流，
        // 服务一绑上就立刻把新状态推进 UI，不再等下一次 refresh 轮询。
        viewModelScope.launch {
            XposedStatusProvider.status.collect { st ->
                _uiState.update { it.copy(xposed = st, systemInfo = it.systemInfo.copy(
                    frameworkVersion = if (st.active) {
                        "${st.frameworkName.ifEmpty { "LSPosed" }} ${st.frameworkVersion} (${st.frameworkCode}) , API ${st.apiVersion}"
                    } else ""
                )) }
            }
        }
    }
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null

    /** 上次查版本的时间戳；切页回来时不必每次重新请求网络。 */
    @Volatile
    private var lastVersionCheckAt = 0L
    private val versionCheckWindowMs = 10 * 60 * 1000L

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val baseState = withContext(Dispatchers.IO) { buildState() }
            _uiState.update { baseState }
            // 版本检查走网络，延后 1.5s 再跑，别和首屏渲染抢带宽和 CPU。
            // 10 分钟内已经查过就不再重复请求：首页和「应用」「设置」之间来回切
            // 会反复触发 refresh，每次都打一次接口纯属浪费。
            val now = System.currentTimeMillis()
            if (baseState.checkUpdateEnabled && now - lastVersionCheckAt > versionCheckWindowMs) {
                delay(1500)
                val latestVersionInfo = withContext(Dispatchers.IO) { checkNewVersion() }
                lastVersionCheckAt = System.currentTimeMillis()
                _uiState.update { it.copy(latestVersionInfo = latestVersionInfo) }
            }
        }
    }

    private fun buildState(): HomeUiState {
        val appVersion = getAppVersion(templateApp)

        return HomeUiState(
            checkUpdateEnabled = templateApp.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("check_update", true),
            latestVersionInfo = LatestVersionInfo(),
            currentAppVersionCode = appVersion.versionCode,
            xposed = XposedStatusProvider.status.value,
            adaptedCount = runCatching { com.fuckad.xx.core.Rules.all().size }.getOrDefault(0),
            // 必须和应用页的勾选状态同源：勾选走 root 直写 LSPosed 数据库，
            // 而 XposedService.scope 是服务端缓存，两者可能短暂不同步。
            // 以前这里优先读 service.scope，会出现「首页已启用 1、应用页全没勾」。
            enabledCount = runCatching {
                com.example.kernelsustyleuikit.ui.screen.apps.ScopeController
                    .currentScope(templateApp).size
            }.getOrDefault(0),
            buildTime = runCatching {
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(
                        templateApp.packageManager
                            .getPackageInfo(templateApp.packageName, 0)
                            .lastUpdateTime
                    ))
            }.getOrDefault(""),
            systemInfo = SystemInfo(
                appVersion = "${appVersion.versionName} (${appVersion.versionCode})",
                deviceModel = resolveDeviceName(),
                androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                frameworkVersion = run {
                    val x = XposedStatusProvider.status.value
                    if (x.active) {
                        "${x.frameworkName.ifEmpty { "LSPosed" }} ${x.frameworkVersion} (${x.frameworkCode}) , API ${x.apiVersion}"
                    } else ""
                },
            ),
        )
    }
}
