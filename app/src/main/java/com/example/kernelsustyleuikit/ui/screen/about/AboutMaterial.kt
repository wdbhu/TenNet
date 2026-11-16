package com.example.kernelsustyleuikit.ui.screen.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.FixedScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kernelsustyleuikit.R
import com.example.kernelsustyleuikit.ui.component.material.ExpressiveScaffold
import com.example.kernelsustyleuikit.ui.component.material.SegmentedColumn
import com.example.kernelsustyleuikit.ui.component.material.SegmentedListItem
import com.example.kernelsustyleuikit.ui.component.material.TopBarBackButton
import com.example.kernelsustyleuikit.ui.component.material.expressiveTopAppBarColors

@Composable
fun AboutScreenMaterial(
    state: AboutUiState,
    actions: AboutScreenActions,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    ExpressiveScaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(state.title) },
                navigationIcon = {
                    TopBarBackButton(onClick = actions.onBack)
                },
                colors = expressiveTopAppBarColors(),
                scrollBehavior = scrollBehavior
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_about_logo),
                            contentDescription = null,
                            contentScale = FixedScale(1f)
                        )
                    }
                    Text(
                        modifier = Modifier.padding(top = 12.dp),
                        text = state.appName,
                        fontWeight = FontWeight.Medium,
                        fontSize = MaterialTheme.typography.headlineMedium.fontSize
                    )
                    Text(
                        text = state.versionName,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize
                    )
                }
            }
            item {
                SectionLabel(stringResource(R.string.about_developer_section))
                SegmentedColumn(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    content = listOf {
                        SegmentedListItem(
                            onClick = { actions.onOpenLink(state.developer.url) },
                            colors = ListItemDefaults.segmentedColors(
                                containerColor = MaterialTheme.colorScheme.surfaceBright,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceBright,
                                supportingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            leadingContent = { PersonAvatar(state.developer.avatarRes) },
                            headlineContent = { Text(state.developer.name) },
                            supportingContent = { Text(state.developer.summary) },
                        )
                    }
                )
            }
            if (state.contributors.isNotEmpty()) {
                item {
                    SectionLabel(stringResource(R.string.about_contributor_section))
                    SegmentedColumn(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        content = state.contributors.map { person ->
                            {
                                SegmentedListItem(
                                    onClick = { actions.onOpenLink(person.url) },
                                    colors = ListItemDefaults.segmentedColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceBright,
                                        disabledContainerColor = MaterialTheme.colorScheme.surfaceBright,
                                        supportingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                    leadingContent = { PersonAvatar(person.avatarRes) },
                                    headlineContent = { Text(person.name) },
                                    supportingContent = { Text(person.summary) },
                                )
                            }
                        }
                    )
                }
            }
            item {
                SectionLabel(stringResource(R.string.about_links_section))
                SegmentedColumn(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    content = state.links.map { linkInfo ->
                        {
                            SegmentedListItem(
                                onClick = { actions.onOpenLink(linkInfo.url) },
                                // Match upstream About without restyling other template screens.
                                colors = ListItemDefaults.segmentedColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceBright,
                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceBright,
                                    supportingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                headlineContent = { Text(linkInfo.fullText) }
                            )
                        }
                    }
                )
                Spacer(
                    Modifier.height(
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                                WindowInsets.captionBar.asPaddingValues().calculateBottomPadding()
                    )
                )
            }
        }
    }
}

/** 分组小标题。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        modifier = Modifier.padding(start = 28.dp, top = 12.dp, bottom = 4.dp),
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
    )
}

/** 圆形头像，没配资源时退回应用 logo。 */
@Composable
private fun PersonAvatar(resId: Int) {
    Image(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(50)),
        painter = painterResource(id = if (resId != 0) resId else R.drawable.ic_about_logo),
        contentDescription = null,
        contentScale = FixedScale(1f),
    )
}
