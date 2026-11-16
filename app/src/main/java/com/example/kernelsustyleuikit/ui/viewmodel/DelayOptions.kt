package com.example.kernelsustyleuikit.ui.viewmodel

/**
 * 模块设置里各下拉框的取值表。
 *
 * 界面显示的是下标对应的文字，落盘的是真实数值（毫秒）。
 * 之所以集中放在这里，是之前倍率那个坑的教训：
 * 直接把 selectedIndex 当数值写进配置，界面和实际行为会整体错位。
 */
object DelayOptions {

    /** 关闭延迟（毫秒）：广告页出现后多久自动关。 */
    val AUTO_CLOSE: List<Int> = listOf(1000, 2000, 5000, 10000)

    /** 补发时机（毫秒）：0 = 立即。 */
    val REWARD: List<Int> = listOf(0, 1500, 3000, 5000)

    fun autoCloseIndex(ms: Int): Int =
        AUTO_CLOSE.indexOf(ms).let { if (it >= 0) it else AUTO_CLOSE.indexOf(5000) }

    fun rewardIndex(ms: Int): Int =
        REWARD.indexOf(ms).let { if (it >= 0) it else 0 }

    fun labelMs(ms: Int): String = if (ms <= 0) "立即" else formatSeconds(ms)

    fun formatSeconds(ms: Int): String {
        val sec = ms / 1000.0
        return if (sec == sec.toInt().toDouble()) "${sec.toInt()} 秒" else "$sec 秒"
    }
}
