package com.anigooli.library

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.anigooli.downloads.DownloadsManagerScreen
import com.imashnake.animite.api.anilist.sanitize.media.Media
import kotlinx.coroutines.launch
import java.io.File

private const val COVER_ASPECT_RATIO = 0.7f
private const val NAV_BAR_CLEARANCE_DP = 88

/**
 * Top-level Library tab housing both the Offline Personal Anime List (2/3/4 column views,
 * custom genres, fault-tolerant backup/restore) and the Offline Downloads Manager.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onNavigateToMedia: (Int) -> Unit,
    onNavigateToGenres: () -> Unit,
    onNavigateToConflicts: () -> Unit,
    modifier: Modifier = Modifier,
    contentWindowInsets: WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val restoreSummary by viewModel.lastRestoreSummary.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showEditorSheet by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<AnimeWithGenres?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val res = viewModel.exportBackupToUri(uri)
                val msg = if (res.isSuccess) "Backup exported successfully" else "Export failed"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importBackupFromUri(uri, onConflictsFound = onNavigateToConflicts)
        }
    }

    val insetsPadding = contentWindowInsets.asPaddingValues()

    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = {
            if (selectedTab == 0) {
                FloatingActionButton(
                    onClick = {
                        editingEntry = null
                        showEditorSheet = true
                    },
                    modifier = Modifier.padding(bottom = NAV_BAR_CLEARANCE_DP.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add Anime")
                }
            }
        },
        modifier = modifier.fillMaxSize(),
    ) { _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = insetsPadding.calculateTopPadding()),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "Library",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )

                if (selectedTab == 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ColumnCountButton(
                            columns = uiState.columns,
                            onSelectColumns = viewModel::setColumns,
                        )
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "Library options")
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Manage Custom Genres") },
                                    onClick = {
                                        showMenu = false
                                        onNavigateToGenres()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Export Backup (JSON)") },
                                    onClick = {
                                        showMenu = false
                                        exportLauncher.launch("anigooli-backup.json")
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Import / Restore (JSON or MAL XML)") },
                                    onClick = {
                                        showMenu = false
                                        importLauncher.launch(
                                            arrayOf("application/json", "text/xml", "application/xml", "*/*")
                                        )
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Sort: ${uiState.sort.label}") },
                                    onClick = {
                                        showMenu = false
                                        showSortMenu = true
                                    },
                                )
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                            ) {
                                OfflineListSort.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        trailingIcon = if (uiState.sort == option) {
                                            { Icon(Icons.Rounded.Check, contentDescription = null) }
                                        } else null,
                                        onClick = {
                                            viewModel.setSort(option)
                                            showSortMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.library_tab_list)) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.library_tab_downloads)) },
                )
            }

            if (selectedTab == 0) {
                OfflineListContent(
                    uiState = uiState,
                    onSearchChange = viewModel::setSearchQuery,
                    onSelectStatus = viewModel::setStatusFilter,
                    onSelectGenre = viewModel::setGenreFilter,
                    onEditEntry = {
                        editingEntry = it
                        showEditorSheet = true
                    },
                    onOpenAniListPage = onNavigateToMedia,
                    onIncrementEpisode = viewModel::incrementEpisode,
                    bottomPaddingDp = NAV_BAR_CLEARANCE_DP + 64,
                )
            } else {
                DownloadsManagerScreen(
                    contentPadding = PaddingValues(bottom = NAV_BAR_CLEARANCE_DP.dp),
                )
            }
        }
    }

    if (showEditorSheet) {
        OfflineAnimeEditorSheet(
            existing = editingEntry,
            allGenres = uiState.allGenres,
            onImportLocalImage = viewModel::importLocalCoverImage,
            onSave = { titleEng, titleRom, cover, status, eps, total, score, notes, genreIds ->
                viewModel.saveAnime(
                    localId = editingEntry?.anime?.localId,
                    anilistId = editingEntry?.anime?.anilistId,
                    titleEnglish = titleEng,
                    titleRomaji = titleRom,
                    titleNative = editingEntry?.anime?.titleNative,
                    coverUrl = cover,
                    status = status,
                    episodesWatched = eps,
                    totalEpisodes = total,
                    score = score,
                    notes = notes,
                    genreIds = genreIds,
                )
                showEditorSheet = false
            },
            onDelete = editingEntry?.let { target ->
                {
                    viewModel.deleteAnime(target.anime.localId)
                    showEditorSheet = false
                }
            },
            onOpenAnilist = editingEntry?.anime?.anilistId?.let { id ->
                {
                    showEditorSheet = false
                    onNavigateToMedia(id)
                }
            },
            onDismiss = { showEditorSheet = false },
        )
    }

    restoreSummary?.let { summary ->
        RestoreSummaryDialog(
            summary = summary,
            onDismiss = viewModel::dismissRestoreSummary,
        )
    }
}

