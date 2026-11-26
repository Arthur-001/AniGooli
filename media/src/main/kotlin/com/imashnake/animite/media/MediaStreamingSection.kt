package com.imashnake.animite.media

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anigooli.downloads.DownloadEpisodeEntity
import com.anigooli.downloads.DownloadStatus
import com.anigooli.streaming.AudioTrack
import com.imashnake.animite.api.anilist.FranchiseEntry
import com.imashnake.animite.api.anilist.OrganizedFranchise
import com.imashnake.animite.api.anilist.sanitize.media.Media
import com.imashnake.animite.core.ui.LocalPaddings
import com.imashnake.animite.core.ui.component.MediaCard
import com.imashnake.animite.core.ui.component.MediaSmallRow
import com.imashnake.animite.media.ext.res
import kotlinx.collections.immutable.toImmutableList

/** Quick action chip row for adding/removing an entry from the Offline Personal Anime List. */
@Composable
internal fun OfflineListActionRow(
    isInOfflineList: Boolean,
    onToggleOfflineList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(LocalPaddings.current.small),
        modifier = modifier,
    ) {
        AssistChip(
            onClick = onToggleOfflineList,
            leadingIcon = {
                Icon(
                    imageVector = if (isInOfflineList) Icons.Rounded.Check else Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            label = {
                Text(if (isInOfflineList) "In Offline List" else "Add to Offline List")
            },
        )
    }
}

/** Stream & Download controls section for Anime detail pages with per-anime Audio/Subtitle memory. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MediaStreamingSection(
    streamingState: StreamingEpisodesUiState,
    audioTrack: AudioTrack,
    subtitlesEnabled: Boolean,
    downloadsByEpisode: Map<String, DownloadEpisodeEntity>,
    onSelectAudio: (AudioTrack) -> Unit,
    onToggleSubtitles: (Boolean) -> Unit,
    onWatchEpisode: (String, Boolean) -> Unit,
    onDownloadEpisode: (String) -> Unit,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(LocalPaddings.current.small),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.stream_episodes),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleMedium,
        )

        // Per-anime Audio + Subtitle memory chips
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                selected = audioTrack == AudioTrack.JAPANESE,
                onClick = { onSelectAudio(AudioTrack.JAPANESE) },
                label = { Text("Japanese") },
            )
            FilterChip(
                selected = audioTrack == AudioTrack.ENGLISH_DUB,
                onClick = { onSelectAudio(AudioTrack.ENGLISH_DUB) },
                label = { Text("English Dub") },
            )
            FilterChip(
                selected = subtitlesEnabled,
                onClick = { onToggleSubtitles(!subtitlesEnabled) },
                label = { Text(if (subtitlesEnabled) "EN Sub ON" else "EN Sub OFF") },
            )
        }

        streamingState.statusMessage?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        streamingState.errorMessage?.let { err ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRetry) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Retry")
                }
            }
        }

        if (streamingState.isLoading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    text = "Fetching episodes from ani-cli...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (streamingState.episodes.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(streamingState.episodes, key = { it }) { ep ->
                    val dl = downloadsByEpisode[ep]
                    val dlStatus = dl?.let { runCatching { DownloadStatus.valueOf(it.status) }.getOrNull() }
                    val isBusy = streamingState.busyEpisode == ep

                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(10.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = "Ep $ep",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                if (isBusy) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                }
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable(enabled = !isBusy) { onWatchEpisode(ep, false) },
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    ) {
                                        Icon(
                                            Icons.Rounded.PlayArrow,
                                            contentDescription = "Play",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                        Spacer(Modifier.width(2.dp))
                                        Text(
                                            text = "Play",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
                                }

                                OutlinedButton(
                                    enabled = !isBusy,
                                    onClick = { onWatchEpisode(ep, true) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp),
                                ) {
                                    Text("MPV", style = MaterialTheme.typography.labelSmall)
                                }

                                when (dlStatus) {
                                    DownloadStatus.COMPLETED -> {
                                        Icon(
                                            painter = painterResource(R.drawable.download_done),
                                            contentDescription = "Downloaded",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                    DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> {
                                        TextButton(
                                            onClick = { dl?.id?.let(onPauseDownload) },
                                            contentPadding = PaddingValues(horizontal = 6.dp),
                                            modifier = Modifier.height(28.dp),
                                        ) {
                                            Text("${dl?.progressPercent ?: 0}%", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    DownloadStatus.PAUSED, DownloadStatus.FAILED -> {
                                        IconButton(
                                            onClick = { dl?.id?.let(onResumeDownload) },
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(Icons.Rounded.Refresh, contentDescription = "Resume", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    null -> {
                                        IconButton(
                                            enabled = !isBusy,
                                            onClick = { onDownloadEpisode(ep) },
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.download),
                                                contentDescription = "Download Episode $ep",
                                                modifier = Modifier.size(18.dp),
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
    }
}

/**
 * Organized franchise sections:
 * 1) Chronological Main Seasons & Movies
 * 2) Separate OVAs & Specials
 * 3) Separate Mangas with persisted Oldest ↔ Newest sort toggle
 */
@Composable
internal fun OrganizedFranchiseSections(
    franchise: OrganizedFranchise,
    mangaNewestFirst: Boolean,
    onToggleMangaSort: () -> Unit,
    onItemClicked: (Media.Small) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    if (franchise.mainSeasonsAndMovies.isNotEmpty()) {
        FranchiseRow(
            title = stringResource(R.string.main_seasons_movies),
            entries = franchise.mainSeasonsAndMovies,
            showOrderIndex = true,
            onItemClicked = onItemClicked,
            contentPadding = contentPadding,
        )
    }

    if (franchise.ovasAndSpecials.isNotEmpty()) {
        FranchiseRow(
            title = stringResource(R.string.ovas_specials),
            entries = franchise.ovasAndSpecials,
            showOrderIndex = false,
            onItemClicked = onItemClicked,
            contentPadding = contentPadding,
        )
    }

    if (franchise.mangas.isNotEmpty()) {
        MediaSmallRow(
            title = stringResource(R.string.mangas_section),
            mediaList = franchise.mangas.toImmutableList(),
            contentPadding = contentPadding,
            contextChip = { _ ->
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onToggleMangaSort),
                ) {
                    Text(
                        text = stringResource(
                            if (mangaNewestFirst) R.string.sort_newest_oldest else R.string.sort_oldest_newest
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            },
        ) { _, entry ->
            val fmt = entry.media.format?.let { stringResource(it.res) }
            val yearTag = entry.media.startYear?.toString()
            val tag = listOfNotNull(fmt, yearTag).joinToString(" • ").ifEmpty { null }
            MediaCard(
                image = entry.media.coverImage,
                tag = tag,
                label = entry.media.title,
                onClick = { if (!entry.isCurrent) onItemClicked(entry.media) },
            )
        }
    }
}

@Composable
private fun FranchiseRow(
    title: String,
    entries: List<FranchiseEntry>,
    showOrderIndex: Boolean,
    onItemClicked: (Media.Small) -> Unit,
    contentPadding: PaddingValues,
) {
    MediaSmallRow(
        title = title,
        mediaList = entries.toImmutableList(),
        contentPadding = contentPadding,
    ) { _, entry ->
        val fmt = entry.media.format?.let { stringResource(it.res) }
        val tag = buildList {
            if (showOrderIndex) add("#${entry.orderIndex}")
            if (entry.isCurrent) add("Current") else if (fmt != null) add(fmt)
        }.joinToString(" • ").ifEmpty { null }

        MediaCard(
            image = entry.media.coverImage,
            tag = tag,
            label = entry.media.title,
            onClick = { if (!entry.isCurrent) onItemClicked(entry.media) },
        )
    }
}
