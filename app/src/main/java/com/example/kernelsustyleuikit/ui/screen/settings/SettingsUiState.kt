package com.example.kernelsustyleuikit.ui.screen.settings

import androidx.compose.runtime.Immutable
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.example.kernelsustyleuikit.ui.UiMode

@Immutable
data class SettingsUiState(
    val uiMode: String = UiMode.DEFAULT_VALUE,
    val checkUpdate: Boolean = true,
    val themeMode: Int = 0,
    val miuixMonet: Boolean = false,
    val keyColor: Int = 0,
    val colorStyle: String = PaletteStyle.TonalSpot.name,
    val colorSpec: String = ColorSpec.SpecVersion.Default.name,
    val enablePredictiveBack: Boolean = true,
    val enableSwipeDismiss: Boolean = true,
    val pagerInterceptionMode: Int = 1,
    val enableBlur: Boolean = true,
    val enableFloatingBottomBar: Boolean = true,
    val enableFloatingBottomBarBlur: Boolean = true,
    val pageScale: Float = 1.0f,

    // ---- 立即检查更新 ----
    /** 检查进度：0=未检查 1=检查中 2=已是最新 3=有新版本 4=检查失败 */
    val checkState: Int = 0,
    /** 远端版本显示用，例如 "7.18" */
    val remoteVersionName: String = "",
    /** 有新版本时的下载地址 */
    val updateDownloadUrl: String = "",

    // ---- TunNet 激励视频拦截 ----
    val tnEnabled: Boolean = true,
    val tnInjectOnSkip: Boolean = true,
    val tnAutoClose: Boolean = false,
    val tnRiskBypass: Boolean = true,
    val tnRewardMultiplier: Int = 1,
    val tnAutoCloseDelayMs: Int = 2500,
    val tnRewardDelayMs: Int = 0,
    val tnToast: Boolean = true,
    val tnDedupe: Boolean = true,
    val tnDebug: Boolean = true,
    val tnFillNullArgs: Boolean = false,
)

@Immutable
data class SettingsScreenActions(
    val onSetCheckUpdate: (Boolean) -> Unit,
    val onOpenTheme: () -> Unit,
    val onOpenFont: () -> Unit = {},
    val onSetUiModeIndex: (Int) -> Unit,
    val onOpenAbout: () -> Unit,
    val onOpenDonate: () -> Unit = {},

    // ---- 立即检查更新 ----
    val onCheckUpdateNow: () -> Unit = {},
    val onDownloadUpdate: (String) -> Unit = {},

    // ---- TunNet ----
    val onSetTnEnabled: (Boolean) -> Unit = {},
    val onSetTnInjectOnSkip: (Boolean) -> Unit = {},
    val onSetTnAutoClose: (Boolean) -> Unit = {},
    val onSetTnRiskBypass: (Boolean) -> Unit = {},
    val onSetTnRewardMultiplier: (Int) -> Unit = {},
    val onSetTnFillNullArgs: (Boolean) -> Unit = {},
)
