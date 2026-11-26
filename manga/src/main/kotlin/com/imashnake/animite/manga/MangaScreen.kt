package com.imashnake.animite.manga

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.imashnake.animite.api.anilist.sanitize.media.Media
import com.imashnake.animite.api.anilist.sanitize.media.Media.Sort.Companion.sanitize
import com.imashnake.animite.api.anilist.sanitize.media.MediaList
import com.imashnake.animite.api.anilist.type.MediaType
import com.imashnake.animite.core.resource.Resource
import com.imashnake.animite.core.ui.LocalPaddings
import com.imashnake.animite.core.ui.component.CardSizeStepper
import com.imashnake.animite.core.ui.component.EmptyChip
import com.imashnake.animite.core.ui.component.LoadingMediaSmallRow
import com.imashnake.animite.core.ui.component.MediaCard
import com.imashnake.animite.core.ui.component.MediaCardSize
import com.imashnake.animite.core.ui.component.MediaSmallRow
import com.imashnake.animite.core.ui.ext.horizontalOnly
import com.imashnake.animite.core.ui.layout.TranslucentStatusBarLayout
import com.imashnake.animite.media.MediaPage
import com.imashnake.animite.navigation.ExploreRoute
import com.imashnake.animite.navigation.SharedContentKey
import com.imashnake.animite.navigation.SharedContentKey.Component.Card
import com.imashnake.animite.navigation.SharedContentKey.Component.Image
import com.imashnake.animite.navigation.SharedContentKey.Component.Page
import org.jetbrains.compose.resources.stringResource
import com.imashnake.animite.navigation.R as navigationR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod")
fun MangaScreen(
    onNavigateToMediaItem: (MediaPage) -> Unit,
    onNavigateToExplore: (ExploreRoute) -> Unit,
    scrollState: ScrollState,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    contentWindowInsets: WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
    viewModel: MangaViewModel = hiltViewModel(),
) {
    val insetPaddingValues = contentWindowInsets.asPaddingValues()
    val navigationComponentPaddingValues = when(LocalConfiguration.current.orientation) {
        Configuration.ORIENTATION_PORTRAIT -> PaddingValues(
            bottom = dimensionResource(navigationR.dimen.navigation_bar_height)
        )
        else -> PaddingValues(
            start = dimensionResource(navigationR.dimen.navigation_rail_width)
        )
    }
    val insetAndNavigationPaddingValues = insetPaddingValues + navigationComponentPaddingValues

    val lists by viewModel.lists.collectAsState()
    val cardSize by viewModel.cardSize.collectAsState()

    var isRefreshing by remember { mutableStateOf(false) }
    val pullToRefreshState = rememberPullToRefreshState()

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { viewModel.refresh { isRefreshing = it } },
        state = pullToRefreshState,
    ) {
        TranslucentStatusBarLayout(scrollState) {
            Box(Modifier.verticalScroll(scrollState)) {
                Column(
                    modifier = Modifier.padding(
                        top = insetPaddingValues.calculateTopPadding() + LocalPaddings.current.small,
                        bottom = LocalPaddings.current.large / 2 +
                            insetAndNavigationPaddingValues.calculateBottomPadding(),
                    ),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                PaddingValues(
                                    horizontal = LocalPaddings.current.large,
                                    vertical = LocalPaddings.current.small,
                                ) + insetAndNavigationPaddingValues.horizontalOnly,
                            ),
                    ) {
                        Text(
                            text = "Manga",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        CardSizeStepper(
                            currentSize = cardSize,
                            onSizeChange = viewModel::setCardSize,
                        )
                    }

                    lists.fastForEachIndexed { index, resource ->
                        AnimatedContent(
                            targetState = resource,
                            transitionSpec = {
                                fadeIn(tween(500, delayMillis = index * 100))
                                    .togetherWith(fadeOut(tween(500)))
                            },
                        ) {
                            when (it) {
                                is Resource.Success -> {
                                    val mangaRow = resource.data
                                    if (mangaRow?.mediaList?.list?.isNotEmpty() == true) {
                                        MangaRow(
                                            index = index,
                                            title = stringResource(mangaRow.title),
                                            mediaList = mangaRow.mediaList,
                                            cardSize = cardSize,
                                            onItemClicked = { media ->
                                                onNavigateToMediaItem(
                                                    MediaPage(
                                                        id = media.id,
                                                        source = index.toString(),
                                                        mediaType = MediaType.MANGA.rawValue,
                                                    )
                                                )
                                            },
                                            onNavigateToExplore = onNavigateToExplore,
                                            sharedTransitionScope = sharedTransitionScope,
                                            animatedVisibilityScope = animatedVisibilityScope,
                                            contentPadding = PaddingValues(
                                                horizontal = LocalPaddings.current.large,
                                                vertical = LocalPaddings.current.large / 2,
                                            ) + insetAndNavigationPaddingValues.horizontalOnly
                                        )
                                    }
                                }
                                is Resource.Loading -> {
                                    LoadingMediaSmallRow(
                                        count = 10,
                                        imageHeight = cardSize.gridImageHeight,
                                        cardWidth = cardSize.gridCardWidth,
                                        contentPadding = PaddingValues(
                                            horizontal = LocalPaddings.current.large,
                                            vertical = LocalPaddings.current.large / 2,
                                        ) + insetAndNavigationPaddingValues.horizontalOnly
                                    )
                                }
                                // TODO: Show error UI.
                                is Resource.Error -> {}
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MangaRow(
    index: Int,
    title: String,
    mediaList: MediaList,
    cardSize: MediaCardSize,
    onItemClicked: (Media.Small) -> Unit,
    onNavigateToExplore: (ExploreRoute) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues()
) {
    val haptic = LocalHapticFeedback.current
    MediaSmallRow(
        title = title,
        mediaList = mediaList.list,
        contextChip = { EmptyChip() },
        onListClick = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            val sort = mediaList.filterStrategy.sort.singleOrNull()?.sanitize()
            onNavigateToExplore(
                ExploreRoute(
                    mediaType = MediaType.MANGA.name,
                    sortName = sort?.first?.name,
                    isDescending = sort?.second,
                    season = mediaList.filterStrategy.season?.name,
                    year = mediaList.filterStrategy.year,
                    genre = mediaList.filterStrategy.genre
                )
            )
        },
        modifier = modifier,
        contentPadding = contentPadding,
    ) { _, media ->
        with(sharedTransitionScope) {
            MediaCard(
                image = media.coverImage,
                tag = null,
                label = media.title,
                onClick = { onItemClicked(media) },
                imageHeight = cardSize.gridImageHeight,
                cardWidth = cardSize.gridCardWidth,
                modifier = Modifier.sharedBounds(
                    sharedContentState = rememberSharedContentState(
                        SharedContentKey(
                            id = media.id,
                            source = index.toString(),
                            sharedComponents = Card to Page,
                        )
                    ),
                    animatedVisibilityScope = animatedVisibilityScope,
                    enter = fadeIn(tween(500)),
                    exit = fadeOut(tween(500)),
                    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                ),
                imageModifier = Modifier.sharedBounds(
                    rememberSharedContentState(
                        SharedContentKey(
                            id = media.id,
                            source = index.toString(),
                            sharedComponents = Image to Image,
                        )
                    ),
                    animatedVisibilityScope,
                ),
                textModifier = Modifier.skipToLookaheadSize(),
            )
        }
    }
}
