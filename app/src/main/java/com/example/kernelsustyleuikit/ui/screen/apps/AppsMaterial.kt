package com.example.kernelsustyleuikit.ui.screen.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.kernelsustyleuikit.R
import com.example.kernelsustyleuikit.ui.component.material.SearchAppBar
import com.example.kernelsustyleuikit.ui.component.material.SegmentedColumn
import com.example.kernelsustyleuikit.ui.component.material.SegmentedListItem
import com.example.kernelsustyleuikit.ui.component.material.SwitchItem

/**
 * 应用页的 Material 风格版本。
 *
 * 搜索用模板自带的 [SearchAppBar]（Material3 的
 * ExpandedFullScreenContainedSearchBar 封装）：自带展开动画、返回键接管、
 * 键盘弹出收起、大标题栏随滚动折叠。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsPagerMaterial(
    apps: List<AppEntry>,
    scopeReady: Boolean,
    bottomInnerPadding: Dp,
    onToggleApp: (AppEntry, Boolean) -> Unit,
    onOpenModuleSettings: () -> Unit,
    listState: LazyListState,
    scrollToTopSignal: Int,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }
    var query by remember { mutableStateOf("") }

    val filtered = remember(query, apps) {
        if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }

    SearchAppBar(
        title = { Text(stringResource(R.string.apps)) },
        searchText = query,
        onSearchTextChange = { query = it },
        onClearClick = { query = "" },
        snackbarHostState = snackbarHostState,
        scrollBehavior = scrollBehavior,
        defaultContent = { contentPadding, _ ->
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(
                    top = contentPadding,
                    bottom = bottomInnerPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ModuleSettingsCardMaterial(onClick = onOpenModuleSettings)
                        if (!scopeReady) {
                            Text(
                                text = "框架服务未连接，勾选暂不可用",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                items(apps, key = { it.packageName }) { app ->
                    AppRowMaterial(app) { checked -> onToggleApp(app, checked) }
                }
            }
        },
        searchContent = { contentPadding, _ ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = contentPadding,
                    bottom = bottomInnerPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (filtered.isEmpty() && query.isNotBlank()) {
                    item {
                        Text(
                            text = stringResource(R.string.apps_search_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                items(filtered, key = { it.packageName }) { app ->
                    AppRowMaterial(app) { checked -> onToggleApp(app, checked) }
                }
            }
        },
    )
}

@Composable
private fun ModuleSettingsCardMaterial(onClick: () -> Unit) {
    SegmentedColumn(content = listOf {
        SegmentedListItem(
            onClick = onClick,
            headlineContent = { Text(stringResource(R.string.settings)) },
            supportingContent = { Text("拦截、补发与奖励倍率的全局参数") },
            leadingContent = {
                Icon(imageVector = Icons.Rounded.Settings, contentDescription = null)
            },
        )
    })
}

@Composable
private fun AppRowMaterial(app: AppEntry, onCheckedChange: (Boolean) -> Unit) {
    SwitchItem(
        title = app.label,
        summary = if (app.versionName.isEmpty()) app.packageName
        else "${app.packageName} · ${app.versionName}",
        checked = app.enabled,
        onCheckedChange = onCheckedChange,
    )
}
