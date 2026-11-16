package com.example.kernelsustyleuikit.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import com.materialkolor.dynamiccolor.ColorSpec
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import com.example.kernelsustyleuikit.ui.font.withFontFamily

@Composable
fun MiuixTemplateTheme(
    appSettings: AppSettings,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDarkTheme = isSystemInDarkTheme()
    val darkTheme = appSettings.colorMode.isDark || (appSettings.colorMode.isSystem && systemDarkTheme)
    val colorStyle = appSettings.paletteStyle
    val colorSpec = appSettings.colorSpec

    val miuixPaletteStyle = try {
        ThemePaletteStyle.valueOf(colorStyle.name)
    } catch (_: Exception) {
        ThemePaletteStyle.TonalSpot
    }

    val miuixColorSpec = if (colorSpec == ColorSpec.SpecVersion.SPEC_2025) {
        ThemeColorSpec.Spec2025
    } else {
        ThemeColorSpec.Spec2021
    }

    // ThemeController 内部跑 MaterialKolor 全套色板算法，开销极大。
    // 不用 remember 包住的话，每次重组都会重算一遍，界面直接卡死。
    val schemeMode = when (appSettings.colorMode) {
        ColorMode.SYSTEM -> ColorSchemeMode.System
        ColorMode.LIGHT -> ColorSchemeMode.Light
        ColorMode.DARK -> ColorSchemeMode.Dark
        ColorMode.MONET_SYSTEM -> ColorSchemeMode.MonetSystem
        ColorMode.MONET_LIGHT -> ColorSchemeMode.MonetLight
        ColorMode.MONET_DARK, ColorMode.DARK_AMOLED -> ColorSchemeMode.MonetDark
    }
    val keyColor = if (appSettings.keyColor == 0) null else Color(appSettings.keyColor)

    val controller = remember(schemeMode, keyColor, darkTheme, miuixPaletteStyle, miuixColorSpec) {
        ThemeController(
            schemeMode,
            keyColor = keyColor,
            isDark = darkTheme,
            paletteStyle = miuixPaletteStyle,
            colorSpec = miuixColorSpec,
        )
    }

    // 自定义字体：套到整套 TextStyles 上。
    // MiuixTheme.textStyles 是 @Composable 属性，必须先取出来再进 remember，
    // 否则 remember 的 lambda 里不允许发起 composable 调用。
    val baseTextStyles = MiuixTheme.textStyles
    // revision 是 FontManager 的可观察计数器，换字体时自增。
    // 读一下就能让整个主题重组，做到「换完立刻生效」，不用重启。
    val fontRevision = com.example.kernelsustyleuikit.ui.font.FontManager.revision
    val customFont = remember(fontRevision) {
        com.example.kernelsustyleuikit.ui.font.FontManager.fontFamily(context)
    }
    val textStyles = remember(customFont, baseTextStyles) {
        if (customFont == null) baseTextStyles
        else baseTextStyles.withFontFamily(customFont)
    }

    MiuixTheme(
        controller = controller,
        textStyles = textStyles,
        content = {
            LaunchedEffect(darkTheme) {
                val window = (context as? Activity)?.window ?: return@LaunchedEffect
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            CompositionLocalProvider(
                LocalContentColor provides MiuixTheme.colorScheme.onBackground,
            ) {
                content()
            }
        }
    )
}
