package com.anigooli.downloads

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import java.util.Locale

private const val BYTES_IN_KB = 1024.0
private const val BYTES_IN_MB = BYTES_IN_KB * 1024.0
private const val BYTES_IN_GB = BYTES_IN_MB * 1024.0
private const val PERCENT_FLOAT_DIVISOR = 100f

/**
 * UI screen listing downloaded and active anime episodes, supporting playback (in-app or MPV),
 * pause/resume/retry, storage usage summary, and single-episode or full-anime deletion.
 */
@Composable
fun DownloadsManagerScreen(
    contentPadding: PaddingValues = PaddingValues(),
    modifier: Modifier = Modifier,
    viewModel: DownloadsManagerViewModel = hiltViewModel(),
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingDeleteAnime by remember { mutableStateOf<AnimeDownloadsGroup?>(null) }
    var pendingDeleteEpisode by remember { mutableStateOf<DownloadEpisodeEntity?>(null) }

    val totalBytes = remember(groups) { groups.sumOf { it.totalBytes } }

    Column(modifier = modifier.fillMaxSize()) {
        if (groups.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(24.dp),
            ) {
                Text(
                    text = stringResource(R.string.downloads_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    top = 8.dp,
                    bottom = contentPadding.calculateBottomPadding() + 24.dp,
                    start = 16.dp,
                    end = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "storage_header") {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = "Downloaded Storage Used",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = formatBytes(totalBytes),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                items(groups, key = { it.anime.anilistId }) { group ->
                    AnimeDownloadGroupCard(
                        group = group,
                        onDeleteAnime = { pendingDeleteAnime = group },
                        onPlayInApp = viewModel::playInApp,
                        onPlayInMpv = { ep ->
                            if (!viewModel.playInMpv(ep)) {
                                Toast.makeText(
                                    context,
                                    "MPV app is not installed on this device",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        onPause = viewModel::pause,
                        onResume = viewModel::resume,
                        onDeleteEpisode = { pendingDeleteEpisode = it },
                    )
                }
            }
        }
    }

    pendingDeleteAnime?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeleteAnime = null },
            title = { Text(stringResource(R.string.downloads_delete_anime)) },
            text = {
                Text(
                    "Remove all ${target.episodes.size} episodes of \"${target.anime.title}\" " +
                        "(${formatBytes(target.totalBytes)}) from device storage?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAnime(target.anime.anilistId)
                        pendingDeleteAnime = null
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteAnime = null }) { Text("Cancel") }
            },
        )
    }

    pendingDeleteEpisode?.let { ep ->
        AlertDialog(
            onDismissRequest = { pendingDeleteEpisode = null },
            title = { Text(stringResource(R.string.downloads_delete_episode)) },
            text = { Text("Delete ${ep.animeTitle} Episode ${ep.episodeNumber} from device?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteEpisode(ep.id)
                        pendingDeleteEpisode = null
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteEpisode = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun AnimeDownloadGroupCard(
    group: AnimeDownloadsGroup,
    onDeleteAnime: () -> Unit,
    onPlayInApp: (DownloadEpisodeEntity) -> Unit,
    onPlayInMpv: (DownloadEpisodeEntity) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onDeleteEpisode: (DownloadEpisodeEntity) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (!group.anime.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = group.anime.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 44.dp, height = 62.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.anime.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${group.episodes.size} ep(s) • ${formatBytes(group.totalBytes)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDeleteAnime) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.downloads_delete_anime),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            group.episodes.forEach { ep ->
                DownloadEpisodeRow(
                    episode = ep,
                    onPlayInApp = { onPlayInApp(ep) },
                    onPlayInMpv = { onPlayInMpv(ep) },
                    onPause = { onPause(ep.id) },
                    onResume = { onResume(ep.id) },
                    onDelete = { onDeleteEpisode(ep) },
                )
            }
        }
    }
}

@Composable
private fun DownloadEpisodeRow(
    episode: DownloadEpisodeEntity,
    onPlayInApp: () -> Unit,
    onPlayInMpv: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDelete: () -> Unit,
) {
    val status = runCatching { DownloadStatus.valueOf(episode.status) }.getOrDefault(DownloadStatus.QUEUED)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Episode ${episode.episodeNumber} (${episode.qualityLabel})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val subtitleInfo = when (status) {
                        DownloadStatus.COMPLETED -> formatBytes(episode.bytesDownloaded)
                        DownloadStatus.DOWNLOADING ->
                            "Downloading ${episode.progressPercent}% • ${formatBytes(episode.bytesDownloaded)}"
                        DownloadStatus.PAUSED -> "Paused (${episode.progressPercent}%)"
                        DownloadStatus.QUEUED -> "Queued..."
                        DownloadStatus.FAILED -> episode.errorMessage ?: "Download failed"
                    }
                    Text(
                        text = subtitleInfo,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (status == DownloadStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                when (status) {
                    DownloadStatus.COMPLETED -> {
                        OutlinedButton(
                            onClick = onPlayInMpv,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Text("MPV", style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = onPlayInApp) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = "Play")
                        }
                    }
                    DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> {
                        IconButton(onClick = onPause) {
                            Icon(Icons.Rounded.Close, contentDescription = "Pause")
                        }
                    }
                    DownloadStatus.PAUSED, DownloadStatus.FAILED -> {
                        IconButton(onClick = onResume) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Resume")
                        }
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.downloads_delete_episode),
                    )
                }
            }

            if (status == DownloadStatus.DOWNLOADING || status == DownloadStatus.PAUSED) {
                LinearProgressIndicator(
                    progress = { (episode.progressPercent / PERCENT_FLOAT_DIVISOR).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= BYTES_IN_GB -> String.format(Locale.getDefault(), "%.2f GB", bytes / BYTES_IN_GB)
    bytes >= BYTES_IN_MB -> String.format(Locale.getDefault(), "%.1f MB", bytes / BYTES_IN_MB)
    bytes >= BYTES_IN_KB -> String.format(Locale.getDefault(), "%.0f KB", bytes / BYTES_IN_KB)
    else -> "$bytes B"
}
