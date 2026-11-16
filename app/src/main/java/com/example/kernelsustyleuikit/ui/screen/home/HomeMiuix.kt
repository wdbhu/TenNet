package com.example.kernelsustyleuikit.ui.screen.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kernelsustyleuikit.R
import com.example.kernelsustyleuikit.permission.PermissionState
import com.example.kernelsustyleuikit.ui.component.ScrollToTopOnChange
import com.example.kernelsustyleuikit.ui.component.WarningLevel
import com.example.kernelsustyleuikit.ui.component.dialog.rememberConfirmDialog
import com.example.kernelsustyleuikit.ui.component.miuix.WarningCard
import com.example.kernelsustyleuikit.ui.theme.LocalEnableBlur
import com.example.kernelsustyleuikit.ui.util.BlurredBar
import com.example.kernelsustyleuikit.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun HomePagerMiuix(
    state: HomeUiState,
    permissionState: PermissionState,
    actions: HomeActions,
    bottomInnerPadding: Dp,
    scrollToTopSignal: Int = 0,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val listState = rememberLazyListState()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    Scaffold(
        topBar = {
            TopBar(
                scrollBehavior = scrollBehavior,
                backdrop = backdrop,
                barColor = barColor,
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            ScrollToTopOnChange(
                listState = listState,
                scrollToTopSignal,
                observedList = { state.systemInfo },
            )
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                item {
                    Column(
                        modifier = Modifier.padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Keep the theme settings preview in sync whenever this home layout changes.
                        if (state.checkUpdateEnabled) {
                            UpdateCard(state, actions)
                        }
                        StatusCardMiuix(state)
                        StatsRowMiuix(state)
                        InfoCard(systemInfo = state.systemInfo, buildTime = state.buildTime)
                        LogsCard(onClick = actions.onOpenLogs)
                    }
                    Spacer(Modifier.height(bottomInnerPadding))
                }
            }
        }
    }
}

@Composable
private fun UpdateCard(state: HomeUiState, actions: HomeActions) {
    val newVersion = state.latestVersionInfo
    val title = stringResource(R.string.update_changelog)
    val updateText = stringResource(R.string.update)
    val updateDialog = rememberConfirmDialog(onConfirm = { actions.onOpenUrl(newVersion.downloadUrl) })

    AnimatedVisibility(
        visible = state.hasUpdate,
        enter = fadeIn() + expandVertically(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        WarningCard(
            message = stringResource(R.string.new_version_available, newVersion.versionCode),
            level = WarningLevel.Notice,
            onClick = {
                if (newVersion.changelog.isEmpty()) {
                    actions.onOpenUrl(newVersion.downloadUrl)
                } else {
                    updateDialog.showConfirm(
                        title = title,
                        content = newVersion.changelog,
                        markdown = true,
                        confirm = updateText,
                    )
                }
            },
        )
    }
}

@Composable
private fun PermissionCardMiuix(
    state: PermissionState,
    appVersion: String,
    onClick: () -> Unit,
) {
    val requiredGranted = state.requiredGranted
    val iconColor = if (requiredGranted) Color(0xFF36D167) else Color(0xFFF72727)
    val containerColor = if (requiredGranted) Color(0xFFDFFAE4) else Color(0xFFF8E2E2)
    val textColor = Color(0xFF111111)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(color = containerColor),
            onClick = onClick,
            showIndication = true,
            pressFeedbackType = PressFeedbackType.Tilt,
        ) {
            Box {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(27.dp, 31.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Icon(
                        modifier = Modifier.size(110.dp),
                        imageVector =
                            if (requiredGranted) Icons.Rounded.CheckCircleOutline else Icons.Rounded.Cancel,
                        tint = iconColor,
                        contentDescription = null,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp, 14.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    Column {
                        Text(
                            text =
                                if (requiredGranted) {
                                    stringResource(R.string.permission_status_ready_title)
                                } else {
                                    stringResource(R.string.permission_status_missing_title)
                                },
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = textColor,
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = appVersion,
                            fontSize = 15.sp,
                            color = textColor,
                        )
                    }
                }
            }
        }
    }
}

