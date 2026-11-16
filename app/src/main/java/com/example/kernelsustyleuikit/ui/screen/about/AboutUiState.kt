package com.example.kernelsustyleuikit.ui.screen.about

import androidx.compose.runtime.Immutable

@Immutable
data class AboutUiState(
    val title: String,
    val appName: String,
    val versionName: String,
    val developer: PersonInfo,
    val contributors: List<PersonInfo>,
    val links: List<LinkInfo>,
)

/**
 * 人员卡片：头像 + 名字 + 一行细体说明 + 点击跳转。
 *
 * @param avatarRes 头像资源 id，0 表示用默认头像
 */
@Immutable
data class PersonInfo(
    val name: String,
    val summary: String,
    val url: String,
    val avatarRes: Int = 0,
)

@Immutable
data class AboutScreenActions(
    val onBack: () -> Unit,
    val onOpenLink: (String) -> Unit,
)
