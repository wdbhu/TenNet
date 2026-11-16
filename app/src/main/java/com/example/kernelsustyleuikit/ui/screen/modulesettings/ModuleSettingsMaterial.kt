package com.example.kernelsustyleuikit.ui.screen.modulesettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.kernelsustyleuikit.R
import com.example.kernelsustyleuikit.ui.component.material.SegmentedColumn
import com.example.kernelsustyleuikit.ui.component.material.SegmentedListItem
import com.example.kernelsustyleuikit.ui.component.material.SwitchItem
import com.example.kernelsustyleuikit.ui.viewmodel.DelayOptions
import com.example.kernelsustyleuikit.ui.viewmodel.RewardMultiplier
import com.example.kernelsustyleuikit.ui.viewmodel.SettingsViewModel

/**
 * 模块设置页的 Material 风格版本。
 *
 * 与 Miuix 版同一套 ViewModel / 配置键，只是换成 Material3 组件：
 * ListItem + Switch 开关、点击弹出单选对话框代替下拉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleSettingsScreenMaterial(onBack: () -> Unit) {
    val viewModel = viewModel<SettingsViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模块设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.tunnet_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item {
                SegmentedColumn(content = listOf {
                    SwitchItem(
                        icon = Icons.Rounded.PlayArrow,
                        title = stringResource(R.string.tunnet_enabled),
                        summary = stringResource(R.string.tunnet_enabled_summary),
                        checked = uiState.tnEnabled,
                        onCheckedChange = viewModel::setTnEnabled,
                    )
                    SwitchItem(
                        icon = Icons.Rounded.CardGiftcard,
                        title = stringResource(R.string.tunnet_inject),
                        summary = stringResource(R.string.tunnet_inject_summary),
                        checked = uiState.tnInjectOnSkip,
                        onCheckedChange = viewModel::setTnInjectOnSkip,
                    )
                    SwitchItem(
                        icon = Icons.Rounded.Close,
                        title = stringResource(R.string.tunnet_autoclose),
                        summary = stringResource(R.string.tunnet_autoclose_summary),
                        checked = uiState.tnAutoClose,
                        onCheckedChange = viewModel::setTnAutoClose,
                    )
                    SwitchItem(
                        icon = Icons.Rounded.Shield,
                        title = stringResource(R.string.tunnet_risk),
                        summary = stringResource(R.string.tunnet_risk_summary),
                        checked = uiState.tnRiskBypass,
                        onCheckedChange = viewModel::setTnRiskBypass,
                    )
                    SwitchItem(
                        icon = Icons.Rounded.Notifications,
                        title = stringResource(R.string.tunnet_toast),
                        summary = stringResource(R.string.tunnet_toast_summary),
                        checked = uiState.tnToast,
                        onCheckedChange = viewModel::setTnToast,
                    )
                    SwitchItem(
                        icon = Icons.Rounded.ContentCopy,
                        title = stringResource(R.string.tunnet_dedupe),
                        summary = stringResource(R.string.tunnet_dedupe_summary),
                        checked = uiState.tnDedupe,
                        onCheckedChange = viewModel::setTnDedupe,
                    )
                })
            }

            item {
                Text(
                    text = stringResource(R.string.tunnet_more_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item {
                SegmentedColumn(content = listOf {
                    // 倍率 / 关闭延迟 / 补发时机：点击弹出单选对话框
                    DropdownListItem(
                        icon = Icons.Rounded.TrendingUp,
                        title = stringResource(R.string.tunnet_multiplier),
                        summary = stringResource(R.string.tunnet_multiplier_summary),
                        options = RewardMultiplier.OPTIONS.map { RewardMultiplier.label(it) },
                        selectedIndex = RewardMultiplier.indexOf(uiState.tnRewardMultiplier),
                        onSelected = viewModel::setTnRewardMultiplier,
                    )
                    DropdownListItem(
                        icon = Icons.Rounded.Schedule,
                        title = stringResource(R.string.tunnet_autoclose_delay),
                        summary = stringResource(R.string.tunnet_autoclose_delay_summary),
                        options = DelayOptions.AUTO_CLOSE.map { DelayOptions.formatSeconds(it) },
                        selectedIndex = DelayOptions.autoCloseIndex(uiState.tnAutoCloseDelayMs),
                        onSelected = viewModel::setTnAutoCloseDelay,
                    )
                    DropdownListItem(
                        icon = Icons.Rounded.Timelapse,
                        title = stringResource(R.string.tunnet_reward_delay),
                        summary = stringResource(R.string.tunnet_reward_delay_summary),
                        options = DelayOptions.REWARD.map { DelayOptions.labelMs(it) },
                        selectedIndex = DelayOptions.rewardIndex(uiState.tnRewardDelayMs),
                        onSelected = viewModel::setTnRewardDelay,
                    )
                })
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

/** 点击弹单选对话框的选择项（Material 没有 Miuix 那种 OverlayDropdown）。 */
@Composable
private fun DropdownListItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    SegmentedListItem(
        onClick = { showDialog = true },
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(imageVector = icon, contentDescription = null) },
        trailingContent = { Text(options.getOrElse(selectedIndex) { "" }) },
    )
    if (showDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDialog = false },
            confirmButton = {},
            title = { Text(title) },
            text = {
                Column {
                    options.forEachIndexed { index, label ->
                        SegmentedListItem(
                            onClick = {
                                onSelected(index)
                                showDialog = false
                            },
                            headlineContent = {
                                Text(
                                    text = label,
                                    color = if (index == selectedIndex) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            },
                        )
                    }
                }
            },
        )
    }
}
