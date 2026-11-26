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
import kotlinx.coroutines.withContext
import java.util.UUID
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

    /** Adds or updates an anime from an AniList detail page into the local offline list. */
    suspend fun addOrUpdateFromAnilist(
        anilistId: Int,
        titleEnglish: String?,
        titleRomaji: String?,
        titleNative: String?,
        coverUrl: String?,
        totalEpisodes: Int?,
        genreNames: List<String>,
        status: OfflineWatchStatus = OfflineWatchStatus.PLAN_TO_WATCH,
    ) = withContext(Dispatchers.IO) {
        ensureDefaultGenresSeeded()
        val now = System.currentTimeMillis()
        val existing = dao.getByAnilistId(anilistId)
        val localId = existing?.localId ?: UUID.randomUUID().toString()
        val entity = OfflineAnimeEntity(
            localId = localId,
            anilistId = anilistId,
            titleEnglish = titleEnglish ?: existing?.titleEnglish,
            titleRomaji = titleRomaji ?: existing?.titleRomaji,
            titleNative = titleNative ?: existing?.titleNative,
            coverUrl = coverUrl ?: existing?.coverUrl,
            localCoverPath = existing?.localCoverPath,
            status = existing?.status ?: status.name,
            episodesWatched = existing?.episodesWatched ?: 0,
            totalEpisodes = totalEpisodes ?: existing?.totalEpisodes,
            score = existing?.score,
            notes = existing?.notes.orEmpty(),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        database.withTransaction {
            dao.upsertAnime(entity)
            if (existing == null) {
                val genreIds = resolveOrCreateGenreIds(genreNames, allowCreateCustom = false)
                dao.setGenresForAnime(localId, genreIds)
            }
        }
    }

    /** Removes an AniList-linked anime from the offline personal list. */
    suspend fun removeByAnilistId(anilistId: Int) = withContext(Dispatchers.IO) {
        dao.deleteByAnilistId(anilistId)
    }

    /** Saves a manual or edited offline anime entry along with its assigned genres atomically. */
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
    ) = withContext(Dispatchers.IO) {
        ensureDefaultGenresSeeded()
        val now = System.currentTimeMillis()
        val existing = localId?.let { dao.getByLocalId(it) }
        val resolvedId = existing?.localId ?: UUID.randomUUID().toString()
        val entity = OfflineAnimeEntity(
            localId = resolvedId,
            anilistId = anilistId ?: existing?.anilistId,
            titleEnglish = titleEnglish?.trim()?.takeIf { it.isNotEmpty() },
            titleRomaji = titleRomaji?.trim()?.takeIf { it.isNotEmpty() },
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
    }

    /** Deletes an offline anime entry by [localId]. */
    suspend fun deleteOfflineAnime(localId: String) = withContext(Dispatchers.IO) {
        dao.deleteAnime(localId)
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

    /** Records an anime as streamed in watch history so release notifications and History UI display it. */
    suspend fun recordWatchHistory(
        anilistId: Int,
        title: String,
        coverUrl: String?,
        episodeWatched: String,
        providerId: String? = null,
        malId: Int? = null,
    ) = withContext(Dispatchers.IO) {
        val existing = dao.getWatchHistoryById(anilistId)
        dao.upsertWatchHistory(
            WatchHistoryEntity(
                anilistId = anilistId,
                title = title,
                coverUrl = coverUrl ?: existing?.coverUrl,
                lastEpisodeWatched = episodeWatched,
                lastWatchedAt = System.currentTimeMillis(),
                providerId = providerId ?: existing?.providerId,
                malId = malId ?: existing?.malId,
            )
        )
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
        titleEnglish = titleEnglish,
        titleRomaji = titleRomaji,
        titleNative = titleNative,
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
