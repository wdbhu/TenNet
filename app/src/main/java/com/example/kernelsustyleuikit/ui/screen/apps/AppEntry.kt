package com.example.kernelsustyleuikit.ui.screen.apps

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable

/**
 * 应用列表条目。
 *
 * 标 @Immutable 让 Compose 跳过深比较——列表几百项时这是最大的性能差异来源。
 *
 * installTime 取 PackageInfo.firstInstallTime（首次安装时间），用来做「新装的排最前」排序：
 * 用户装完新 App 打开模块就能在最上面看到，不用再一项一项搜。
 */
@Immutable
data class AppEntry(
    val packageName: String,
    val label: String,
    val versionName: String,
    val icon: Bitmap?,
    val system: Boolean,
    val enabled: Boolean,
    val installTime: Long = 0L,
)
