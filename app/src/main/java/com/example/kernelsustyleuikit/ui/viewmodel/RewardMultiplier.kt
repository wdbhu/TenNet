package com.example.kernelsustyleuikit.ui.viewmodel

/**
 * 奖励倍率的唯一真相表。
 *
 * 界面下拉框显示的是「倍率文字」，存储进配置的必须是「倍率数值」。
 * 早先直接把下拉框的 selectedIndex（0..4）当成倍率写进配置，
 * 于是：
 *   - 选 1x → 存 0 → hook 侧 rewardPassthrough() 判 true（等于不修改）
 *   - 选 2x → 存 1 → 实际只发 1 次，等于没加倍
 *   - 选 3x → 存 2 → 实际发 2 次
 * 全线差 1。现在统一走这张表做双向换算。
 */
object RewardMultiplier {

    /** 下拉框的可选倍率，顺序即界面顺序。 */
    val OPTIONS: List<Int> = listOf(1, 2, 3, 5, 10)

    /** 倍率 → 下拉框下标；不在表里的倍率回退到 1x。 */
    fun indexOf(multiplier: Int): Int {
        val i = OPTIONS.indexOf(multiplier)
        return if (i >= 0) i else 0
    }

    /** 下拉框下标 → 倍率；越界按 1x 处理。 */
    fun multiplierAt(index: Int): Int =
        OPTIONS.getOrElse(index) { 1 }

    /** 界面文案。 */
    /** 界面文案；1x 就是正常单次，不再是"不修改"的特殊值。 */
    fun label(multiplier: Int): String = "${if (multiplier < 1) 1 else multiplier}x"
}
