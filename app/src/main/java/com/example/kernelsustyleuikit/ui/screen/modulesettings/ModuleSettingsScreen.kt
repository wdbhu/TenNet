package com.example.kernelsustyleuikit.ui.screen.modulesettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kernelsustyleuikit.R
import com.example.kernelsustyleuikit.ui.theme.LocalEnableBlur
import com.example.kernelsustyleuikit.ui.util.BlurredBar
import com.example.kernelsustyleuikit.ui.util.rememberBlurBackdrop
import com.example.kernelsustyleuikit.ui.viewmodel.DelayOptions
import com.example.kernelsustyleuikit.ui.viewmodel.RewardMultiplier
import com.example.kernelsustyleuikit.ui.viewmodel.SettingsViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun ModuleSettingsScreen() {
    val viewModel = viewModel<SettingsViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = "模块设置",
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars
            .add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .scrollEndHaptic()
                .overScrollVertical()
                .padding(horizontal = 12.dp),
            contentPadding = innerPadding,
            overscrollEffect = null,
        ) {
            item {
                Column(
                    modifier = Modifier.padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SmallTitle(text = stringResource(R.string.tunnet_section))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        SwitchPreference(
                            title = stringResource(R.string.tunnet_enabled),
                            summary = stringResource(R.string.tunnet_enabled_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    tint = colorScheme.onBackground,
                                )
                            },
                            checked = uiState.tnEnabled,
                            onCheckedChange = viewModel::setTnEnabled,
                        )
                        SwitchPreference(
                            title = stringResource(R.string.tunnet_inject),
                            summary = stringResource(R.string.tunnet_inject_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.CardGiftcard,
                                    contentDescription = null,
                                    tint = colorScheme.onBackground,
                                )
                            },
                            checked = uiState.tnInjectOnSkip,
                            onCheckedChange = viewModel::setTnInjectOnSkip,
                        )
                        SwitchPreference(
                            title = stringResource(R.string.tunnet_autoclose),
                            summary = stringResource(R.string.tunnet_autoclose_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = null,
                                    tint = colorScheme.onBackground,
                                )
                            },
                            checked = uiState.tnAutoClose,
                            onCheckedChange = viewModel::setTnAutoClose,
                        )
                        SwitchPreference(
                            title = stringResource(R.string.tunnet_risk),
                            summary = stringResource(R.string.tunnet_risk_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.Shield,
                                    contentDescription = null,
                                    tint = colorScheme.onBackground,
                                )
                            },
                            checked = uiState.tnRiskBypass,
                            onCheckedChange = viewModel::setTnRiskBypass,
                        )
                        OverlayDropdownPreference(
                            title = stringResource(R.string.tunnet_multiplier),
                            summary = stringResource(R.string.tunnet_multiplier_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.TrendingUp,
                                    contentDescription = null,
                                    tint = colorScheme.onBackground,
                                )
                            },
                            items = RewardMultiplier.OPTIONS.map { RewardMultiplier.label(it) },
                            selectedIndex = RewardMultiplier.indexOf(uiState.tnRewardMultiplier),
                            onSelectedIndexChange = viewModel::setTnRewardMultiplier,
                        )
                        OverlayDropdownPreference(
                            title = stringResource(R.string.tunnet_autoclose_delay),
                            summary = stringResource(R.string.tunnet_autoclose_delay_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.Schedule,
                                    contentDescription = null,
                                    tint = colorScheme.onBackground,
                                )
                            },
                            items = DelayOptions.AUTO_CLOSE.map { DelayOptions.formatSeconds(it) },
                            selectedIndex = DelayOptions.autoCloseIndex(uiState.tnAutoCloseDelayMs),
                            onSelectedIndexChange = viewModel::setTnAutoCloseDelay,
                        )
                        OverlayDropdownPreference(
                            title = stringResource(R.string.tunnet_reward_delay),
                            summary = stringResource(R.string.tunnet_reward_delay_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.Timelapse,
                                    contentDescription = null,
                                    tint = colorScheme.onBackground,
                                )
                            },
                            items = DelayOptions.REWARD.map { DelayOptions.labelMs(it) },
                            selectedIndex = DelayOptions.rewardIndex(uiState.tnRewardDelayMs),
                            onSelectedIndexChange = viewModel::setTnRewardDelay,
                        )
                    }

                    // ---- 功能卡片：提示 / 去重 / 调试 ----
                    SmallTitle(text = stringResource(R.string.tunnet_more_section))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        SwitchPreference(
                            title = stringResource(R.string.tunnet_toast),
                            summary = stringResource(R.string.tunnet_toast_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.Notifications,
                                    contentDescription = null,
                                    tint = colorScheme.onBackground,
                                )
                            },
                            checked = uiState.tnToast,
                            onCheckedChange = viewModel::setTnToast,
                        )
                        SwitchPreference(
                            title = stringResource(R.string.tunnet_dedupe),
                            summary = stringResource(R.string.tunnet_dedupe_summary),
                            startAction = {
                                Icon(
                                    Icons.Rounded.ContentCopy,
                                    contentDescription = null,
                                    tint = colorScheme.onBackground,
                                )
                            },
                            checked = uiState.tnDedupe,
                            onCheckedChange = viewModel::setTnDedupe,
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}
