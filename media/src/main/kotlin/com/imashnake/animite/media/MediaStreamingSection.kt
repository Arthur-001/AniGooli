package com.imashnake.animite.media

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.anigooli.downloads.DownloadEpisodeEntity
import com.anigooli.downloads.DownloadStatus
import com.anigooli.streaming.AudioTrack
import com.anigooli.streaming.CensorshipVersion
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

/** Stream & Download controls section with compact numbers-only episode grid and action bottom sheet. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod", "CognitiveComplexMethod")
internal fun MediaStreamingSection(
    streamingState: StreamingEpisodesUiState,
    audioTrack: AudioTrack,
    showEpisodeNames: Boolean,
    censorshipVersion: CensorshipVersion,
    lastWatchedEpisode: String?,
    watchedEpisodes: Set<String>,
    downloadsByEpisode: Map<String, DownloadEpisodeEntity>,
    onSelectAudio: (AudioTrack) -> Unit,
    onToggleShowEpisodeNames: () -> Unit,
    onSelectCensorshipVersion: (CensorshipVersion) -> Unit,
    onLoadEpisodeNamesForNumber: (Int) -> Unit,
    onWatchEpisode: (String, Boolean) -> Unit,
    onToggleEpisodeWatched: (String) -> Unit,
    onDownloadEpisode: (String) -> Unit,
    onPauseDownload: (String) -> Unit,
    onResumeDownload: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedEpisodeForSheet by remember { mutableStateOf<String?>(null) }
    val activeEpisodes = remember(streamingState, audioTrack) {
        streamingState.episodesFor(audioTrack)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(LocalPaddings.current.small),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.stream_episodes),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleMedium,
        )

        // Per-anime Audio, Version & Episode Names memory chips
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                selected = audioTrack == AudioTrack.JAPANESE,
                onClick = { onSelectAudio(AudioTrack.JAPANESE) },
                label = {
                    val label = if (streamingState.subEpisodes.isNotEmpty()) {
                        "Japanese (${streamingState.subEpisodes.size})"
                    } else {
                        "Japanese"
                    }
                    Text(label)
                },
            )
            FilterChip(
                selected = audioTrack == AudioTrack.ENGLISH_DUB,
                onClick = { onSelectAudio(AudioTrack.ENGLISH_DUB) },
                label = {
                    val label = if (streamingState.dubChecked && streamingState.subEpisodes.isNotEmpty()) {
                        "English Dub (${streamingState.dubEpisodes.size})"
                    } else {
                        "English Dub"
                    }
                    Text(label)
                },
            )
            if (streamingState.availableVersions?.hasMultipleVersions == true) {
                FilterChip(
                    selected = censorshipVersion == CensorshipVersion.UNCENSORED,
                    onClick = { onSelectCensorshipVersion(CensorshipVersion.UNCENSORED) },
                    label = { Text("Uncensored") },
                )
                FilterChip(
                    selected = censorshipVersion == CensorshipVersion.CENSORED,
                    onClick = { onSelectCensorshipVersion(CensorshipVersion.CENSORED) },
                    label = { Text("Censored") },
                )
            }
            FilterChip(
                selected = showEpisodeNames,
                onClick = onToggleShowEpisodeNames,
                label = { Text(if (showEpisodeNames) "Names ON" else "Numbers Only") },
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
        } else if (
            audioTrack == AudioTrack.ENGLISH_DUB &&
            streamingState.dubEpisodes.isEmpty() &&
            streamingState.subEpisodes.isNotEmpty()
        ) {
            Text(
                text = "No English dubbed episodes available for this anime.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (activeEpisodes.isNotEmpty()) {
            val episodes = activeEpisodes
            val chunkSize = if (episodes.size > 500) 100 else 50
            val chunks = remember(episodes, chunkSize) { episodes.chunked(chunkSize) }
            val defaultChunkIdx = remember(chunks, lastWatchedEpisode) {
                val idx = lastWatchedEpisode?.let { last -> chunks.indexOfFirst { last in it } } ?: 0
                if (idx >= 0) idx else 0
            }
            var selectedChunkIndex by remember(episodes) { mutableIntStateOf(defaultChunkIdx) }
            val safeChunkIndex = selectedChunkIndex.coerceIn(0, (chunks.size - 1).coerceAtLeast(0))
            val visibleEpisodes = chunks.getOrElse(safeChunkIndex) { episodes }

            LaunchedEffect(showEpisodeNames, safeChunkIndex, episodes) {
                if (showEpisodeNames) {
                    val firstNum = visibleEpisodes.firstOrNull()?.toDoubleOrNull()?.toInt() ?: 1
                    onLoadEpisodeNamesForNumber(firstNum)
                }
            }

            if (chunks.size > 1) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(chunks) { idx, chunk ->
                        FilterChip(
                            selected = idx == safeChunkIndex,
                            onClick = { selectedChunkIndex = idx },
                            label = { Text("${chunk.first()}–${chunk.last()}") },
                        )
                    }
                }
            }

            if (showEpisodeNames) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    visibleEpisodes.forEach { ep ->
                        val dl = downloadsByEpisode[ep]
                        val dlStatus = dl?.let { runCatching { DownloadStatus.valueOf(it.status) }.getOrNull() }
                        val isBusy = streamingState.busyEpisode == ep
                        val isLastWatched = ep == lastWatchedEpisode
                        val isWatched = ep in watchedEpisodes
                        val epName = streamingState.episodeTitles[ep]

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = when {
                                isLastWatched -> MaterialTheme.colorScheme.primaryContainer
                                isWatched -> MaterialTheme.colorScheme.secondaryContainer
                                else -> MaterialTheme.colorScheme.surfaceContainer
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { selectedEpisodeForSheet = ep },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    text = ep,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        isLastWatched -> MaterialTheme.colorScheme.onPrimaryContainer
                                        isWatched -> MaterialTheme.colorScheme.onSecondaryContainer
                                        else -> MaterialTheme.colorScheme.primary
                                    },
                                )
                                Text(
                                    text = epName ?: "Episode $ep",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (isWatched) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "Watched",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                EpisodeStatusIndicator(isBusy = isBusy, dlStatus = dlStatus, dl = dl)
                            }
                        }
                    }
                }
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    visibleEpisodes.forEach { ep ->
                        val dl = downloadsByEpisode[ep]
                        val dlStatus = dl?.let { runCatching { DownloadStatus.valueOf(it.status) }.getOrNull() }
                        val isBusy = streamingState.busyEpisode == ep
                        val isLastWatched = ep == lastWatchedEpisode
                        val isWatched = ep in watchedEpisodes
                        val containerColor = when {
                            isLastWatched -> MaterialTheme.colorScheme.primaryContainer
                            isWatched -> MaterialTheme.colorScheme.secondaryContainer
                            dlStatus == DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = containerColor,
                            modifier = Modifier
                                .defaultMinSize(minWidth = 48.dp, minHeight = 36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { selectedEpisodeForSheet = ep },
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = ep,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = when {
                                            isLastWatched -> MaterialTheme.colorScheme.onPrimaryContainer
                                            isWatched -> MaterialTheme.colorScheme.onSecondaryContainer
                                            else -> MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                    if (isWatched) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = "Watched",
                                            tint = if (isLastWatched) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.primary
                                            },
                                            modifier = Modifier.size(12.dp),
                                        )
                                    }
                                    if (isBusy) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(12.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else if (dlStatus == DownloadStatus.COMPLETED) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary),
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

    selectedEpisodeForSheet?.let { ep ->
        val dl = downloadsByEpisode[ep]
        val dlStatus = dl?.let { runCatching { DownloadStatus.valueOf(it.status) }.getOrNull() }
        val isBusy = streamingState.busyEpisode == ep
        val epTitle = streamingState.episodeTitles[ep]

        ModalBottomSheet(onDismissRequest = { selectedEpisodeForSheet = null }) {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Episode $ep",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (!epTitle.isNullOrBlank()) {
                        Text(
                            text = epTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(
                        enabled = !isBusy,
                        onClick = {
                            selectedEpisodeForSheet = null
                            onWatchEpisode(ep, false)
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = "Play",
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Play")
                    }

                    OutlinedButton(
                        enabled = !isBusy,
                        onClick = {
                            selectedEpisodeForSheet = null
                            onWatchEpisode(ep, true)
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("MPV")
                    }

                    when (dlStatus) {
                        DownloadStatus.COMPLETED -> {
                            OutlinedButton(
                                enabled = false,
                                onClick = {},
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.download_done),
                                    contentDescription = "Downloaded",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> {
                            OutlinedButton(
                                onClick = { dl?.id?.let(onPauseDownload) },
                            ) {
                                Text("${dl?.progressPercent ?: 0}%")
                            }
                        }
                        DownloadStatus.PAUSED, DownloadStatus.FAILED -> {
                            OutlinedButton(
                                onClick = { dl?.id?.let(onResumeDownload) },
                            ) {
                                Icon(
                                    Icons.Rounded.Refresh,
                                    contentDescription = "Resume Download",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        null -> {
                            OutlinedButton(
                                enabled = !isBusy,
                                onClick = { onDownloadEpisode(ep) },
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

                val isEpWatched = ep in watchedEpisodes
                OutlinedButton(
                    onClick = { onToggleEpisodeWatched(ep) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (isEpWatched) "Watched (Tap to Unmark)" else "Mark as Watched")
                }
            }
        }
    }
}

@Composable
private fun EpisodeStatusIndicator(
    isBusy: Boolean,
    dlStatus: DownloadStatus?,
    dl: DownloadEpisodeEntity?,
) {
    when {
        isBusy -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        dlStatus == DownloadStatus.COMPLETED -> Icon(
            painter = painterResource(R.drawable.download_done),
            contentDescription = "Downloaded",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        dlStatus == DownloadStatus.DOWNLOADING || dlStatus == DownloadStatus.QUEUED -> Text(
            text = "${dl?.progressPercent ?: 0}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
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
