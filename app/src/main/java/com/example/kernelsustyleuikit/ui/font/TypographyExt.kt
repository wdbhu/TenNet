package com.example.kernelsustyleuikit.ui.font

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle

/**
 * 把自定义字体套到整套 [Typography] 上。
 *
 * Material3 的 Typography 有 15 个样式（display/headline/title/body/label 各 3 档），
 * 没有现成的批量替换接口，只能逐项 copy。漏掉任何一档都会在对应字号上回落系统字体，
 * 看起来就是「部分文字没变」，所以这里全部列出来。
 */
fun Typography.withFontFamily(family: FontFamily): Typography = Typography(
    displayLarge = displayLarge.withFamily(family),
    displayMedium = displayMedium.withFamily(family),
    displaySmall = displaySmall.withFamily(family),
    headlineLarge = headlineLarge.withFamily(family),
    headlineMedium = headlineMedium.withFamily(family),
    headlineSmall = headlineSmall.withFamily(family),
    titleLarge = titleLarge.withFamily(family),
    titleMedium = titleMedium.withFamily(family),
    titleSmall = titleSmall.withFamily(family),
    bodyLarge = bodyLarge.withFamily(family),
    bodyMedium = bodyMedium.withFamily(family),
    bodySmall = bodySmall.withFamily(family),
    labelLarge = labelLarge.withFamily(family),
    labelMedium = labelMedium.withFamily(family),
    labelSmall = labelSmall.withFamily(family),
)

private fun TextStyle.withFamily(family: FontFamily): TextStyle = copy(fontFamily = family)
