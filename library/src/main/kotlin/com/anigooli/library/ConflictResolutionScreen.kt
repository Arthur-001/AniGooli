package com.anigooli.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Interactive fault-tolerant import conflict resolution screen.
 * Displays checksum/quarantine warnings, per-item Keep Incoming / Skip checkboxes with
 * side-by-side Local vs Incoming details, plus Keep All and Discard All bulk actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictResolutionScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val plan by viewModel.stagedRestorePlan.collectAsStateWithLifecycle()
    val currentPlan = plan
    if (currentPlan == null) {
        LaunchedEffect(Unit) { onFinished() }
        return
    }

    val keepAnimeDecisions = remember(currentPlan) {
        mutableStateMapOf<String, Boolean>().apply {
            currentPlan.animeConflicts.forEach { put(it.conflictId, false) }
        }
    }
    val keepGenreDecisions = remember(currentPlan) {
        mutableStateMapOf<String, Boolean>().apply {
            currentPlan.genreConflicts.forEach { put(it.conflictId, false) }
        }
    }
    var restorePreferences by remember(currentPlan) {
        mutableStateOf(currentPlan.preferences != null)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conflict_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            viewModel.cancelConflictResolution()
                            onFinished()
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Cancel")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            viewModel.cancelConflictResolution()
                            onFinished()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Cancel") }
                    Button(
                        onClick = {
                            val acceptedAnime = keepAnimeDecisions.filterValues { it }.keys
                            val acceptedGenre = keepGenreDecisions.filterValues { it }.keys
                            viewModel.applyConflictResolution(
                                acceptedAnimeConflictIds = acceptedAnime,
                                acceptedGenreConflictIds = acceptedGenre,
                                restorePreferences = restorePreferences,
                                onComplete = onFinished,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Apply Selection") }
                }
            }
        },
        modifier = modifier.fillMaxSize(),
    ) { paddingValues ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = paddingValues.calculateTopPadding() + 8.dp,
                bottom = paddingValues.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (currentPlan.isChecksumMismatch || currentPlan.quarantinedItems.isNotEmpty()) {
                item(key = "fault_banner") {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(12.dp),
                        ) {
                            if (currentPlan.isChecksumMismatch) {
                                Text(
                                    text = "Backup checksum mismatch detected. Recovering valid items safely.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                            if (currentPlan.quarantinedItems.isNotEmpty()) {
                                Text(
                                    text = "${currentPlan.quarantinedItems.size} corrupted record(s) quarantined.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }
            }

            item(key = "summary_and_bulk") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${currentPlan.newAnimes.size} new anime(s) will be added automatically. " +
                            "${currentPlan.animeConflicts.size} anime conflict(s) and " +
                            "${currentPlan.genreConflicts.size} genre conflict(s) found.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                currentPlan.animeConflicts.forEach { keepAnimeDecisions[it.conflictId] = true }
                                currentPlan.genreConflicts.forEach { keepGenreDecisions[it.conflictId] = true }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Keep All Incoming") }
                        OutlinedButton(
                            onClick = {
                                currentPlan.animeConflicts.forEach { keepAnimeDecisions[it.conflictId] = false }
                                currentPlan.genreConflicts.forEach { keepGenreDecisions[it.conflictId] = false }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Discard All Incoming") }
                    }
                }
            }

            if (currentPlan.preferences != null) {
                item(key = "prefs_toggle") {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = "Restore saved app preferences",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Switch(
                                checked = restorePreferences,
                                onCheckedChange = { restorePreferences = it },
                            )
                        }
                    }
                }
            }

            items(currentPlan.animeConflicts, key = { it.conflictId }) { conflict ->
                val keepIncoming = keepAnimeDecisions[conflict.conflictId] == true
                AnimeConflictCard(
                    conflict = conflict,
                    keepIncoming = keepIncoming,
                    onToggle = { keepAnimeDecisions[conflict.conflictId] = it },
                )
            }

            items(currentPlan.genreConflicts, key = { it.conflictId }) { gc ->
                val keepIncoming = keepGenreDecisions[gc.conflictId] == true
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp),
                    ) {
                        Checkbox(
                            checked = keepIncoming,
                            onCheckedChange = { keepGenreDecisions[gc.conflictId] = it },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Genre Conflict: ${gc.existing.name}", fontWeight = FontWeight.SemiBold)
                            Text(
                                text = if (keepIncoming) {
                                    "Replace with incoming (${gc.incoming.name}, ${gc.incoming.colorHex})"
                                } else {
                                    "Skip incoming (keep local)"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimeConflictCard(
    conflict: AnimeConflict,
    keepIncoming: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val localTitle = conflict.existing.anime.titleEnglish
        ?: conflict.existing.anime.titleRomaji
        ?: conflict.existing.anime.titleNative
        ?: "Local Entry"

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Checkbox(checked = keepIncoming, onCheckedChange = onToggle)
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = localTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (keepIncoming) "Keep Incoming (Overwrite Local)" else "Skip Incoming (Keep Local)",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (keepIncoming) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.weight(1f),
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("LOCAL", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Text(
                            text = "Status: ${OfflineWatchStatus.safeParse(conflict.existing.anime.status).displayLabel}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = "Progress: ${conflict.existing.anime.episodesWatched} ep",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = "Score: ${conflict.existing.anime.score ?: "-"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.weight(1f),
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("INCOMING", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Text(
                            text = "Status: ${OfflineWatchStatus.safeParse(conflict.incoming.status).displayLabel}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = "Progress: ${conflict.incoming.episodesWatched} ep",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = "Score: ${conflict.incoming.score ?: "-"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
