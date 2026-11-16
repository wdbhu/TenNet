package com.example.kernelsustyleuikit.ui.navigation3

import android.os.Parcelable
import top.yukonga.miuix.kmp.nav.core.NavKey
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

/**
 * Type-safe navigation keys for miuix-nav.
 * Each destination is a NavKey (data object/data class) and can be saved/restored in the back stack.
 */
@Serializable
sealed interface Route : NavKey, Parcelable {
    @Parcelize
    @Serializable
    data object Main : Route

    @Parcelize
    @Serializable
    data object Home : Route

    @Parcelize
    @Serializable
    data object Settings : Route

    @Parcelize
    @Serializable
    data object About : Route

    @Parcelize
    @Serializable
    data object ColorPalette : Route

    @Parcelize
    @Serializable
    data object Permissions : Route

    /** TunNet 模块设置（从「应用」页的模块设置卡片进入）。 */
    @Parcelize
    @Serializable
    data object ModuleSettings : Route

    /** 日志查看。 */
    @Parcelize
    @Serializable
    data object Logs : Route

    /** 自定义字体。 */
    @Parcelize
    @Serializable
    data object Font : Route

    /** 赞赏。 */
    @Parcelize
    @Serializable
    data object Donate : Route
}
