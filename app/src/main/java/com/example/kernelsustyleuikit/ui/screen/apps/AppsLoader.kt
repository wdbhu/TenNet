package com.example.kernelsustyleuikit.ui.screen.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 应用列表加载。
 *
 * 关键优化：先返回不带图标的条目（毫秒级），图标由 UI 侧按需懒加载。
 * 之前一次性解码 400+ 个图标，单个 20~40ms，总耗时十几秒，这就是「刷新很慢」的原因。
 */
object AppsLoader {

    /** 图标缓存，全局共享，切换过滤条件时不用重解。 */
    private val iconCache = ConcurrentHashMap<String, Bitmap>()

    /**
     * 列表排序：已勾选的在前 → 新安装的在前 → 名称升序。
     *
     * 全部走 firstInstallTime 倒序，用户刚装的 App 自动顶到最上面，
     * 不用再在几百项里一个个搜。installTime=0（拿不到时间）的排到同组末尾。
     */
    val SORT: Comparator<AppEntry> = Comparator { a, b ->
        if (a.enabled != b.enabled) return@Comparator if (a.enabled) -1 else 1
        if (a.installTime != b.installTime) return@Comparator b.installTime.compareTo(a.installTime)
        a.label.lowercase().compareTo(b.label.lowercase())
    }

    /** 只枚举条目，不碰图标。 */
    suspend fun load(
        context: Context,
        includeSystem: Boolean,
        scope: Set<String>,
    ): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val self = context.packageName

        // 一次遍历同时拿 ApplicationInfo 和 PackageInfo，避免两次 IPC 全表扫描。
        // 需要 GET_PERMISSIONS 之外的信息时用 0 即可；versionName 在 PackageInfo 里，
        // 不需要额外 flags。之前这里还多算了一次 associateBy，结果从来没用上。
        val packages = runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList())

        packages.asSequence()
            .mapNotNull { pkgInfo ->
                val info: ApplicationInfo = pkgInfo.applicationInfo ?: return@mapNotNull null
                if (info.packageName == self) return@mapNotNull null
                // 系统应用默认不列出。例外：已被勾进作用域的系统应用必须留着，
                // 否则它从列表里消失后就没法取消勾选了。
                if (!includeSystem && isSystem(info) && info.packageName !in scope) {
                    return@mapNotNull null
                }
                AppEntry(
                    packageName = info.packageName,
                    label = runCatching { pm.getApplicationLabel(info).toString() }
                        .getOrDefault(info.packageName),
                    versionName = pkgInfo.versionName ?: "",
                    icon = iconCache[info.packageName],   // 有缓存就用，没有先留空
                    system = isSystem(info),
                    enabled = info.packageName in scope,
                    installTime = pkgInfo.firstInstallTime,
                )
            }
            .sortedWith(SORT)
            .toList()
    }

    /** 按需加载单个图标（在 IO 线程调用）。 */
    suspend fun loadIcon(context: Context, packageName: String): Bitmap? =
        withContext(Dispatchers.IO) {
            iconCache[packageName]?.let { return@withContext it }
            runCatching {
                val pm = context.packageManager
                val d: Drawable = pm.getApplicationIcon(packageName)
                val bmp = if (d is BitmapDrawable && d.bitmap != null) d.bitmap
                          else d.toBitmap(64, 64)
                iconCache[packageName] = bmp
                bmp
            }.getOrNull()
        }

    private fun isSystem(info: ApplicationInfo): Boolean =
        (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
}
