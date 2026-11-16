package com.example.kernelsustyleuikit.ui.font

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.FileProvider
import java.io.File

/**
 * 自定义字体。
 *
 * 用户从系统文件选择器挑一个 .ttf/.otf，这里把它拷贝到应用私有目录
 * （原文件在 SAF 里只是临时授权，重启后就读不到了），
 * 再用 [Font] 包成 Compose 的 [FontFamily] 全局应用。
 */
object FontManager {

    private const val DIR = "custom_fonts"
    private const val ACTIVE = "active_font"

    /**
     * 字体变更信号。
     *
     * Compose 读文件系统是感知不到变化的：换字体后如果只改磁盘，
     * 主题层的 remember 不会失效，界面就还是旧字体，必须重启才生效。
     * 这里用一个可观察计数器，导入/清除时自增，主题读它即可触发重组。
     */
    private val _revision = androidx.compose.runtime.mutableIntStateOf(0)
    val revision: Int get() = _revision.intValue

    private fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }

    /** 目录里最新的那个字体文件（文件名带时间戳）。 */
    private fun activeFile(ctx: Context): File? =
        dir(ctx).listFiles { f -> f.isFile && f.name.startsWith(ACTIVE) }
            ?.maxByOrNull { it.lastModified() }

    /** 是否已设置自定义字体。 */
    fun hasCustomFont(ctx: Context): Boolean = activeFile(ctx)?.isFile == true

    /** 当前字体文件名（用于设置页展示）。 */
    fun currentFontName(ctx: Context): String? {
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        return prefs.getString("custom_font_name", null)
    }

    /**
     * 把用户选中的字体文件复制进来并设为当前字体。
     * @return 成功返回 true。
     */
    fun importFont(ctx: Context, uri: android.net.Uri, displayName: String?): Boolean {
        return runCatching {
            // 每次导入都写成新文件名（active_font_<时间戳>.ttf），旧文件删掉。
            // Compose 按文件路径缓存字体，同名覆盖会继续用旧字形，必须换名。
            dir(ctx).listFiles()?.forEach { runCatching { it.delete() } }
            val target = File(dir(ctx), ACTIVE + "_" + System.currentTimeMillis() + ".ttf")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return false

            // 校验是不是可解析的字体，坏文件直接回滚，免得起不来
            Typeface.createFromFile(target)
            ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putString("custom_font_name", displayName ?: target.name)
                .putString("custom_font_path", target.absolutePath)
                .apply()
            _revision.intValue++
            true
        }.getOrElse { false }
    }

    /** 恢复系统默认字体。 */
    fun clearFont(ctx: Context) {
        runCatching { dir(ctx).listFiles()?.forEach { it.delete() } }
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .remove("custom_font_name")
            .remove("custom_font_path")
            .apply()
        _revision.intValue++
    }

    /**
     * 构建 Compose 用的 [FontFamily]；没设置或加载失败时返回 null，
     * 调用方回落到系统默认字体。
     */
    fun fontFamily(ctx: Context): FontFamily? {
        val f = activeFile(ctx)?.takeIf { it.isFile } ?: return null
        return runCatching {
            // 把常见字重都映射到同一个文件，避免粗体回落到系统字体
            FontFamily(
                Font(f, FontWeight.Normal),
                Font(f, FontWeight.Medium),
                Font(f, FontWeight.SemiBold),
                Font(f, FontWeight.Bold),
            )
        }.getOrNull()
    }

    /** 给系统控件（如 Toast）用的 Typeface。 */
    fun typeface(ctx: Context): Typeface? {
        val f = activeFile(ctx)?.takeIf { it.isFile } ?: return null
        return runCatching { Typeface.createFromFile(f) }.getOrNull()
    }
}
