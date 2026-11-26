package com.anigooli.library

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Data access object for offline anime list, custom genres, playback memory, watch history, and release tracking. */
@Dao
@Suppress("TooManyFunctions")
interface LibraryDao {

    // region Offline Anime List & Genres

    /** Streams all offline anime entries together with their assigned genres. */
    @Transaction
    @Query("SELECT * FROM offline_anime ORDER BY updatedAt DESC")
    fun observeAllAnimeWithGenres(): Flow<List<AnimeWithGenres>>

    /** Streams whether an AniList ID exists in the offline list. */
    @Query("SELECT EXISTS(SELECT 1 FROM offline_anime WHERE anilistId = :anilistId)")
    fun observeExistsByAnilistId(anilistId: Int): Flow<Boolean>

    /** Fetches all offline anime entries once with their genres. */
    @Transaction
    @Query("SELECT * FROM offline_anime")
    suspend fun getAllAnimeWithGenresOnce(): List<AnimeWithGenres>

    /** Fetches an offline anime entry by AniList ID if present. */
    @Query("SELECT * FROM offline_anime WHERE anilistId = :anilistId LIMIT 1")
    suspend fun getByAnilistId(anilistId: Int): OfflineAnimeEntity?

    /** Fetches an offline anime entry by local UUID if present. */
    @Query("SELECT * FROM offline_anime WHERE localId = :localId LIMIT 1")
    suspend fun getByLocalId(localId: String): OfflineAnimeEntity?

    /** Inserts or replaces an offline anime entry. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAnime(entity: OfflineAnimeEntity)

    /** Deletes an offline anime entry by local ID (cascades genre links). */
    @Query("DELETE FROM offline_anime WHERE localId = :localId")
    suspend fun deleteAnime(localId: String)

    /** Deletes an offline anime entry by AniList ID. */
    @Query("DELETE FROM offline_anime WHERE anilistId = :anilistId")
    suspend fun deleteByAnilistId(anilistId: Int)

    /** Streams all genres (defaults + custom), with custom genres and alphabetical ordering. */
    @Query("SELECT * FROM genres ORDER BY isCustom DESC, name COLLATE NOCASE ASC")
    fun observeAllGenres(): Flow<List<GenreEntity>>

    /** Fetches all genres once. */
    @Query("SELECT * FROM genres")
    suspend fun getAllGenresOnce(): List<GenreEntity>

    /** Counts how many anime entries currently reference [genreId]. */
    @Query("SELECT COUNT(*) FROM anime_genre_cross_ref WHERE genreId = :genreId")
    suspend fun countAnimeForGenre(genreId: String): Int

    /** Inserts default genres without overwriting existing rows. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGenresIgnore(genres: List<GenreEntity>)

    /** Inserts or updates a genre. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGenre(genre: GenreEntity)

    /** Deletes a custom genre (cascades only cross-ref tag links, leaving anime entries intact). */
    @Query("DELETE FROM genres WHERE id = :genreId AND isCustom = 1")
    suspend fun deleteCustomGenre(genreId: String)

    /** Inserts genre cross-refs ignoring duplicates. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRefs(refs: List<AnimeGenreCrossRef>)

    /** Removes all genre cross-refs for a single anime entry. */
    @Query("DELETE FROM anime_genre_cross_ref WHERE localId = :localId")
    suspend fun clearCrossRefsForAnime(localId: String)

    /** Replaces the complete set of genre links for [localId] atomically. */
    @Transaction
    suspend fun setGenresForAnime(localId: String, genreIds: List<String>) {
        clearCrossRefsForAnime(localId)
        if (genreIds.isNotEmpty()) {
            insertCrossRefs(genreIds.distinct().map { AnimeGenreCrossRef(localId, it) })
        }
    }

    // endregion

    // region Per-Anime Audio / Subtitle Memory & Watch History

    /** Observes per-anime playback preferences for [anilistId]. */
    @Query("SELECT * FROM anime_playback_prefs WHERE anilistId = :anilistId LIMIT 1")
    fun observePlaybackPrefs(anilistId: Int): Flow<AnimePlaybackPrefsEntity?>

    /** Fetches per-anime playback preferences once. */
    @Query("SELECT * FROM anime_playback_prefs WHERE anilistId = :anilistId LIMIT 1")
    suspend fun getPlaybackPrefs(anilistId: Int): AnimePlaybackPrefsEntity?

    /** Saves or updates per-anime playback preferences. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaybackPrefs(prefs: AnimePlaybackPrefsEntity)

    /** Records or updates watch history for [WatchHistoryEntity]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWatchHistory(entry: WatchHistoryEntity)

    /** Fetches all streamed anime entries in watch history. */
    @Query("SELECT * FROM watch_history ORDER BY lastWatchedAt DESC")
    suspend fun getAllWatchHistoryOnce(): List<WatchHistoryEntity>

    /** Fetches a single watch history entry by [anilistId]. */
    @Query("SELECT * FROM watch_history WHERE anilistId = :anilistId LIMIT 1")
    suspend fun getWatchHistoryById(anilistId: Int): WatchHistoryEntity?

    /** Streams all watch history entries ordered most recently watched first. */
    @Query("SELECT * FROM watch_history ORDER BY lastWatchedAt DESC")
    fun observeWatchHistory(): Flow<List<WatchHistoryEntity>>

    /** Deletes a single watch history entry by [anilistId]. */
    @Query("DELETE FROM watch_history WHERE anilistId = :anilistId")
    suspend fun deleteWatchHistory(anilistId: Int)

    /** Clears all watch history entries. */
    @Query("DELETE FROM watch_history")
    suspend fun clearWatchHistory()

    // endregion

    // region Release Notification Tracker

    /** Fetches stored release tracker snapshot for [anilistId]. */
    @Query("SELECT * FROM release_tracker WHERE anilistId = :anilistId LIMIT 1")
    suspend fun getReleaseTracker(anilistId: Int): ReleaseTrackerEntity?

    /** Saves or updates release tracker snapshot. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReleaseTracker(tracker: ReleaseTrackerEntity)

    /** Fetches all distinct AniList IDs from offline anime list and watch history for release checks. */
    @Query(
        "SELECT anilistId FROM offline_anime WHERE anilistId IS NOT NULL AND anilistId > 0 " +
            "UNION SELECT anilistId FROM watch_history WHERE anilistId > 0"
    )
    suspend fun getAllTrackedAnilistIds(): List<Int>

    // endregion
}

/** Room database storing offline anime list, custom genres, per-anime playback memory, watch history, and release snapshots. */
@Database(
    entities = [
        OfflineAnimeEntity::class,
        GenreEntity::class,
        AnimeGenreCrossRef::class,
        AnimePlaybackPrefsEntity::class,
        WatchHistoryEntity::class,
        ReleaseTrackerEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class LibraryDatabase : RoomDatabase() {
    /** Exposes [LibraryDao]. */
    abstract fun libraryDao(): LibraryDao
}