@Composable
private fun ColumnCountButton(
    columns: Int,
    onSelectColumns: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(2, 3, 4).forEach { count ->
            val isSelected = columns == count
            Surface(
                shape = CircleShape,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onSelectColumns(count) },
            ) {
                Text(
                    text = "${count}x",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun OfflineListContent(
    uiState: OfflineListUiState,
    onSearchChange: (String) -> Unit,
    onSelectStatus: (OfflineWatchStatus?) -> Unit,
    onSelectGenre: (String?) -> Unit,
    onEditEntry: (AnimeWithGenres) -> Unit,
    onOpenAniListPage: (Int) -> Unit,
    onIncrementEpisode: (AnimeWithGenres) -> Unit,
    bottomPaddingDp: Int,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text("Search your offline list...") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = if (uiState.searchQuery.isNotEmpty()) {
                {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear")
                    }
                }
            } else null,
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = uiState.selectedStatus == null,
                    onClick = { onSelectStatus(null) },
                    label = { Text("All") },
                )
            }
            items(OfflineWatchStatus.entries) { status ->
                FilterChip(
                    selected = uiState.selectedStatus == status,
                    onClick = {
                        onSelectStatus(if (uiState.selectedStatus == status) null else status)
                    },
                    label = { Text(status.displayLabel) },
                )
            }
        }

        if (uiState.allGenres.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(uiState.allGenres, key = { it.id }) { genre ->
                    val isSelected = uiState.selectedGenreId == genre.id
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectGenre(if (isSelected) null else genre.id) },
                        label = {
                            Text(if (genre.isCustom) "${genre.name} ★" else genre.name)
                        },
                    )
                }
            }
        }

        if (uiState.entries.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            ) {
                Text(
                    text = stringResource(R.string.library_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(uiState.columns),
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 8.dp,
                    bottom = bottomPaddingDp.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(uiState.entries, key = { it.anime.localId }) { item ->
                    OfflineAnimeCard(
                        item = item,
                        language = uiState.titleLanguage,
                        compact = uiState.columns >= 4,
                        onClick = {
                            val anilistId = item.anime.anilistId
                            if (anilistId != null) {
                                onOpenAniListPage(anilistId)
                            } else {
                                onEditEntry(item)
                            }
                        },
                        onEditClick = { onEditEntry(item) },
                        onPlusOne = { onIncrementEpisode(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OfflineAnimeCard(
    item: AnimeWithGenres,
    language: Media.Language,
    compact: Boolean,
    onClick: () -> Unit,
    onEditClick: () -> Unit,
    onPlusOne: () -> Unit,
) {
    val displayTitle = LibraryViewModel.resolveDisplayTitle(item.anime, language)
    val coverModel: Any? = item.anime.localCoverPath?.let { File(it) } ?: item.anime.coverUrl
    val status = OfflineWatchStatus.safeParse(item.anime.status)

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(COVER_ASPECT_RATIO)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                if (coverModel != null) {
                    AsyncImage(
                        model = coverModel,
                        contentDescription = displayTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                    ) {
                        Text(
                            text = displayTitle.take(2).uppercase(),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clickable(onClick = onEditClick),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = "Edit",
                        modifier = Modifier.padding(6.dp).size(14.dp),
                    )
                }

                val epLabel = item.anime.totalEpisodes?.let {
                    "${item.anime.episodesWatched}/$it"
                } ?: "Ep ${item.anime.episodesWatched}"
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clickable(onClick = onPlusOne),
                ) {
                    Text(
                        text = "$epLabel +",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    text = displayTitle,
                    style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = status.displayLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (!compact && item.genres.isNotEmpty()) {
                    Text(
                        text = item.genres.take(2).joinToString(", ") { it.name },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun OfflineAnimeEditorSheet(
    existing: AnimeWithGenres?,
    allGenres: List<GenreEntity>,
    onImportLocalImage: suspend (Uri) -> String?,
    onSave: (
        titleEnglish: String?,
        titleRomaji: String?,
        coverUrl: String?,
        status: OfflineWatchStatus,
        episodesWatched: Int,
        totalEpisodes: Int?,
        score: Float?,
        notes: String,
        genreIds: List<String>,
    ) -> Unit,
    onDelete: (() -> Unit)?,
    onOpenAnilist: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var titleEnglish by remember { mutableStateOf(existing?.anime?.titleEnglish.orEmpty()) }
    var titleRomaji by remember { mutableStateOf(existing?.anime?.titleRomaji.orEmpty()) }
    var coverUrl by remember {
        mutableStateOf(existing?.anime?.localCoverPath ?: existing?.anime?.coverUrl.orEmpty())
    }
    var status by remember {
        mutableStateOf(OfflineWatchStatus.safeParse(existing?.anime?.status))
    }
    var epsText by remember { mutableStateOf((existing?.anime?.episodesWatched ?: 0).toString()) }
    var totalEpsText by remember { mutableStateOf(existing?.anime?.totalEpisodes?.toString().orEmpty()) }
    var scoreText by remember { mutableStateOf(existing?.anime?.score?.toString().orEmpty()) }
    var notes by remember { mutableStateOf(existing?.anime?.notes.orEmpty()) }
    val selectedGenreIds = remember {
        mutableStateListOf<String>().apply {
            addAll(existing?.genres?.map { it.id }.orEmpty())
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                onImportLocalImage(uri)?.let { localPath -> coverUrl = localPath }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = if (existing == null) "Add Offline Anime" else "Edit Entry",
                style = MaterialTheme.typography.titleLarge,
            )

            OutlinedTextField(
                value = titleEnglish,
                onValueChange = { titleEnglish = it },
                label = { Text("English Title *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = titleRomaji,
                onValueChange = { titleRomaji = it },
                label = { Text("Japanese-English / Romaji Title (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = coverUrl,
                    onValueChange = { coverUrl = it },
                    label = { Text("Cover URL or local path") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { imagePicker.launch("image/*") }) {
                    Text("Pick Image")
                }
            }

            Text("Status", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OfflineWatchStatus.entries.forEach { option ->
                    FilterChip(
                        selected = status == option,
                        onClick = { status = option },
                        label = { Text(option.displayLabel) },
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = epsText,
                    onValueChange = { epsText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Watched") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = totalEpsText,
                    onValueChange = { totalEpsText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Total Eps") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = scoreText,
                    onValueChange = { scoreText = it },
                    label = { Text("Score /10") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Personal Notes") },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Genres (Default & Custom)", style = MaterialTheme.typography.labelLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                allGenres.forEach { genre ->
                    val isSelected = genre.id in selectedGenreIds
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            if (isSelected) selectedGenreIds.remove(genre.id) else selectedGenreIds.add(genre.id)
                        },
                        label = {
                            Text(if (genre.isCustom) "${genre.name} ★" else genre.name)
                        },
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = "Delete entry",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (onOpenAnilist != null) {
                    OutlinedButton(onClick = onOpenAnilist) {
                        Text("Open Page")
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = titleEnglish.isNotBlank() || titleRomaji.isNotBlank(),
                    onClick = {
                        onSave(
                            titleEnglish.ifBlank { null },
                            titleRomaji.ifBlank { null },
                            coverUrl.ifBlank { null },
                            status,
                            epsText.toIntOrNull() ?: 0,
                            totalEpsText.toIntOrNull(),
                            scoreText.toFloatOrNull(),
                            notes,
                            selectedGenreIds.toList(),
                        )
                    },
                ) {
                    Text("Save")
                }
            }
        }
    }
}

@Composable
private fun RestoreSummaryDialog(
    summary: RestoreSummary,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Summary") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (summary.isChecksumMismatch) {
                    Text(
                        text = "Warning: Backup checksum differed from payload; valid items were recovered safely.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("• Newly imported: ${summary.importedCount}")
                Text("• Updated (conflicts kept): ${summary.updatedCount}")
                Text("• Skipped (kept local): ${summary.skippedCount}")
                Text("• Custom genres synced: ${summary.genresImportedCount}")
                if (summary.quarantinedItems.isNotEmpty()) {
                    Text(
                        text = "Quarantined corrupted records (${summary.quarantinedItems.size}):\n" +
                            summary.quarantinedItems.take(5).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        },
    )
}
