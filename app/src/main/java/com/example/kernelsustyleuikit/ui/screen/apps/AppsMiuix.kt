package com.example.kernelsustyleuikit.ui.screen.apps

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kernelsustyleuikit.R
import com.example.kernelsustyleuikit.ui.component.ScrollToTopOnChange
import com.example.kernelsustyleuikit.ui.component.SearchStatus
import com.example.kernelsustyleuikit.ui.component.miuix.SearchBarFake
import com.example.kernelsustyleuikit.ui.component.miuix.SearchPager
import com.example.kernelsustyleuikit.ui.component.miuix.WarningCard
import com.example.kernelsustyleuikit.ui.theme.LocalEnableBlur
import com.example.kernelsustyleuikit.ui.util.BlurredBar
import com.example.kernelsustyleuikit.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 应用页（Miuix）。
 *
 * 搜索按 miuix 官方 example（IconPage.kt）的组合方式：
 *  - 收起态搜索框放在 topBar 里（SearchBarFake），点击时置 EXPANDING；
 *  - SearchPager 挂在 Scaffold 的 popupHost 上，并传 offsetY
 *    （缺了它 topPadding 的 targetValue 恒为 0，过渡态永远推不完，
 *      表现就是取消按钮点不动）；
 *  - 结果态由独立的 LaunchedEffect 同步，不在 onSearchStatusChange 里覆盖。
 */
