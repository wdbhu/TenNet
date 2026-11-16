package com.example.kernelsustyleuikit.ui.screen.apps

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.example.kernelsustyleuikit.ui.LocalUiMode
import com.example.kernelsustyleuikit.ui.UiMode
import com.example.kernelsustyleuikit.ui.util.XposedStatusProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun AppsPager(
    bottomInnerPadding: Dp,
    isCurrentPage: Boolean = true,
    onOpenModuleSettings: () -> Unit,
    scrollToTopSignal: Int = 0,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var apps by remember { mutableStateOf(listOf<AppEntry>()) }
    var scopeReady by remember { mutableStateOf(false) }
    // 下拉刷新状态：安装新 App 后下拉即可重新枚举
    var refreshing by remember { mutableStateOf(false) }

    // 系统应用一律纳入枚举，靠搜索框筛；这样系统应用也能直接搜到并勾选
    fun reload() {
        scope.launch(Dispatchers.IO) {
            XposedStatusProvider.ensureRegistered()
            ScopeController.init(context)
            val ready = ScopeController.ready()
            val sc = ScopeController.currentScope(context)
            // 列表只显示用户自己安装的 App，系统应用不列出来
            val list = AppsLoader.load(context, includeSystem = false, scope = sc)
            withContext(Dispatchers.Main) {
                scopeReady = ready
                apps = list
                refreshing = false
            }
        }
    }

    // 只在真正切到这一页时才枚举应用（pager 会预加载相邻页，无条件跑会卡死）。
    // 每次切回来都重新枚举：这样刚安装的 App 不用重启模块就能出现在列表里。
    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) {
            refreshing = apps.isNotEmpty()
            reload()
        }
    }

    /** 下拉刷新：重新枚举已安装应用（安装新 App 后用它）。 */
    val onRefreshStable: () -> Unit = remember {
        {
            refreshing = true
            reload()
        }
    }

    // 用 rememberUpdatedState 拿到最新的 apps 引用，同时保持 lambda 身份稳定
    val appsRef by rememberUpdatedState(apps)

    val onToggleAppStable = remember {
        { app: AppEntry, checked: Boolean ->
            val sorted = appsRef
                .map { if (it.packageName == app.packageName) it.copy(enabled = checked) else it }
                .sortedWith(AppsLoader.SORT)
            apps = sorted
            scope.launch(Dispatchers.IO) {
                // 乐观更新已在上方完成；等 LSPosed 真正确认后再决定是否回滚。
                // root 直写库是同步的，会立刻回调；没有 root 时走官方接口，要等授权弹窗。
                val settled = kotlinx.coroutines.CompletableDeferred<Boolean>()
                if (checked) {
                    ScopeController.add(context, app.packageName) { settled.complete(it) }
                } else {
                    ScopeController.remove(context, app.packageName) { settled.complete(it) }
                }
                val ok = runCatching {
                    withTimeoutOrNull(30_000L) { settled.await() } ?: false
                }.getOrDefault(false)
                withContext(Dispatchers.Main) {
                    if (ok) {
                        apps = apps.map {
                            if (it.packageName == app.packageName) it.copy(enabled = checked) else it
                        }.sortedWith(AppsLoader.SORT)
                    } else {
                        apps = apps
                            .map { if (it.packageName == app.packageName) it.copy(enabled = !checked) else it }
                            .sortedWith(AppsLoader.SORT)
                    }
                }
            }
            Unit
        }
    }

    when (LocalUiMode.current) {
        UiMode.Material -> AppsPagerMaterial(
            apps = apps,
            scopeReady = scopeReady,
            bottomInnerPadding = bottomInnerPadding,
            onToggleApp = onToggleAppStable,
            onOpenModuleSettings = onOpenModuleSettings,
            listState = listState,
            scrollToTopSignal = scrollToTopSignal,
        )

        UiMode.Miuix -> AppsPagerMiuix(
            apps = apps,
            scopeReady = scopeReady,
            bottomInnerPadding = bottomInnerPadding,
            onToggleApp = onToggleAppStable,
            onOpenModuleSettings = onOpenModuleSettings,
            listState = listState,
            scrollToTopSignal = scrollToTopSignal,
            refreshing = refreshing,
            onRefresh = onRefreshStable,
        )
    }
}
