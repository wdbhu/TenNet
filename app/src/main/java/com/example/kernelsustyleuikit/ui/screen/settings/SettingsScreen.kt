package com.example.kernelsustyleuikit.ui.screen.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kernelsustyleuikit.ui.LocalUiMode
import com.example.kernelsustyleuikit.ui.UiMode
import com.example.kernelsustyleuikit.ui.navigation3.Navigator
import com.example.kernelsustyleuikit.ui.navigation3.Route
import com.example.kernelsustyleuikit.ui.viewmodel.SettingsViewModel

@Composable
fun SettingPager(
    navigator: Navigator,
    bottomInnerPadding: Dp
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val viewModel = viewModel<SettingsViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val actions = SettingsScreenActions(
        onSetCheckUpdate = viewModel::setCheckUpdate,
        onOpenTheme = { navigator.push(Route.ColorPalette) },
        onOpenFont = { navigator.push(Route.Font) },
        onSetUiModeIndex = { index ->
            viewModel.setUiMode(if (index == 0) UiMode.Miuix.value else UiMode.Material.value)
        },
        onOpenAbout = { navigator.push(Route.About) },
        onOpenDonate = { navigator.push(Route.Donate) },
        onSetTnEnabled = viewModel::setTnEnabled,
        onSetTnInjectOnSkip = viewModel::setTnInjectOnSkip,
        onSetTnAutoClose = viewModel::setTnAutoClose,
        onSetTnRiskBypass = viewModel::setTnRiskBypass,
        onSetTnRewardMultiplier = viewModel::setTnRewardMultiplier,
        onCheckUpdateNow = viewModel::checkUpdateNow,
        onDownloadUpdate = { url ->
            if (url.isNotBlank()) {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(url)
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        },
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> SettingPagerMiuix(uiState, actions, bottomInnerPadding)
        UiMode.Material -> SettingPagerMaterial(uiState, actions, bottomInnerPadding)
    }
}
