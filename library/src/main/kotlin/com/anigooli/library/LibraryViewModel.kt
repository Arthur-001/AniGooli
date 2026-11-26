package com.anigooli.library

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imashnake.animite.api.anilist.sanitize.media.Media
import com.imashnake.animite.api.preferences.DEFAULT_PERSONAL_LIST_COLUMNS
import com.imashnake.animite.api.preferences.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L

/** Sort modes for the offline personal anime list. */
enum class OfflineListSort(val label: String) {
    RECENTLY_UPDATED("Recently Updated"),
    TITLE_ASC("Title A–Z"),
    SCORE_DESC("Highest Score"),
    PROGRESS_DESC("Most Watched"),
}

/** UI state for the Library screen's offline personal anime list tab. */
data class OfflineListUiState(
    /** Filtered and sorted anime entries. */
    val entries: List<AnimeWithGenres> = emptyList(),
    /** All available genres (custom + default). */
    val allGenres: List<GenreEntity> = emptyList(),
    /** Active grid column count (2, 3, or 4), persisted in DataStore. */
    val columns: Int = DEFAULT_PERSONAL_LIST_COLUMNS,
    /** Active title language preference (defaults to ENGLISH). */
    val titleLanguage: Media.Language = Media.Language.ENGLISH,
    /** Current offline search query. */
    val searchQuery: String = "",
    /** Selected status filter, or null for all statuses. */
    val selectedStatus: OfflineWatchStatus? = null,
    /** Selected genre ID filter, or null for all genres. */
    val selectedGenreId: String? = null,
    /** Selected sort order. */
    val sort: OfflineListSort = OfflineListSort.RECENTLY_UPDATED,
)