/** 框架状态卡：连上 LSPosed 就绿色打勾，否则红色。 */
@Composable
private fun StatusCardMiuix(state: HomeUiState) {
    val active = state.xposed.active
    val iconColor = if (active) Color(0xFF36D167) else Color(0xFFF72727)
    val containerColor = if (active) Color(0xFFDFFAE4) else Color(0xFFF8E2E2)
    val textColor = Color(0xFF111111)

    // 标题 / 副标题 / 底部状态文字
    val title = if (active) "工作中" else "未连接"
    val subtitle = if (active) {
        "框架服务已连接 · ${state.xposed.frameworkName.ifEmpty { "LSPosed" }}"
    } else {
        "未检测到框架服务"
    }
    val footer = if (active) "框架已激活" else "请先在 LSPosed 启用模块"

    // 只要按压缩放/倾斜的手感，不接 onClick：点一下有反馈但不跳转
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = containerColor),
        pressFeedbackType = PressFeedbackType.Tilt,
        showIndication = true,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(118.dp)
        ) {
            // 右下角大图标
            Icon(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 8.dp, y = 18.dp)
                    .size(110.dp),
                imageVector = if (active) Icons.Rounded.CheckCircleOutline else Icons.Rounded.Cancel,
                tint = iconColor,
                contentDescription = null,
            )
            // 左侧文字
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = 14.dp, end = 16.dp)
            ) {
                Text(
                    text = title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor,
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    fontSize = 15.sp,
                    color = textColor,
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    text = footer,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = textColor,
                )
            }
        }
    }
}

/** 双栏统计卡：已适配 / 已启用。 */
@Composable
private fun StatsRowMiuix(state: HomeUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatCardMiuix("已支持的 SDK", state.adaptedCount.toString(), Modifier.weight(1f))
        StatCardMiuix("已启用", state.enabledCount.toString(), Modifier.weight(1f))
    }
}

@Composable
private fun StatCardMiuix(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp, 14.dp)) {
            Text(
                text = label,
                fontSize = 15.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = value,
                fontSize = 34.sp,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun TopBar(
    scrollBehavior: ScrollBehavior,
    backdrop: LayerBackdrop?,
    barColor: Color,
) {
    BlurredBar(backdrop) {
        TopAppBar(
            color = barColor,
            title = stringResource(R.string.app_name),
            scrollBehavior = scrollBehavior
        )
    }
}

@Composable
private fun LogsCard(onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ArrowPreference(
            title = "日志",
            summary = "查看各目标 App 的 hook 运行记录",
            startAction = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.MenuBook,
                    modifier = Modifier.padding(end = 6.dp).size(24.dp),
                    tint = colorScheme.onBackground,
                    contentDescription = null,
                )
            },
            onClick = onClick,
        )
    }
}

@Composable
private fun InfoCard(systemInfo: SystemInfo, buildTime: String) {
    @Composable
    fun InfoText(
        icon: ImageVector,
        title: String,
        content: String,
        bottomPadding: Dp = 24.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = bottomPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(end = 12.dp).size(24.dp),
                tint = colorScheme.onSurface,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface,
                )
                Text(
                    text = content,
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }

    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            InfoText(
                icon = Icons.Filled.Smartphone,
                title = "应用版本",
                content = systemInfo.appVersion,
            )
            InfoText(
                icon = Icons.Filled.Android,
                title = "Android 版本",
                content = systemInfo.androidVersion,
            )
            InfoText(
                icon = Icons.Filled.Android,
                title = "框架版本",
                content = systemInfo.frameworkVersion.ifEmpty { "未连接" },
            )
            InfoText(
                icon = Icons.Filled.Smartphone,
                title = "构建时间",
                content = buildTime.ifEmpty { "-" },
            )
            InfoText(
                icon = Icons.Filled.Smartphone,
                title = "设备型号",
                content = systemInfo.deviceModel,
                bottomPadding = 0.dp
            )
        }
    }
}
