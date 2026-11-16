package com.example.kernelsustyleuikit.ui.font

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import top.yukonga.miuix.kmp.theme.TextStyles

/**
 * 把自定义字体套到 Miuix 的整套 [TextStyles] 上。
 *
 * TextStyles 的每个字段都是 `var ... internal set`，跨模块不能直接赋值，
 * 所以用构造函数重建一份。14 个样式全部列出，漏一个就会在对应场景
 * 回落系统字体（表现为「标题变了正文没变」）。
 */
fun TextStyles.withFontFamily(family: FontFamily): TextStyles = TextStyles(
    main = main.withFamily(family),
    paragraph = paragraph.withFamily(family),
    body1 = body1.withFamily(family),
    body2 = body2.withFamily(family),
    button = button.withFamily(family),
    footnote1 = footnote1.withFamily(family),
    footnote2 = footnote2.withFamily(family),
    headline1 = headline1.withFamily(family),
    headline2 = headline2.withFamily(family),
    subtitle = subtitle.withFamily(family),
    title1 = title1.withFamily(family),
    title2 = title2.withFamily(family),
    title3 = title3.withFamily(family),
    title4 = title4.withFamily(family),
)

private fun TextStyle.withFamily(family: FontFamily): TextStyle = copy(fontFamily = family)
