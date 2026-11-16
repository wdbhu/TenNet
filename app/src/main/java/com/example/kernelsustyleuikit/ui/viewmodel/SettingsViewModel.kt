package com.example.kernelsustyleuikit.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.example.kernelsustyleuikit.data.repository.SettingsRepository
import com.example.kernelsustyleuikit.data.repository.SettingsRepositoryImpl
import com.example.kernelsustyleuikit.ui.screen.settings.SettingsUiState
import com.example.kernelsustyleuikit.ui.theme.ColorMode

class SettingsViewModel(
    private val repo: SettingsRepository = SettingsRepositoryImpl()
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val checkUpdate = repo.checkUpdate
            val themeMode = repo.themeMode
            val miuixMonet = repo.miuixMonet
            val keyColor = repo.keyColor
            val enablePredictiveBack = repo.enablePredictiveBack
            val enableSwipeDismiss = repo.enableSwipeDismiss
            val pagerInterceptionMode = repo.pagerInterceptionMode
            val enableBlur = repo.enableBlur
            val enableFloatingBottomBar = repo.enableFloatingBottomBar
            val enableFloatingBottomBarBlur = repo.enableFloatingBottomBarBlur
            val pageScale = repo.pageScale
            val colorStyle = repo.colorStyle
            val colorSpec = repo.colorSpec
            val uiMode = repo.uiMode
            val tn = TunNetPrefs.read()

            _uiState.update {
                it.copy(
                    uiMode = uiMode,
                    checkUpdate = checkUpdate,
                    themeMode = themeMode,
                    miuixMonet = miuixMonet,
                    keyColor = keyColor,
                    enablePredictiveBack = enablePredictiveBack,
                    enableSwipeDismiss = enableSwipeDismiss,
                    pagerInterceptionMode = pagerInterceptionMode,
                    enableBlur = enableBlur,
                    enableFloatingBottomBar = enableFloatingBottomBar,
                    enableFloatingBottomBarBlur = enableFloatingBottomBarBlur,
                    pageScale = pageScale,
                    colorStyle = colorStyle,
                    colorSpec = colorSpec,
                    tnEnabled = tn.enabled,
                    tnInjectOnSkip = tn.injectOnSkip,
                    tnAutoClose = tn.autoClose,
                    tnRiskBypass = tn.riskBypass,
                    tnRewardMultiplier = tn.rewardMultiplier,
                    tnAutoCloseDelayMs = tn.autoCloseDelayMs,
                    tnRewardDelayMs = tn.rewardDelayMs,
                    tnToast = tn.toast,
                    tnDedupe = tn.dedupe,
                    tnDebug = tn.debug,
                )
            }
        }
    }

    fun setCheckUpdate(enabled: Boolean) {
        repo.checkUpdate = enabled
        _uiState.update { it.copy(checkUpdate = enabled) }
    }

    fun setUiMode(mode: String) {
        val oldMode = repo.uiMode
        val currentThemeMode = repo.themeMode

        val newThemeMode = when (oldMode) {
            "material" if mode == "miuix" -> {
                val colorMode = ColorMode.fromValue(currentThemeMode)
                val baseMode = if (colorMode == ColorMode.DARK_AMOLED) 2 else currentThemeMode
                if (repo.miuixMonet && !colorMode.isMonet) {
                    ColorMode.fromValue(baseMode).toMonetMode()
                } else if (!repo.miuixMonet && colorMode.isMonet) {
                    ColorMode.fromValue(baseMode).toNonMonetMode()
                } else baseMode
            }

            "miuix" if mode == "material" -> {
                val colorMode = ColorMode.fromValue(currentThemeMode)
                if (colorMode.isMonet) {
                    colorMode.toNonMonetMode()
                } else currentThemeMode
            }

            else -> currentThemeMode
        }

        repo.uiMode = mode
        repo.themeMode = newThemeMode
        _uiState.update { it.copy(uiMode = mode, themeMode = newThemeMode) }
    }

    fun setThemeMode(mode: Int) {
        val currentUiMode = repo.uiMode
        val effectiveMode = if (currentUiMode == "miuix" && _uiState.value.miuixMonet) {
            mode + 3
        } else {
            mode
        }
        repo.themeMode = effectiveMode
        _uiState.update { it.copy(themeMode = effectiveMode) }
    }

    fun setColorMode(mode: ColorMode) {
        repo.themeMode = mode.value
        _uiState.update { it.copy(themeMode = mode.value) }
    }

    fun setMiuixMonet(enabled: Boolean) {
        val currentThemeMode = repo.themeMode
        val colorMode = ColorMode.fromValue(currentThemeMode)
        val newThemeMode = if (enabled) {
            if (!colorMode.isMonet) colorMode.toMonetMode() else currentThemeMode
        } else {
            if (colorMode.isMonet) colorMode.toNonMonetMode() else currentThemeMode
        }
        repo.miuixMonet = enabled
        repo.themeMode = newThemeMode
        _uiState.update { it.copy(miuixMonet = enabled, themeMode = newThemeMode) }
    }

    fun setKeyColor(color: Int) {
        repo.keyColor = color
        _uiState.update { it.copy(keyColor = color) }
    }

    fun setColorStyle(style: String) {
        repo.colorStyle = style
        _uiState.update { it.copy(colorStyle = style) }
    }

    fun setColorSpec(spec: String) {
        repo.colorSpec = spec
        _uiState.update { it.copy(colorSpec = spec) }
    }

    /**
     * 立即检查更新。
     *
     * checkState: 0=未检查 1=检查中 2=已是最新 3=有新版本 4=失败
     */
    fun checkUpdateNow() {
        if (_uiState.value.checkState == 1) return
        _uiState.update { it.copy(checkState = 1) }
        viewModelScope.launch {
            val info = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.example.kernelsustyleuikit.ui.util.checkNewVersion()
            }
            val current = com.example.kernelsustyleuikit.BuildConfig.VERSION_CODE
            val state = when {
                info.versionCode <= 0 -> 4                 // 请求失败或解析不到
                info.versionCode > current -> 3            // 有新版本
                else -> 2                                  // 已是最新
            }
            _uiState.update {
                it.copy(
                    checkState = state,
                    remoteVersionName = info.versionName,
                    updateDownloadUrl = info.downloadUrl,
                )
            }
        }
    }

    fun setEnablePredictiveBack(enabled: Boolean) {
        repo.enablePredictiveBack = enabled
        _uiState.update { it.copy(enablePredictiveBack = enabled) }
    }

    fun setEnableSwipeDismiss(enabled: Boolean) {
        repo.enableSwipeDismiss = enabled
        _uiState.update { it.copy(enableSwipeDismiss = enabled) }
    }

    fun setPagerInterceptionMode(mode: Int) {
        val effectiveMode = mode.coerceIn(0, 2)
        repo.pagerInterceptionMode = effectiveMode
        _uiState.update { it.copy(pagerInterceptionMode = effectiveMode) }
    }

    fun setEnableBlur(enabled: Boolean) {
        repo.enableBlur = enabled
        _uiState.update { it.copy(enableBlur = enabled) }
    }

    fun setEnableFloatingBottomBar(enabled: Boolean) {
        repo.enableFloatingBottomBar = enabled
        _uiState.update { it.copy(enableFloatingBottomBar = enabled) }
    }

    fun setEnableFloatingBottomBarBlur(enabled: Boolean) {
        repo.enableFloatingBottomBarBlur = enabled
        _uiState.update { it.copy(enableFloatingBottomBarBlur = enabled) }
    }

    fun setPageScale(scale: Float) {
        repo.pageScale = scale
        _uiState.update { it.copy(pageScale = scale) }
    }
    // ================= TunNet =================
    // 写入后统一回读一次：写的是 LSPosed 远程共享内存，只有回读一致才算真保存。
    // 以前只写模块本地 prefs 再直接改 UI 状态，界面看着变了、hook 侧什么都没收到。

    fun setTnEnabled(v: Boolean) {
        TunNetPrefs.writeEnabled(v)
        refreshTn()
    }

    fun setTnInjectOnSkip(v: Boolean) {
        TunNetPrefs.writeBoolean("inject_on_skip", v)
        refreshTn()
    }

    fun setTnAutoClose(v: Boolean) {
        TunNetPrefs.writeBoolean("auto_close", v)
        refreshTn()
    }

    fun setTnRiskBypass(v: Boolean) {
        TunNetPrefs.writeBoolean("risk_bypass", v)
        refreshTn()
    }

    /** 入参是下拉框下标，落盘的是真实倍率数值。 */
    fun setTnRewardMultiplier(index: Int) {
        TunNetPrefs.writeInt("reward_multiplier", RewardMultiplier.multiplierAt(index))
        refreshTn()
    }

    /** 关闭延迟：入参是选项下标。 */
    fun setTnAutoCloseDelay(index: Int) {
        TunNetPrefs.writeInt("auto_close_delay", DelayOptions.AUTO_CLOSE.getOrElse(index) { 2500 })
        refreshTn()
    }

    /** 补发时机：入参是选项下标。 */
    fun setTnRewardDelay(index: Int) {
        TunNetPrefs.writeInt("reward_delay", DelayOptions.REWARD.getOrElse(index) { 0 })
        refreshTn()
    }

    fun setTnToast(v: Boolean) {
        TunNetPrefs.writeBoolean("toast", v)
        refreshTn()
    }

    fun setTnDedupe(v: Boolean) {
        TunNetPrefs.writeBoolean("dedupe", v)
        refreshTn()
    }

    /** 从真实配置源回读，界面永远显示落盘后的值。 */
    private fun refreshTn() {
        val tn = TunNetPrefs.read()
        _uiState.update {
            it.copy(
                tnEnabled = tn.enabled,
                tnInjectOnSkip = tn.injectOnSkip,
                tnAutoClose = tn.autoClose,
                tnRiskBypass = tn.riskBypass,
                tnRewardMultiplier = tn.rewardMultiplier,
                tnAutoCloseDelayMs = tn.autoCloseDelayMs,
                tnRewardDelayMs = tn.rewardDelayMs,
                tnToast = tn.toast,
                tnDedupe = tn.dedupe,
                tnDebug = tn.debug,
            )
        }
    }
}