/** ViewModel backing [LibraryScreen], [GenreManagerScreen], and [ConflictResolutionScreen]. */
@HiltViewModel
@Suppress("TooManyFunctions")
class LibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: LibraryRepository,
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val selectedStatus = MutableStateFlow<OfflineWatchStatus?>(null)
    private val selectedGenreId = MutableStateFlow<String?>(null)
    private val sortMode = MutableStateFlow(OfflineListSort.RECENTLY_UPDATED)

    /** Summary of the most recent import/restore operation, or null when dismissed. */
    val lastRestoreSummary: StateFlow<RestoreSummary?> = repository.lastRestoreSummary

    /** Currently staged backup/import plan awaiting conflict resolution. */
    val stagedRestorePlan: StateFlow<RestorePlan?> = repository.stagedRestorePlan

    /** All genres streamed for [GenreManagerScreen]. */
    val allGenres: StateFlow<List<GenreEntity>> = repository.observeAllGenres()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** Combined reactive UI state for the offline personal anime list. */
    val uiState: StateFlow<OfflineListUiState> = combine(
        repository.observeAllAnime(),
        repository.observeAllGenres(),
        preferencesRepository.personalListColumns.filterNotNull(),
        preferencesRepository.language.filterNotNull().map { Media.Language.safeValueOf(it) },
        combine(searchQuery, selectedStatus, selectedGenreId, sortMode) { q, s, g, sort ->
            FilterParams(q, s, g, sort)
        },
    ) { entries, genres, cols, lang, filter ->
        val filtered = entries.filter { item ->
            val titleMatch = if (filter.query.isBlank()) {
                true
            } else {
                val q = filter.query.trim()
                listOfNotNull(
                    item.anime.titleEnglish,
                    item.anime.titleRomaji,
                    item.anime.titleNative,
                    item.anime.notes,
                ).any { it.contains(q, ignoreCase = true) }
            }
            val statusMatch = filter.status == null || item.anime.status == filter.status.name
            val genreMatch = filter.genreId == null || item.genres.any { it.id == filter.genreId }
            titleMatch && statusMatch && genreMatch
        }
        val sorted = when (filter.sort) {
            OfflineListSort.RECENTLY_UPDATED -> filtered.sortedByDescending { it.anime.updatedAt }
            OfflineListSort.TITLE_ASC -> filtered.sortedBy { resolveDisplayTitle(it.anime, lang).lowercase() }
            OfflineListSort.SCORE_DESC -> filtered.sortedByDescending { it.anime.score ?: -1f }
            OfflineListSort.PROGRESS_DESC -> filtered.sortedByDescending { it.anime.episodesWatched }
        }
        OfflineListUiState(
            entries = sorted,
            allGenres = genres,
            columns = cols.coerceIn(2, 4),
            titleLanguage = lang,
            searchQuery = filter.query,
            selectedStatus = filter.status,
            selectedGenreId = filter.genreId,
            sort = filter.sort,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), OfflineListUiState())

    init {
        viewModelScope.launch {
            repository.ensureDefaultGenresSeeded()
        }
    }

    /** Updates search filter query. */
    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    /** Updates watch status filter. */
    fun setStatusFilter(status: OfflineWatchStatus?) {
        selectedStatus.value = status
    }

    /** Updates genre ID filter. */
    fun setGenreFilter(genreId: String?) {
        selectedGenreId.value = genreId
    }

    /** Updates sort mode. */
    fun setSort(sort: OfflineListSort) {
        sortMode.value = sort
    }

    /** Persists 2, 3, or 4 column card layout choice in DataStore. */
    fun setColumns(columns: Int) = viewModelScope.launch {
        preferencesRepository.setPersonalListColumns(columns)
    }

    /** Creates or updates a manual or AniList-linked offline anime entry. */
    fun saveAnime(
        localId: String?,
        anilistId: Int?,
        titleEnglish: String?,
        titleRomaji: String?,
        titleNative: String?,
        coverUrl: String?,
        status: OfflineWatchStatus,
        episodesWatched: Int,
        totalEpisodes: Int?,
        score: Float?,
        notes: String,
        genreIds: List<String>,
    ) = viewModelScope.launch {
        repository.saveOfflineAnime(
            localId = localId,
            anilistId = anilistId,
            titleEnglish = titleEnglish,
            titleRomaji = titleRomaji,
            titleNative = titleNative,
            coverUrl = coverUrl,
            status = status,
            episodesWatched = episodesWatched,
            totalEpisodes = totalEpisodes,
            score = score,
            notes = notes,
            genreIds = genreIds,
        )
    }

    /** Increments watched episode progress by 1 cleanly. */
    fun incrementEpisode(entry: AnimeWithGenres) = viewModelScope.launch {
        val total = entry.anime.totalEpisodes
        val next = if (total != null) {
            (entry.anime.episodesWatched + 1).coerceAtMost(total)
        } else {
            entry.anime.episodesWatched + 1
        }
        val nextStatus = if (total != null && next >= total) {
            OfflineWatchStatus.COMPLETED
        } else {
            OfflineWatchStatus.safeParse(entry.anime.status)
        }
        repository.saveOfflineAnime(
            localId = entry.anime.localId,
            anilistId = entry.anime.anilistId,
            titleEnglish = entry.anime.titleEnglish,
            titleRomaji = entry.anime.titleRomaji,
            titleNative = entry.anime.titleNative,
            coverUrl = entry.anime.coverUrl,
            status = nextStatus,
            episodesWatched = next,
            totalEpisodes = total,
            score = entry.anime.score,
            notes = entry.anime.notes,
            genreIds = entry.genres.map { it.id },
        )
    }

    /** Deletes an anime from the offline list. */
    fun deleteAnime(localId: String) = viewModelScope.launch {
        repository.deleteOfflineAnime(localId)
    }

    /** Copies a user-selected cover image URI into persistent internal app storage. */
    suspend fun importLocalCoverImage(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "custom_covers").apply { mkdirs() }
            val dst = File(dir, "cover_${UUID.randomUUID()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dst.outputStream().use { out -> input.copyTo(out) }
            }
            dst.absolutePath
        }.getOrNull()
    }

    /** Creates a new custom genre. */
    suspend fun createCustomGenre(name: String, colorHex: String): Result<GenreEntity> =
        repository.createCustomGenre(name, colorHex)

    /** Edits an existing custom genre. */
    suspend fun updateCustomGenre(genreId: String, name: String, colorHex: String): Result<Unit> =
        repository.updateCustomGenre(genreId, name, colorHex)

    /** Deletes a custom genre while preserving anime entries. */
    fun deleteCustomGenre(genreId: String) = viewModelScope.launch {
        repository.deleteCustomGenre(genreId)
    }

    /** Counts anime entries tagged with [genreId]. */
    suspend fun countAnimeForGenre(genreId: String): Int = repository.countAnimeUsingGenre(genreId)

    /** Exports full offline backup JSON into [uri] via SAF. */
    suspend fun exportBackupToUri(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = repository.exportBackupJson(includePreferences = true)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
            } ?: error("Unable to open output stream")
        }
    }

    /**
     * Parses an import/backup file at [uri], invokes [onConflictsFound] if interactive resolution is needed,
     * or applies non-conflicting entries directly and surfaces [lastRestoreSummary].
     */
    fun importBackupFromUri(uri: Uri, onConflictsFound: () -> Unit) = viewModelScope.launch {
        val rawText = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
        }
        if (rawText == null) {
            repository.publishRestoreSummary(
                RestoreSummary(
                    importedCount = 0,
                    updatedCount = 0,
                    skippedCount = 0,
                    genresImportedCount = 0,
                    quarantinedItems = listOf("Unable to read selected file"),
                    isChecksumMismatch = false,
                )
            )
            return@launch
        }
        val plan = repository.stageImportPlan(rawText)
        if (plan.hasConflicts) {
            onConflictsFound()
        } else {
            repository.applyRestorePlan(
                plan = plan,
                acceptedAnimeConflictIds = emptySet(),
                acceptedGenreConflictIds = emptySet(),
                restorePreferences = true,
            )
        }
    }

    /** Applies user conflict choices inside an atomic Room transaction. */
    fun applyConflictResolution(
        acceptedAnimeConflictIds: Set<String>,
        acceptedGenreConflictIds: Set<String>,
        restorePreferences: Boolean,
        onComplete: () -> Unit,
    ) = viewModelScope.launch {
        val plan = repository.stagedRestorePlan.value ?: return@launch
        repository.applyRestorePlan(
            plan = plan,
            acceptedAnimeConflictIds = acceptedAnimeConflictIds,
            acceptedGenreConflictIds = acceptedGenreConflictIds,
            restorePreferences = restorePreferences,
        )
        onComplete()
    }

    /** Cancels pending conflict resolution. */
    fun cancelConflictResolution() {
        repository.clearStagedRestorePlan()
    }

    /** Dismisses the post-restore summary dialog. */
    fun dismissRestoreSummary() {
        repository.dismissRestoreSummary()
    }

    companion object {
        /** Resolves display title for an offline anime according to the user's [language] preference. */
        fun resolveDisplayTitle(anime: OfflineAnimeEntity, language: Media.Language): String {
            return Media.resolveTitle(
                english = anime.titleEnglish,
                nativeTitle = anime.titleNative,
                romaji = anime.titleRomaji,
                userPreferred = anime.titleEnglish ?: anime.titleRomaji ?: anime.titleNative,
                language = language,
            ) ?: "Untitled"
        }
    }
}

private data class FilterParams(
    val query: String,
    val status: OfflineWatchStatus?,
    val genreId: String?,
    val sort: OfflineListSort,
)
