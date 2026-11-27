package com.anigooli.library

import androidx.room.withTransaction
import com.imashnake.animite.api.preferences.AUDIO_TRACK_JAPANESE
import com.imashnake.animite.api.preferences.CENSORSHIP_UNCENSORED
import com.imashnake.animite.api.preferences.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository managing the offline personal anime list, custom genres CRUD,
 * per-anime audio/subtitle preferences, watch history, and fault-tolerant atomic backup/restore.
 */
@Singleton
@Suppress("TooManyFunctions")
class LibraryRepository @Inject constructor(
    private val database: LibraryDatabase,
    private val dao: LibraryDao,
    private val preferencesRepository: PreferencesRepository,
) {
    private val _stagedRestorePlan = MutableStateFlow<RestorePlan?>(null)
    /** Pending conflict-resolution plan held in memory cleanly without exceeding Intent limits. */
    val stagedRestorePlan: StateFlow<RestorePlan?> = _stagedRestorePlan.asStateFlow()

    private val _lastRestoreSummary = MutableStateFlow<RestoreSummary?>(null)
    /** Summary of the most recent import/restore operation, shared across Library and Conflict screens. */
    val lastRestoreSummary: StateFlow<RestoreSummary?> = _lastRestoreSummary.asStateFlow()

    /** In-memory cache of watched episode numbers keyed by AniList ID. */
    private val watchedEpisodesMemory = ConcurrentHashMap<Int, Set<String>>()

    /** Dismisses the latest restore summary dialog. */
    fun dismissRestoreSummary() {
        _lastRestoreSummary.value = null
    }

    /** Records an immediate import/restore summary message. */
    fun publishRestoreSummary(summary: RestoreSummary) {
        _lastRestoreSummary.value = summary
    }

    /** Streams all offline anime entries with their assigned genres. */
    fun observeAllAnime(): Flow<List<AnimeWithGenres>> = dao.observeAllAnimeWithGenres()

    /** Streams whether [anilistId] is present in the offline list. */
    fun observeIsInList(anilistId: Int): Flow<Boolean> = dao.observeExistsByAnilistId(anilistId)

    /** Streams all available genres (custom + default). */
    fun observeAllGenres(): Flow<List<GenreEntity>> = dao.observeAllGenres()

    /** Idempotently seeds built-in default genres if missing. */
    suspend fun ensureDefaultGenresSeeded() = withContext(Dispatchers.IO) {
        dao.insertGenresIgnore(
            DefaultGenres.names.map { name ->
                GenreEntity(
                    id = DefaultGenres.defaultId(name),
                    name = name,
                    isCustom = false,
                )
            }
        )
    }

    /**
     * Adds an anime from an AniList detail page into the local offline list using its whole-series
     * base title. Prevents duplicates across related seasons, OVAs, movies, manga, or matching titles.
     */
    suspend fun addOrUpdateFromAnilist(
        anilistId: Int,
        titleEnglish: String?,
        titleRomaji: String?,
        titleNative: String?,
        coverUrl: String?,
        totalEpisodes: Int?,
        genreNames: List<String>,
        status: OfflineWatchStatus = OfflineWatchStatus.PLAN_TO_WATCH,
        relatedAnilistIds: Set<Int> = emptySet(),
    ) = withContext(Dispatchers.IO) {
        ensureDefaultGenresSeeded()
        val normalizedEnglish = AnimeTitleNormalizer.displayWholeTitle(titleEnglish)
        val normalizedRomaji = AnimeTitleNormalizer.displayWholeTitle(titleRomaji)
        val candidateTitles = listOf(titleEnglish, titleRomaji, titleNative, normalizedEnglish, normalizedRomaji)

        val existing = findMatchingOfflineAnime(
            anilistId = anilistId,
            relatedAnilistIds = relatedAnilistIds,
            titles = candidateTitles,
        )
        if (existing != null) {
            return@withContext
        }

        val now = System.currentTimeMillis()
        val localId = UUID.randomUUID().toString()
        val entity = OfflineAnimeEntity(
            localId = localId,
            anilistId = anilistId,
            titleEnglish = normalizedEnglish,
            titleRomaji = normalizedRomaji,
            titleNative = titleNative?.trim()?.takeIf { it.isNotEmpty() },
            coverUrl = coverUrl,
            localCoverPath = null,
            status = status.name,
            episodesWatched = 0,
            totalEpisodes = totalEpisodes,
            score = null,
            notes = "",
            createdAt = now,
            updatedAt = now,
        )
        database.withTransaction {
            dao.upsertAnime(entity)
            val genreIds = resolveOrCreateGenreIds(genreNames, allowCreateCustom = false)
            dao.setGenresForAnime(localId, genreIds)
        }
    }

    /**
     * Removes an AniList-linked anime (or its matching franchise entry) from the offline personal list
     * and cleans up its release notification tracker so notifications stay strictly synced with My List.
     */
    suspend fun removeByAnilistId(
        anilistId: Int,
        relatedAnilistIds: Set<Int> = emptySet(),
        titles: List<String?> = emptyList(),
    ) = withContext(Dispatchers.IO) {
        val matched = findMatchingOfflineAnime(
            anilistId = anilistId,
            relatedAnilistIds = relatedAnilistIds,
            titles = titles,
        )
        if (matched != null) {
            dao.deleteAnime(matched.localId)
            matched.anilistId?.let { dao.deleteReleaseTracker(it) }
        } else {
            dao.deleteByAnilistId(anilistId)
            dao.deleteReleaseTracker(anilistId)
        }
    }

    private suspend fun findMatchingOfflineAnime(
        anilistId: Int?,
        relatedAnilistIds: Set<Int>,
        titles: List<String?>,
    ): OfflineAnimeEntity? {
        if (anilistId != null) {
            dao.getByAnilistId(anilistId)?.let { return it }
        }
        val lookupIds = (relatedAnilistIds + listOfNotNull(anilistId)).filter { it > 0 }.distinct()
        if (lookupIds.isNotEmpty()) {
            dao.getByAnyAnilistId(lookupIds)?.let { return it }
        }
        if (titles.any { !it.isNullOrBlank() }) {
            return dao.getAllAnimeOnce().firstOrNull { entry ->
                AnimeTitleNormalizer.titlesMatch(
                    listOf(entry.titleEnglish, entry.titleRomaji, entry.titleNative),
                    titles,
                )
            }
        }
        return null
    }

    /**
     * Saves a manual or edited offline anime entry along with its assigned genres atomically,
     * normalizing the title to the whole anime name and rejecting duplicates.
     */
    suspend fun saveOfflineAnime(
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
    ): Result<Unit> = withContext(Dispatchers.IO) {
        ensureDefaultGenresSeeded()
        val normalizedEnglish = AnimeTitleNormalizer.displayWholeTitle(titleEnglish)
        val normalizedRomaji = AnimeTitleNormalizer.displayWholeTitle(titleRomaji)
        val candidateTitles = listOf(titleEnglish, titleRomaji, titleNative, normalizedEnglish, normalizedRomaji)

        val existing = localId?.let { dao.getByLocalId(it) }
        val duplicate = dao.getAllAnimeOnce().firstOrNull { entry ->
            if (existing != null && entry.localId == existing.localId) return@firstOrNull false
            (anilistId != null && entry.anilistId == anilistId) ||
                AnimeTitleNormalizer.titlesMatch(
                    listOf(entry.titleEnglish, entry.titleRomaji, entry.titleNative),
                    candidateTitles,
                )
        }
        if (duplicate != null) {
            val dupName = duplicate.titleEnglish ?: duplicate.titleRomaji ?: "This anime"
            return@withContext Result.failure(IllegalStateException("\"$dupName\" is already in your list"))
        }

        val now = System.currentTimeMillis()
        val resolvedId = existing?.localId ?: UUID.randomUUID().toString()
        val entity = OfflineAnimeEntity(
            localId = resolvedId,
            anilistId = anilistId ?: existing?.anilistId,
            titleEnglish = normalizedEnglish,
            titleRomaji = normalizedRomaji,
            titleNative = titleNative?.trim()?.takeIf { it.isNotEmpty() },
            coverUrl = coverUrl?.trim()?.takeIf { it.isNotEmpty() },
            localCoverPath = existing?.localCoverPath,
            status = status.name,
            episodesWatched = episodesWatched.coerceAtLeast(0),
            totalEpisodes = totalEpisodes?.takeIf { it > 0 },
            score = score?.coerceIn(0f, 10f),
            notes = notes.trim(),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        database.withTransaction {
            dao.upsertAnime(entity)
            dao.setGenresForAnime(resolvedId, genreIds)
        }
        Result.success(Unit)
    }

    /** Deletes an offline anime entry by [localId] and removes its release notification tracker. */
    suspend fun deleteOfflineAnime(localId: String) = withContext(Dispatchers.IO) {
        val existing = dao.getByLocalId(localId)
        dao.deleteAnime(localId)
        existing?.anilistId?.let { dao.deleteReleaseTracker(it) }
    }

    /** Creates a new user-defined custom genre if the name does not collide with an existing genre. */
    suspend fun createCustomGenre(name: String, colorHex: String): Result<GenreEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                ensureDefaultGenresSeeded()
                val trimmed = name.trim()
                require(trimmed.isNotEmpty()) { "Genre name cannot be empty" }
                val existing = dao.getAllGenresOnce()
                require(existing.none { it.name.equals(trimmed, ignoreCase = true) }) {
                    "Genre \"$trimmed\" already exists"
                }
                val genre = GenreEntity(
                    id = "custom_${UUID.randomUUID()}",
                    name = trimmed,
                    isCustom = true,
                    colorHex = colorHex,
                )
                dao.upsertGenre(genre)
                genre
            }
        }

    /** Updates a user-created custom genre's name and color. */
    suspend fun updateCustomGenre(genreId: String, newName: String, newColorHex: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val trimmed = newName.trim()
                require(trimmed.isNotEmpty()) { "Genre name cannot be empty" }
                val all = dao.getAllGenresOnce()
                val current = all.firstOrNull { it.id == genreId && it.isCustom }
                    ?: error("Custom genre not found")
                require(all.none { it.id != genreId && it.name.equals(trimmed, ignoreCase = true) }) {
                    "Another genre named \"$trimmed\" already exists"
                }
                dao.upsertGenre(current.copy(name = trimmed, colorHex = newColorHex))
            }
        }

    /** Deletes a custom genre while preserving all anime entries (removes only tag links). */
    suspend fun deleteCustomGenre(genreId: String) = withContext(Dispatchers.IO) {
        dao.deleteCustomGenre(genreId)
    }

    /** Counts how many anime entries currently reference [genreId]. */
    suspend fun countAnimeUsingGenre(genreId: String): Int = withContext(Dispatchers.IO) {
        dao.countAnimeForGenre(genreId)
    }

    /** Exports offline anime list, custom genres, and user preferences to a checksummed JSON bundle. */
    suspend fun exportBackupJson(includePreferences: Boolean = true): String = withContext(Dispatchers.IO) {
        ensureDefaultGenresSeeded()
        val animes = dao.getAllAnimeWithGenresOnce()
        val genres = dao.getAllGenresOnce()
        val prefs = if (includePreferences) capturePreferencesSnapshot() else null
        BackupEngine.exportToJson(animes = animes, genres = genres, preferences = prefs)
    }

    /** Parses an import file fault-tolerantly and stages its [RestorePlan] for immediate or conflict resolution. */
    suspend fun stageImportPlan(rawText: String): RestorePlan = withContext(Dispatchers.IO) {
        ensureDefaultGenresSeeded()
        val parsed = BackupEngine.parseImport(rawText)
        val existingAnimes = dao.getAllAnimeWithGenresOnce()
        val existingGenres = dao.getAllGenresOnce()
        val plan = BackupEngine.prepareRestorePlan(parsed, existingAnimes, existingGenres)
        _stagedRestorePlan.value = plan
        plan
    }

    /** Clears any staged restore plan once applied or cancelled. */
    fun clearStagedRestorePlan() {
        _stagedRestorePlan.value = null
    }

    /**
     * Atomically applies [plan] inside a Room transaction, overwriting only conflicts whose
     * IDs are present in [acceptedAnimeConflictIds] and [acceptedGenreConflictIds].
     */
    suspend fun applyRestorePlan(
        plan: RestorePlan,
        acceptedAnimeConflictIds: Set<String>,
        acceptedGenreConflictIds: Set<String>,
        restorePreferences: Boolean = true,
    ): RestoreSummary = withContext(Dispatchers.IO) {
        ensureDefaultGenresSeeded()
        var importedCount = 0
        var updatedCount = 0
        var skippedCount = 0
        var genresImportedCount = 0

        database.withTransaction {
            for (g in plan.newGenres) {
                dao.upsertGenre(GenreEntity(id = g.id, name = g.name, isCustom = true, colorHex = g.colorHex))
                genresImportedCount++
            }
            for (gc in plan.genreConflicts) {
                if (gc.conflictId in acceptedGenreConflictIds) {
                    dao.upsertGenre(
                        gc.existing.copy(
                            name = gc.incoming.name,
                            colorHex = gc.incoming.colorHex,
                        )
                    )
                    genresImportedCount++
                }
            }

            val now = System.currentTimeMillis()
            for (incoming in plan.newAnimes) {
                val targetId = incoming.localId.ifBlank { UUID.randomUUID().toString() }
                val safeLocalId = if (dao.getByLocalId(targetId) == null) {
                    targetId
                } else {
                    UUID.randomUUID().toString()
                }
                dao.upsertAnime(incoming.toEntity(safeLocalId, now, now))
                val genreIds = resolveOrCreateGenreIds(incoming.genreNames, allowCreateCustom = true)
                dao.setGenresForAnime(safeLocalId, genreIds)
                importedCount++
            }

            for (conflict in plan.animeConflicts) {
                if (conflict.conflictId in acceptedAnimeConflictIds) {
                    val existingEntity = conflict.existing.anime
                    val updated = conflict.incoming.toEntity(
                        localId = existingEntity.localId,
                        createdAt = existingEntity.createdAt,
                        updatedAt = maxOf(conflict.incoming.updatedAt, now),
                    ).copy(
                        anilistId = conflict.incoming.anilistId ?: existingEntity.anilistId,
                        localCoverPath = existingEntity.localCoverPath,
                    )
                    dao.upsertAnime(updated)
                    val genreIds = resolveOrCreateGenreIds(conflict.incoming.genreNames, allowCreateCustom = true)
                    dao.setGenresForAnime(existingEntity.localId, genreIds)
                    updatedCount++
                } else {
                    skippedCount++
                }
            }
        }

        if (restorePreferences && plan.preferences != null) {
            applyPreferencesSnapshot(plan.preferences)
        }
        _stagedRestorePlan.value = null

        val summary = RestoreSummary(
            importedCount = importedCount,
            updatedCount = updatedCount,
            skippedCount = skippedCount,
            genresImportedCount = genresImportedCount,
            quarantinedItems = plan.quarantinedItems,
            isChecksumMismatch = plan.isChecksumMismatch,
        )
        _lastRestoreSummary.value = summary
        summary
    }

    /**
     * Resolves effective per-anime audio track, subtitles enabled state, censorship version, and resume position,
     * falling back to Global Settings (`JAPANESE`, `English Subtitles ON`, `UNCENSORED`) for new anime.
     */
    suspend fun getEffectivePlaybackPrefs(anilistId: Int): AnimePlaybackPrefsEntity =
        withContext(Dispatchers.IO) {
            val saved = dao.getPlaybackPrefs(anilistId)
            val defaultCensorship = preferencesRepository.defaultCensorshipVersion.firstOrNull()
                ?: CENSORSHIP_UNCENSORED
            if (saved != null) {
                return@withContext saved.copy(
                    censorshipVersion = saved.censorshipVersion ?: defaultCensorship,
                )
            }
            val defaultAudio = preferencesRepository.defaultAudioTrack.firstOrNull() ?: AUDIO_TRACK_JAPANESE
            val defaultSubs = preferencesRepository.defaultSubtitlesEnabled.firstOrNull() ?: true
            AnimePlaybackPrefsEntity(
                anilistId = anilistId,
                audioTrack = defaultAudio,
                subtitlesEnabled = defaultSubs,
                preferredQualityOverride = null,
                lastEpisode = null,
                lastPositionMs = 0L,
                censorshipVersion = defaultCensorship,
                updatedAt = System.currentTimeMillis(),
            )
        }

    /** Streams per-anime playback prefs or null if unset (so UI can merge with global defaults). */
    fun observePlaybackPrefs(anilistId: Int): Flow<AnimePlaybackPrefsEntity?> =
        dao.observePlaybackPrefs(anilistId)

    /** Saves per-anime audio track, subtitles flag, and optional quality/episode/position/censorship memory. */
    suspend fun savePlaybackPrefs(
        anilistId: Int,
        audioTrack: String,
        subtitlesEnabled: Boolean,
        preferredQualityOverride: String? = null,
        lastEpisode: String? = null,
        lastPositionMs: Long? = null,
        censorshipVersion: String? = null,
    ) = withContext(Dispatchers.IO) {
        val current = dao.getPlaybackPrefs(anilistId)
        dao.upsertPlaybackPrefs(
            AnimePlaybackPrefsEntity(
                anilistId = anilistId,
                audioTrack = audioTrack,
                subtitlesEnabled = subtitlesEnabled,
                preferredQualityOverride = preferredQualityOverride ?: current?.preferredQualityOverride,
                lastEpisode = lastEpisode ?: current?.lastEpisode,
                lastPositionMs = lastPositionMs ?: current?.lastPositionMs ?: 0L,
                censorshipVersion = censorshipVersion ?: current?.censorshipVersion,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /** Records an anime as recently opened/streamed in watch history without marking the episode completed. */
    suspend fun recordWatchHistory(
        anilistId: Int,
        title: String,
        coverUrl: String?,
        episodeWatched: String,
        providerId: String? = null,
        malId: Int? = null,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = dao.getWatchHistoryById(anilistId)
        dao.upsertWatchHistory(
            WatchHistoryEntity(
                anilistId = anilistId,
                title = title,
                coverUrl = coverUrl ?: existing?.coverUrl,
                lastEpisodeWatched = episodeWatched,
                lastWatchedAt = now,
                providerId = providerId ?: existing?.providerId,
                malId = malId ?: existing?.malId,
            )
        )
    }

    /** Streams the set of watched episode numbers for [anilistId], backed by local storage and memory. */
    fun observeWatchedEpisodes(anilistId: Int): Flow<Set<String>> =
        dao.observeWatchedEpisodes(anilistId).map { list ->
            val set = list.toSet()
            watchedEpisodesMemory[anilistId] = set
            set
        }

    /** Returns the saved playback resume position in ms for a specific [episodeNumber], or 0 if none. */
    suspend fun getEpisodeResumePositionMs(anilistId: Int, episodeNumber: String): Long =
        withContext(Dispatchers.IO) {
            dao.getWatchedEpisodeRecord(anilistId, episodeNumber)?.lastPositionMs ?: 0L
        }

    /**
     * Accumulates [activeDeltaMs] of real active playback time for [episodeNumber] and marks it watched
     * only once accumulated active watch time reaches at least 65% of [durationMs], while always
     * persisting per-episode [lastPositionMs] resume position when supplied.
     */
    suspend fun recordActiveWatchProgress(
        anilistId: Int,
        episodeNumber: String,
        activeDeltaMs: Long,
        durationMs: Long,
        animeTitle: String? = null,
        coverUrl: String? = null,
        lastPositionMs: Long? = null,
    ) = withContext(Dispatchers.IO) {
        if (activeDeltaMs <= 0L && durationMs <= 0L && (lastPositionMs ?: 0L) <= 0L) return@withContext
        val existing = dao.getWatchedEpisodeRecord(anilistId, episodeNumber)
        val newActiveWatchMs = (existing?.activeWatchMs ?: 0L) + activeDeltaMs.coerceAtLeast(0L)
        val effectiveDurationMs = if (durationMs > 0L) durationMs else (existing?.durationMs ?: 0L)
        val reachedThreshold = hasReachedWatchedThreshold(newActiveWatchMs, effectiveDurationMs)
        val newIsWatched = (existing?.isWatched == true) || reachedThreshold
        val resolvedPositionMs = lastPositionMs?.coerceAtLeast(0L) ?: existing?.lastPositionMs ?: 0L
        dao.upsertWatchedEpisode(
            WatchedEpisodeEntity(
                anilistId = anilistId,
                episodeNumber = episodeNumber,
                activeWatchMs = newActiveWatchMs,
                durationMs = effectiveDurationMs,
                isWatched = newIsWatched,
                lastPositionMs = resolvedPositionMs,
                watchedAt = System.currentTimeMillis(),
            )
        )
        if (newIsWatched) {
            watchedEpisodesMemory[anilistId] = watchedEpisodesMemory[anilistId].orEmpty() + episodeNumber
        }
        if (reachedThreshold && !animeTitle.isNullOrBlank()) {
            val autoAdd = preferencesRepository.autoAddWatchedToMyList.firstOrNull() ?: true
            if (autoAdd) {
                addOrUpdateFromAnilist(
                    anilistId = anilistId,
                    titleEnglish = animeTitle,
                    titleRomaji = null,
                    titleNative = null,
                    coverUrl = coverUrl,
                    totalEpisodes = null,
                    genreNames = emptyList(),
                    status = OfflineWatchStatus.WATCHING,
                )
            }
        }
    }

    /** Manually marks [episodeNumber] as watched for [anilistId] in both memory and local storage. */
    suspend fun markEpisodeWatched(anilistId: Int, episodeNumber: String) = withContext(Dispatchers.IO) {
        val existing = dao.getWatchedEpisodeRecord(anilistId, episodeNumber)
        watchedEpisodesMemory[anilistId] = watchedEpisodesMemory[anilistId].orEmpty() + episodeNumber
        dao.upsertWatchedEpisode(
            WatchedEpisodeEntity(
                anilistId = anilistId,
                episodeNumber = episodeNumber,
                activeWatchMs = existing?.activeWatchMs ?: 0L,
                durationMs = existing?.durationMs ?: 0L,
                isWatched = true,
                lastPositionMs = existing?.lastPositionMs ?: 0L,
                watchedAt = System.currentTimeMillis(),
            )
        )
    }

    /** Unmarks [episodeNumber] as watched for [anilistId] and resets its accumulated watch progress. */
    suspend fun unmarkEpisodeWatched(anilistId: Int, episodeNumber: String) = withContext(Dispatchers.IO) {
        watchedEpisodesMemory[anilistId] = watchedEpisodesMemory[anilistId].orEmpty() - episodeNumber
        val existing = dao.getWatchedEpisodeRecord(anilistId, episodeNumber)
        if (existing != null && existing.lastPositionMs > 0L) {
            dao.upsertWatchedEpisode(
                existing.copy(
                    activeWatchMs = 0L,
                    isWatched = false,
                    watchedAt = System.currentTimeMillis(),
                )
            )
        } else {
            dao.deleteWatchedEpisode(anilistId, episodeNumber)
        }
    }

    companion object {
        /** Fraction of total episode duration that must be actively watched to auto-mark watched. */
        const val WATCHED_THRESHOLD_FRACTION = 0.65

        /** Returns true when [activeWatchMs] is at least 65% of a valid [durationMs]. */
        fun hasReachedWatchedThreshold(activeWatchMs: Long, durationMs: Long): Boolean =
            durationMs > 0L && activeWatchMs >= (durationMs * WATCHED_THRESHOLD_FRACTION).toLong()
    }

    /** Streams all watch history entries ordered most recently watched first. */
    fun observeWatchHistory(): Flow<List<WatchHistoryEntity>> = dao.observeWatchHistory()

    /** Deletes a single entry from watch history. */
    suspend fun removeWatchHistoryEntry(anilistId: Int) = withContext(Dispatchers.IO) {
        dao.deleteWatchHistory(anilistId)
    }

    /** Clears all entries from watch history. */
    suspend fun clearWatchHistory() = withContext(Dispatchers.IO) {
        dao.clearWatchHistory()
    }

    private suspend fun resolveOrCreateGenreIds(
        names: List<String>,
        allowCreateCustom: Boolean,
    ): List<String> {
        if (names.isEmpty()) return emptyList()
        val allGenres = dao.getAllGenresOnce().toMutableList()
        val resolvedIds = ArrayList<String>()
        for (raw in names) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            val existing = allGenres.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
            if (existing != null) {
                resolvedIds.add(existing.id)
            } else if (allowCreateCustom) {
                val created = GenreEntity(
                    id = "custom_${UUID.randomUUID()}",
                    name = trimmed,
                    isCustom = true,
                    colorHex = DefaultGenres.customColorPresets.random(),
                )
                dao.upsertGenre(created)
                allGenres.add(created)
                resolvedIds.add(created.id)
            }
        }
        return resolvedIds
    }

    private suspend fun capturePreferencesSnapshot(): BackupPreferencesSnapshot {
        return BackupPreferencesSnapshot(
            titleLanguage = preferencesRepository.language.firstOrNull(),
            personalListColumns = preferencesRepository.personalListColumns.firstOrNull(),
            mangaRelationsSortOrder = preferencesRepository.mangaRelationsSortOrder.firstOrNull(),
            defaultStreamQuality = preferencesRepository.defaultStreamQuality.firstOrNull(),
            defaultAudioTrack = preferencesRepository.defaultAudioTrack.firstOrNull(),
            defaultSubtitlesEnabled = preferencesRepository.defaultSubtitlesEnabled.firstOrNull(),
            preferredPlayer = preferencesRepository.preferredPlayer.firstOrNull(),
            preBufferMaxMb = preferencesRepository.preBufferMaxMb.firstOrNull(),
            notificationsEnabled = preferencesRepository.notificationsEnabled.firstOrNull(),
        )
    }

    private suspend fun applyPreferencesSnapshot(prefs: BackupPreferencesSnapshot) {
        prefs.titleLanguage?.let { preferencesRepository.setLanguage(it) }
        prefs.personalListColumns?.let { preferencesRepository.setPersonalListColumns(it) }
        prefs.mangaRelationsSortOrder?.let { preferencesRepository.setMangaRelationsSortOrder(it) }
        prefs.defaultStreamQuality?.let { preferencesRepository.setDefaultStreamQuality(it) }
        prefs.defaultAudioTrack?.let { preferencesRepository.setDefaultAudioTrack(it) }
        prefs.defaultSubtitlesEnabled?.let { preferencesRepository.setDefaultSubtitlesEnabled(it) }
        prefs.preferredPlayer?.let { preferencesRepository.setPreferredPlayer(it) }
        prefs.preBufferMaxMb?.let { preferencesRepository.setPreBufferMaxMb(it) }
        prefs.notificationsEnabled?.let { preferencesRepository.setNotificationsEnabled(it) }
    }

    private fun BackupAnimeEntry.toEntity(
        localId: String,
        createdAt: Long,
        updatedAt: Long,
    ): OfflineAnimeEntity = OfflineAnimeEntity(
        localId = localId,
        anilistId = anilistId,
        titleEnglish = AnimeTitleNormalizer.displayWholeTitle(titleEnglish),
        titleRomaji = AnimeTitleNormalizer.displayWholeTitle(titleRomaji),
        titleNative = titleNative?.trim()?.takeIf { it.isNotEmpty() },
        coverUrl = coverUrl,
        localCoverPath = null,
        status = OfflineWatchStatus.safeParse(status).name,
        episodesWatched = episodesWatched,
        totalEpisodes = totalEpisodes,
        score = score,
        notes = notes,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
