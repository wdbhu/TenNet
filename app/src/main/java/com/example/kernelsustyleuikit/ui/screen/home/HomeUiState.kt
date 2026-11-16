package com.example.kernelsustyleuikit.ui.screen.home

import androidx.compose.runtime.Immutable
import com.example.kernelsustyleuikit.ui.util.LatestVersionInfo
import com.example.kernelsustyleuikit.ui.util.XposedStatus

@Immutable
data class HomeUiState(
    val checkUpdateEnabled: Boolean,
    val latestVersionInfo: LatestVersionInfo,
    val currentAppVersionCode: Long,
    val systemInfo: SystemInfo,
    /** LSPosed 框架连接状态。 */
    val xposed: XposedStatus = XposedStatus(),
    /** 已适配（作用域内）的 App 数量。 */
    val adaptedCount: Int = 0,
    /** 已启用（已勾选）的 App 数量。 */
    val enabledCount: Int = 0,
    /** 构建时间。 */
    val buildTime: String = "",
) {
    /** 有新版本、且用户没关掉「检查更新」时才提示。 */
    val hasUpdate: Boolean
        get() = checkUpdateEnabled &&
            latestVersionInfo.versionCode > currentAppVersionCode
}

@Immutable
data class HomeActions(
    val onPermissionsClick: () -> Unit,
    val onOpenUrl: (String) -> Unit,
    val onOpenLogs: () -> Unit = {},
)
