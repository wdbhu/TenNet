package com.example.kernelsustyleuikit.ui.screen.font

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.kernelsustyleuikit.R
import com.example.kernelsustyleuikit.ui.font.FontManager
import com.example.kernelsustyleuikit.ui.theme.LocalEnableBlur
import com.example.kernelsustyleuikit.ui.util.BlurredBar
import com.example.kernelsustyleuikit.ui.util.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 自定义字体设置页。
 *
 * 用系统文件选择器挑 .ttf/.otf，拷进应用私有目录后由 [FontManager] 全局应用。
 * 选完立即生效（主题层用 remember(fontFamily) 重建 Typography/TextStyles）。
 */
@Composable
fun FontScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // 读 revision：换字体后本页的文件名显示也跟着刷新
    val fontRevision = FontManager.revision
    var currentName by remember(fontRevision) {
        mutableStateOf(FontManager.currentFontName(context))
    }
    var lastError by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = queryDisplayName(context, uri)
        val ok = FontManager.importFont(context, uri, name)
        if (ok) {
            currentName = FontManager.currentFontName(context)
            lastError = null
        } else {
            lastError = "导入失败：不是有效的字体文件"
        }
    }

    val scrollBehavior = MiuixScrollBehavior()
    val enableBlur = LocalEnableBlur.current
    val backdrop = rememberBlurBackdrop(enableBlur)
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop) {
                TopAppBar(
                    color = barColor,
                    title = stringResource(R.string.settings_font),
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
                    SmallTitle(text = stringResource(R.string.settings_font))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        ArrowPreference(
                            title = stringResource(R.string.settings_font_pick),
                            summary = currentName ?: stringResource(R.string.settings_font_none),
                            startAction = {
                                Icon(
                                    Icons.Rounded.FontDownload,
                                    contentDescription = null,
                                    tint = colorScheme.onBackground,
                                )
                            },
                            onClick = {
                                // 只显示字体类文件；有些文件管理器不上报 mime，所以也放开 */* 兜底
                                picker.launch(arrayOf(
                                    "font/ttf",
                                    "font/otf",
                                    "application/x-font-ttf",
                                    "application/x-font-opentype",
                                    "application/octet-stream",
                                ))
                            },
                        )
                        if (currentName != null) {
                            ArrowPreference(
                                title = stringResource(R.string.settings_font_reset),
                                summary = stringResource(R.string.settings_font_reset_summary),
                                startAction = {
                                    Icon(
                                        Icons.Rounded.RestartAlt,
                                        contentDescription = null,
                                        tint = colorScheme.onBackground,
                                    )
                                },
                                onClick = {
                                    FontManager.clearFont(context)
                                    currentName = null
                                    lastError = null
                                },
                            )
                        }
                    }

                    lastError?.let { err ->
                        Text(
                            text = err,
                            color = colorScheme.error,
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }

                    Text(
                        text = stringResource(R.string.settings_font_hint),
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

/** 从 SAF uri 查询用户看到的文件名。 */
private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
    }
}.getOrNull()
