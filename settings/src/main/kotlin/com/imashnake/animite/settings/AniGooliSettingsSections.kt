package com.imashnake.animite.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.anigooli.downloads.PreBufferPolicy
import com.anigooli.streaming.QualitySelector
import com.imashnake.animite.api.preferences.AUDIO_TRACK_ENGLISH_DUB
import com.imashnake.animite.api.preferences.AUDIO_TRACK_JAPANESE
import com.imashnake.animite.api.preferences.CARD_SIZE_COMPACT
import com.imashnake.animite.api.preferences.CENSORSHIP_CENSORED
import com.imashnake.animite.api.preferences.CENSORSHIP_UNCENSORED
import com.imashnake.animite.api.preferences.DEFAULT_PRE_BUFFER_MB
import com.imashnake.animite.api.preferences.DEFAULT_STREAM_QUALITY
import com.imashnake.animite.api.preferences.PLAYER_IN_APP
import com.imashnake.animite.api.preferences.PLAYER_MPV
import com.imashnake.animite.core.ui.LocalPaddings
import com.imashnake.animite.core.ui.component.MediaCardSize
import java.util.Locale

private const val BYTES_PER_MB = 1024.0 * 1024.0

/** Renders AniGooli's Streaming, Pre-Buffering, Release Notifications, and Social shortcut sections inside Settings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("LongMethod")
internal fun AniGooliSettingsSections(
    viewModel: SettingsViewModel,
    isDarkMode: Boolean,
    onNavigateToSocial: () -> Unit,
) {
    val context = LocalContext.current
    val cardColor = if (isDarkMode) {
        MaterialTheme.colorScheme.surfaceContainer
    } else {
        MaterialTheme.colorScheme.surface
    }

    val streamQuality by viewModel.defaultStreamQuality.collectAsState(initial = DEFAULT_STREAM_QUALITY)
    val defaultAudio by viewModel.defaultAudioTrack.collectAsState(initial = AUDIO_TRACK_JAPANESE)
    val defaultSubs by viewModel.defaultSubtitlesEnabled.collectAsState(initial = true)
    val autoAddWatchedToMyList by viewModel.autoAddWatchedToMyList.collectAsState(initial = true)
    val preferredPlayer by viewModel.preferredPlayer.collectAsState(initial = PLAYER_IN_APP)
    val preBufferMaxMb by viewModel.preBufferMaxMb.collectAsState(initial = DEFAULT_PRE_BUFFER_MB)
    val preBufferCacheBytes by viewModel.preBufferCacheBytes.collectAsState(initial = 0L)
    val autoUpdateEnabled by viewModel.extractorAutoUpdateEnabled.collectAsState(initial = true)
    val extractorStatus by viewModel.extractorStatus.collectAsState()
    val isUpdatingExtractor by viewModel.isUpdatingExtractor.collectAsState()

    val notificationsEnabled by viewModel.notificationsEnabled.collectAsState(initial = true)
    val notifyEpisodes by viewModel.notifyEpisodes.collectAsState(initial = true)
    val notifySeasonsMovies by viewModel.notifySeasonsMovies.collectAsState(initial = true)
    val notifyOvas by viewModel.notifyOvas.collectAsState(initial = true)
    val notifyMangas by viewModel.notifyMangas.collectAsState(initial = true)
    val mediaCardSize by viewModel.mediaCardSize.collectAsState(initial = CARD_SIZE_COMPACT)
    val defaultCensorship by viewModel.defaultCensorshipVersion.collectAsState(initial = CENSORSHIP_UNCENSORED)

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.markNotificationPermissionRequested()
        viewModel.setNotificationsEnabled(granted)
    }

    // Streaming & Playback
    Column(verticalArrangement = Arrangement.spacedBy(LocalPaddings.current.medium)) {
        Text(
            text = stringResource(R.string.streaming_playback),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleLarge,
        )

        Surface(
            shape = RoundedCornerShape(LocalPaddings.current.large),
            color = cardColor,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(16.dp),
            ) {
                Text(
                    text = "Default Video Quality (falls back to highest available)",
                    style = MaterialTheme.typography.titleSmall,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    QualitySelector.standardQualities.forEach { quality ->
                        FilterChip(
                            selected = streamQuality.equals(quality, ignoreCase = true),
                            onClick = { viewModel.setDefaultStreamQuality(quality) },
                            label = {
                                Text(if (quality == "best") "Best" else quality)
                            },
                        )
                    }
                }

                Text(
                    text = "Default Audio & Subtitles (for new anime)",
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = defaultAudio == AUDIO_TRACK_JAPANESE,
                        onClick = { viewModel.setDefaultAudioTrack(AUDIO_TRACK_JAPANESE) },
                        label = { Text("Japanese") },
                    )
                    FilterChip(
                        selected = defaultAudio == AUDIO_TRACK_ENGLISH_DUB,
                        onClick = { viewModel.setDefaultAudioTrack(AUDIO_TRACK_ENGLISH_DUB) },
                        label = { Text("English Dub") },
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("English Subtitles ON by default", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = defaultSubs, onCheckedChange = viewModel::setDefaultSubtitlesEnabled)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Auto-add watched anime to My List", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Adds anime to My List after watching 65% of an episode",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoAddWatchedToMyList,
                        onCheckedChange = viewModel::setAutoAddWatchedToMyList,
                    )
                }

                Text(
                    text = "Preferred Anime Version",
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = defaultCensorship == CENSORSHIP_UNCENSORED,
                        onClick = { viewModel.setDefaultCensorshipVersion(CENSORSHIP_UNCENSORED) },
                        label = { Text("Uncensored") },
                    )
                    FilterChip(
                        selected = defaultCensorship == CENSORSHIP_CENSORED,
                        onClick = { viewModel.setDefaultCensorshipVersion(CENSORSHIP_CENSORED) },
                        label = { Text("Censored") },
                    )
                }

                Text(
                    text = "Media Card Size (Anime, Manga, Explore)",
                    style = MaterialTheme.typography.titleSmall,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MediaCardSize.entries.forEach { size ->
                        FilterChip(
                            selected = mediaCardSize.equals(size.name, ignoreCase = true),
                            onClick = { viewModel.setMediaCardSize(size.name) },
                            label = { Text(size.label) },
                        )
                    }
                }

                Text(text = "Preferred Player", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = preferredPlayer == PLAYER_IN_APP,
                        onClick = { viewModel.setPreferredPlayer(PLAYER_IN_APP) },
                        label = { Text("In-App Pro Player") },
                    )
                    FilterChip(
                        selected = preferredPlayer == PLAYER_MPV,
                        onClick = { viewModel.setPreferredPlayer(PLAYER_MPV) },
                        label = { Text("External MPV") },
                    )
                }

                Text(
                    text = "In-Memory (RAM) Pre-Buffer Cap (In RAM: ${
                        String.format(Locale.getDefault(), "%.1f MB", preBufferCacheBytes / BYTES_PER_MB)
                    })",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "Stored in RAM only — cleared automatically when app closes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PreBufferPolicy.supportedOptionsMb.forEach { mb ->
                        FilterChip(
                            selected = preBufferMaxMb == mb,
                            onClick = { viewModel.setPreBufferMaxMb(mb) },
                            label = { Text(PreBufferPolicy.formatMbLabel(mb)) },
                        )
                    }
                }
                OutlinedButton(onClick = viewModel::clearPreBufferCache) {
                    Text("Clear RAM Pre-Buffer")
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-Update ani-cli Script", style = MaterialTheme.typography.bodyMedium)
                        val statusText = extractorStatus?.let {
                            "Script v${it.scriptVersion} • ani-cli v${it.upstreamAniCliVersion} (${it.miruroBase})"
                        } ?: "Checking script status..."
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoUpdateEnabled, onCheckedChange = viewModel::setExtractorAutoUpdateEnabled)
                }

                Button(
                    enabled = !isUpdatingExtractor,
                    onClick = viewModel::updateExtractorNow,
                ) {
                    if (isUpdatingExtractor) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(if (isUpdatingExtractor) "Updating..." else "Update Streaming Script Now")
                }
            }
        }
    }

    // Release Notifications
    Column(verticalArrangement = Arrangement.spacedBy(LocalPaddings.current.medium)) {
        Text(
            text = stringResource(R.string.release_notifications),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleLarge,
        )

        Surface(
            shape = RoundedCornerShape(LocalPaddings.current.large),
            color = cardColor,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(16.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Notify on New Releases", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "Monitors your Offline List and watched anime",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = notificationsEnabled,
                        onCheckedChange = { wantEnabled ->
                            if (wantEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!granted) {
                                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    viewModel.setNotificationsEnabled(true)
                                }
                            } else {
                                viewModel.setNotificationsEnabled(wantEnabled)
                            }
                        },
                    )
                }

                if (notificationsEnabled) {
                    NotificationCategoryRow("New Episodes", notifyEpisodes, viewModel::setNotifyEpisodes)
                    NotificationCategoryRow("New Seasons & Movies", notifySeasonsMovies, viewModel::setNotifySeasonsMovies)
                    NotificationCategoryRow("New OVAs & Specials", notifyOvas, viewModel::setNotifyOvas)
                    NotificationCategoryRow("New Mangas", notifyMangas, viewModel::setNotifyMangas)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val hasPerm = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!hasPerm) {
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            }
                        ) {
                            Text("Open System Notification Settings")
                        }
                    }
                }
            }
        }
    }

    // Social shortcut (since Library replaced Social in the 5-tab bottom bar)
    Surface(
        shape = RoundedCornerShape(LocalPaddings.current.large),
        color = cardColor,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LocalPaddings.current.large))
            .clickable(onClick = onNavigateToSocial),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.open_social),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text("Open →", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun NotificationCategoryRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