@Composable
fun AppsPagerMiuix(
    apps: List<AppEntry>,
    scopeReady: Boolean,
    bottomInnerPadding: Dp,
    onToggleApp: (AppEntry, Boolean) -> Unit,
    onOpenModuleSettings: () -> Unit,
    listState: LazyListState,
    scrollToTopSignal: Int,
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
) {
    val scrollBehavior = MiuixScrollBehavior()
    val pullState = rememberPullToRefreshState()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else colorScheme.surface
    val searchCapsuleColor = if (blurActive) {
        colorScheme.surfaceContainerHigh.copy(alpha = 0.8f)
    } else {
        null
    }

    // 记忆里的老坑：@Composable 调用（stringResource 等）不能放进 remember {} 块，
    // 所以先在组合里取值，再交给 remember。
    val searchHint = stringResource(R.string.apps_search_hint)
    var searchStatus by remember(searchHint) { mutableStateOf(SearchStatus(label = searchHint)) }
    val updateSearchStatus: (SearchStatus) -> Unit = { searchStatus = it }

    // 收起态搜索框的窗口 Y 坐标，展开动画从这里起步
    var searchOffsetY by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    // 官方 IconPage 的做法：lambda 把折叠比例挡在 composition 外面
    val dynamicTopPadding: () -> Dp = remember(scrollBehavior) {
        { 12.dp * (1f - scrollBehavior.state.collapsedFraction) }
    }

    // 搜索结果
    val filtered = remember(searchStatus.searchText, apps) {
        val q = searchStatus.searchText.trim()
        if (q.isEmpty()) emptyList()
        else apps.filter {
            it.label.contains(q, ignoreCase = true) ||
                it.packageName.contains(q, ignoreCase = true)
        }
    }
    val searchResultStatus = remember(searchStatus.searchText, filtered) {
        when {
            searchStatus.searchText.isBlank() -> SearchStatus.ResultStatus.DEFAULT
            filtered.isEmpty() -> SearchStatus.ResultStatus.EMPTY
            else -> SearchStatus.ResultStatus.SHOW
        }
    }
    // 状态里只同步结果态，不覆盖 current
    LaunchedEffect(searchResultStatus) {
        if (searchStatus.resultStatus != searchResultStatus) {
            searchStatus = searchStatus.copy(resultStatus = searchResultStatus)
        }
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop, blurActive) {
                searchStatus.TopAppBarAnim(backgroundColor = barColor) {
                    TopAppBar(
                        color = barColor,
                        title = stringResource(R.string.apps),
                        scrollBehavior = scrollBehavior,
                        // 照官方 example：收起态搜索框放在 TopAppBar 的 actions 区，
                        // 这样它和大标题同级、不会压住标题，也能量到准确的 Y 坐标。
                        actions = {
                            Box(
                                modifier = Modifier
                                    .alpha(if (searchStatus.isCollapsed()) 1f else 0f)
                                    .onGloballyPositioned { coords ->
                                        with(density) {
                                            searchOffsetY = coords.positionInWindow().y.toDp()
                                        }
                                    }
                                    .then(
                                        if (searchStatus.isCollapsed()) {
                                            Modifier.pointerInput(Unit) {
                                                detectTapGestures {
                                                    updateSearchStatus(
                                                        searchStatus.copy(
                                                            current = SearchStatus.Status.EXPANDING,
                                                            searchText = "",
                                                        )
                                                    )
                                                }
                                            }
                                        } else Modifier
                                    ),
                            ) {
                                SearchBarFake(
                                    label = searchStatus.label,
                                    searchBarTopPadding = dynamicTopPadding,
                                    capsuleColor = searchCapsuleColor,
                                )
                            }
                        },
                    )
                }
            }
        },
        popupHost = {
            searchStatus.SearchPager(
                onSearchStatusChange = updateSearchStatus,
                offsetY = searchOffsetY,
                defaultResult = {},
                searchBarTopPadding = dynamicTopPadding,
                collapsedCapsuleColor = searchCapsuleColor,
            ) {
                // 官方写法：result 是 LazyListScope 的尾随 lambda，直接塞 item
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.apps_search_empty),
                            fontSize = 14.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        )
                    }
                }
                items(filtered, key = { it.packageName }) { app ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 3.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(colorScheme.surfaceContainer),
                    ) {
                        AppRow(app, onToggle = onToggleApp)
                    }
                }
                item {
                    Spacer(Modifier.height(bottomInnerPadding))
                }
            }
        },
        contentWindowInsets = WindowInsets.systemBars
            .add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
        ) {
            ScrollToTopOnChange(
                listState = listState,
                scrollToTopSignal,
                observedList = { apps.size },
            )
            // 搜索展开时隐藏主列表，避免和搜索层叠在一起
            if (searchStatus.shouldCollapsed()) PullToRefresh(
                isRefreshing = refreshing,
                onRefresh = onRefresh,
                pullToRefreshState = pullState,
            ) {
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
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ModuleSettingsCard(onClick = onOpenModuleSettings)
                        if (!scopeReady) {
                            WarningCard("框架服务未连接，勾选暂不可用")
                        }
                    }
                }
                // 照官方 IconPage 的分组写法：整段列表一个圆角容器，
                // 而不是每行各套一张 Card。
                items(
                    count = apps.size,
                    key = { apps[it].packageName },
                ) { index ->
                    val app = apps[index]
                    val isFirst = index == 0
                    val isLast = index == apps.lastIndex
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(
                                RoundedCornerShape(
                                    topStart = if (isFirst) 16.dp else 0.dp,
                                    topEnd = if (isFirst) 16.dp else 0.dp,
                                    bottomEnd = if (isLast) 16.dp else 0.dp,
                                    bottomStart = if (isLast) 16.dp else 0.dp,
                                )
                            )
                            .background(colorScheme.surfaceContainer),
                    ) {
                        AppRow(app, onToggle = onToggleApp)
                    }
                }
                item { Spacer(Modifier.height(bottomInnerPadding)) }
            }
            }
        }
    }
}

@Composable
private fun ModuleSettingsCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        showIndication = true,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = null,
                modifier = Modifier.padding(end = 12.dp).size(24.dp),
                tint = colorScheme.onSurface,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings),
                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface,
                )
                Text(
                    text = "拦截、补发与奖励倍率的全局参数",
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun AppRow(app: AppEntry, onToggle: (AppEntry, Boolean) -> Unit) {
    val context = LocalContext.current
    var icon by remember(app.packageName) { mutableStateOf(app.icon) }
    LaunchedEffect(app.packageName) {
        if (icon == null) {
            icon = AppsLoader.loadIcon(context, app.packageName)
        }
    }
    val imageBitmap = remember(icon) { icon?.asImageBitmap() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = null,
                    modifier = Modifier.size(42.dp),
                )
            }
        }
        Spacer(Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (app.versionName.isEmpty()) app.packageName
                else "${app.packageName} · ${app.versionName}",
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Switch(
            checked = app.enabled,
            onCheckedChange = { checked -> onToggle(app, checked) },
        )
    }
}
