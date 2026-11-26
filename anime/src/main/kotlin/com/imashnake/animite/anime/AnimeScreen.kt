package com.imashnake.animite.anime

import android.content.res.Configuration
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.anigooli.library.WatchHistoryEntity
import com.imashnake.animite.api.anilist.sanitize.media.Media
import com.imashnake.animite.api.anilist.sanitize.media.Media.Season.Companion.sanitize
import com.imashnake.animite.api.anilist.sanitize.media.Media.Sort.Companion.sanitize
import com.imashnake.animite.api.anilist.sanitize.media.MediaList
import com.imashnake.animite.api.anilist.type.MediaType
import com.imashnake.animite.core.resource.Resource
import com.imashnake.animite.core.ui.LocalPaddings
import com.imashnake.animite.core.ui.component.CardSizeStepper
import com.imashnake.animite.core.ui.component.Chip
import com.imashnake.animite.core.ui.component.EmptyChip
import com.imashnake.animite.core.ui.component.LoadingMediaSmallRow
import com.imashnake.animite.core.ui.component.MediaCard
import com.imashnake.animite.core.ui.component.MediaCardSize
import com.imashnake.animite.core.ui.component.MediaSmallRow
import com.imashnake.animite.core.ui.ext.crossfadeModel
import com.imashnake.animite.core.ui.ext.horizontalOnly
import com.imashnake.animite.core.ui.layout.TranslucentStatusBarLayout
import com.imashnake.animite.media.MediaPage
import com.imashnake.animite.media.ext.icon
import com.imashnake.animite.media.ext.res
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
fun AnimeScreen(
    onNavigateToMediaItem: (MediaPage) -> Unit,
    onNavigateToExplore: (ExploreRoute) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    scrollState: ScrollState = rememberScrollState(),
    contentWindowInsets: WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
    viewModel: AnimeViewModel = hiltViewModel(),
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
    val watchHistory by viewModel.watchHistory.collectAsState()

    var isRefreshing by remember { mutableStateOf(false) }
    var showHistorySheet by remember { mutableStateOf(false) }
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
                            text = "Anime",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = if (watchHistory.isNotEmpty()) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable { showHistorySheet = true },
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.history),
                                        contentDescription = "Watch History",
                                        tint = if (watchHistory.isNotEmpty()) {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = if (watchHistory.isNotEmpty()) {
                                            "History (${watchHistory.size})"
                                        } else {
                                            "History"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (watchHistory.isNotEmpty()) {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                            }

                            CardSizeStepper(
                                currentSize = cardSize,
                                onSizeChange = viewModel::setCardSize,
                            )
                        }
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
                                    val animeRow = resource.data
                                    if (animeRow?.mediaList?.list?.isNotEmpty() == true) {
                                        AnimeRow(
                                            index = index,
                                            title = stringResource(animeRow.title),
                                            mediaList = animeRow.mediaList,
                                            cardSize = cardSize,
                                            onItemClicked = { media ->
                                                onNavigateToMediaItem(
                                                    MediaPage(
                                                        id = media.id,
                                                        source = index.toString(),
                                                        mediaType = MediaType.ANIME.rawValue,
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

    if (showHistorySheet) {
        WatchHistorySheet(
            history = watchHistory,
            onDismiss = { showHistorySheet = false },
            onOpenItem = { entry ->
                showHistorySheet = false
                onNavigateToMediaItem(
                    MediaPage(
                        id = entry.anilistId,
                        source = "History",
                        mediaType = MediaType.ANIME.rawValue,
                        providerId = entry.providerId,
                        malId = entry.malId,
                        fallbackTitle = entry.title,
                    )
                )
            },
            onRemoveEntry = viewModel::removeFromWatchHistory,
            onClearAll = viewModel::clearWatchHistory,
        )
    }
}

@Composable
private fun AnimeRow(
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
        contextChip = { onListClick ->
            val season = mediaList.filterStrategy.season?.sanitize()
            val year = mediaList.filterStrategy.year
            if (season != null && year != null) {
                Chip(
                    color = MaterialTheme.colorScheme.primary,
                    icon = ImageVector.vectorResource(season.icon),
                    text = listOf(stringResource(season.res), year).joinToString(separator = " "),
                    onClick = onListClick
                )
            } else {
                EmptyChip()
            }
        },
        onListClick = {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            val sort = mediaList.filterStrategy.sort.singleOrNull()?.sanitize()
            onNavigateToExplore(
                ExploreRoute(
                    mediaType = MediaType.ANIME.name,
                    sortName = sort?.first?.name,
                    isDescending = sort?.second,
                    season = mediaList.filterStrategy.season?.name,
                    year = mediaList.filterStrategy.year,
                    genre = mediaList.filterStrategy.genre
                )
            )
        },
        contentPadding = contentPadding,
        modifier = modifier.clip(RoundedCornerShape(LocalPaddings.current.large))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatchHistorySheet(
    history: List<WatchHistoryEntity>,
    onDismiss: () -> Unit,
    onOpenItem: (WatchHistoryEntity) -> Unit,
    onRemoveEntry: (Int) -> Unit,
    onClearAll: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Watch History",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (history.isNotEmpty()) {
                    TextButton(onClick = onClearAll) {
                        Text("Clear All")
                    }
                }
            }

            if (history.isEmpty()) {
                Text(
                    text = "No episodes watched yet. Start watching any anime and it will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(history, key = { it.anilistId }) { entry ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onOpenItem(entry) },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(10.dp),
                            ) {
                                AsyncImage(
                                    model = crossfadeModel(entry.coverUrl),
                                    contentDescription = entry.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .width(46.dp)
                                        .height(64.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        text = entry.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "Last watched: Ep ${entry.lastEpisodeWatched}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = DateUtils.getRelativeTimeSpanString(
                                            entry.lastWatchedAt,
                                            System.currentTimeMillis(),
                                            DateUtils.MINUTE_IN_MILLIS,
                                        ).toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { onRemoveEntry(entry.anilistId) }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Delete,
                                        contentDescription = "Remove from history",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
