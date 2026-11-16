package com.example.kernelsustyleuikit.ui.screen.about

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.dropUnlessResumed
import com.example.kernelsustyleuikit.BuildConfig
import com.example.kernelsustyleuikit.R
import com.example.kernelsustyleuikit.ui.LocalUiMode
import com.example.kernelsustyleuikit.ui.UiMode
import com.example.kernelsustyleuikit.ui.navigation3.LocalNavigator

/**
 * 开发者 QQ 主页链接。
 *
 * 用 mqqapi 协议可以直达 QQ 资料卡；没装 QQ 时 UriHandler 会抛异常，
 * 由 onOpenLink 内部兜底（见下面的 safeOpen）。
 */
private const val DEVELOPER_QQ_URL = "mqqapi://card/show_pslcard?src_type=internal&uin=1550282791"
private const val CONTRIBUTOR_QQ_URL = "mqqapi://card/show_pslcard?src_type=internal&uin=3971889644"

@Composable
fun AboutScreen() {
    val navigator = LocalNavigator.current
    val uriHandler = LocalUriHandler.current
    val htmlString = stringResource(
        id = R.string.about_source_link,
        "<b><a href=\"https://qun.qq.com/universal-share/share?ac=1&authKey=5IxdZvUez8Z4gN4mjG5aOMEwKJ2T0P0Ikbowb51BzK4scH7XtpyKAxSMAZ%2BqZ358&busi_data=eyJncm91cENvZGUiOiIxODE3MzAxOTkiLCJ0b2tlbiI6InN5a2d0cWhWbmpaQ0ZCdWhkS3M0KzlCSnJiQUk2Q0xXZWM3SGxINUpUbWJ3MUMxS2xuckRIRDBXNVhMSHFjZ0QiLCJ1aW4iOiIxNTUwMjgyNzkxIn0%3D&data=7eqHhA8ztFU58mq5f6A2jOk2XLgfwAznnWJ0qJRneINdY6CI-cCCzRxON_0e8EYTiObzV7gT0Uw2q_PBBMI08g&svctype=4&tempid=h5_group_info\">QQ 交流群</a></b>"
    )
    val state = AboutUiState(
        title = stringResource(R.string.about),
        appName = stringResource(R.string.app_name),
        versionName = BuildConfig.VERSION_NAME,
        developer = PersonInfo(
            name = stringResource(R.string.about_developer_name),
            summary = stringResource(R.string.about_developer_summary),
            url = DEVELOPER_QQ_URL,
            avatarRes = R.drawable.ic_developer_avatar,
        ),
        contributors = listOf(
            PersonInfo(
                name = stringResource(R.string.about_contributor_name),
                summary = stringResource(R.string.about_contributor_summary),
                url = CONTRIBUTOR_QQ_URL,
                avatarRes = R.drawable.ic_contributor_avatar,
            ),
        ),
        links = extractLinks(htmlString),
    )
    val actions = AboutScreenActions(
        onBack = dropUnlessResumed { navigator.pop() },
        onOpenLink = { url ->
            // mqqapi 打不开（未装 QQ）时退回到网页版资料卡，避免点了没反应
            runCatching { uriHandler.openUri(url) }
                .onFailure {
                    runCatching { uriHandler.openUri("https://qzone.qq.com/") }
                }
        },
    )

    when (LocalUiMode.current) {
        UiMode.Miuix -> AboutScreenMiuix(state, actions)
        UiMode.Material -> AboutScreenMaterial(state, actions)
    }
}
