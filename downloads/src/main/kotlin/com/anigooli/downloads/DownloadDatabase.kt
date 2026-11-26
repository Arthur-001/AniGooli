package com.anigooli.downloads

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Data access object for offline downloaded anime and episode tasks. */
@Dao
interface DownloadDao {
    /** Streams all anime entries that have download records, newest first. */
    @Query("SELECT * FROM downloaded_anime ORDER BY updatedAt DESC")
    fun observeDownloadedAnime(): Flow<List<DownloadedAnimeEntity>>

    /** Streams all episode download records across all anime. */
    @Query("SELECT * FROM download_episodes ORDER BY createdAt DESC")
    fun observeAllEpisodes(): Flow<List<DownloadEpisodeEntity>>

    /** Streams episode download records belonging to [anilistId]. */
    @Query("SELECT * FROM download_episodes WHERE anilistId = :anilistId ORDER BY createdAt ASC")
    fun observeEpisodesForAnime(anilistId: Int): Flow<List<DownloadEpisodeEntity>>

    /** Fetches a single download episode by its [id]. */
    @Query("SELECT * FROM download_episodes WHERE id = :id LIMIT 1")
    suspend fun getEpisodeById(id: String): DownloadEpisodeEntity?

    /** Fetches all download episodes for [anilistId]. */
    @Query("SELECT * FROM download_episodes WHERE anilistId = :anilistId")
    suspend fun getEpisodesForAnime(anilistId: Int): List<DownloadEpisodeEntity>

    /** Inserts or replaces a parent anime record. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAnime(anime: DownloadedAnimeEntity)

    /** Inserts or replaces an episode download record. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEpisode(episode: DownloadEpisodeEntity)

    /** Updates download progress fields atomically. */
    @Query(
        "UPDATE download_episodes SET status = :status, bytesDownloaded = :bytesDownloaded, " +
            "totalBytes = :totalBytes, completedSegments = :completedSegments, " +
            "totalSegments = :totalSegments, progressPercent = :progressPercent, " +
            "errorMessage = :errorMessage WHERE id = :id"
    )
    suspend fun updateProgress(
        id: String,
        status: String,
        bytesDownloaded: Long,
        totalBytes: Long,
        completedSegments: Int,
        totalSegments: Int,
        progressPercent: Int,
        errorMessage: String? = null,
    )

    /** Updates status and optional error message for an episode. */
    @Query("UPDATE download_episodes SET status = :status, errorMessage = :errorMessage WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, errorMessage: String? = null)

    /** Marks a download as completed with final playable file paths. */
    @Query(
        "UPDATE download_episodes SET status = 'COMPLETED', progressPercent = 100, " +
            "bytesDownloaded = :finalBytes, totalBytes = :finalBytes, " +
            "videoFilePath = :videoFilePath, subtitleFilePath = :subtitleFilePath, " +
            "errorMessage = NULL WHERE id = :id"
    )
    suspend fun markCompleted(
        id: String,
        finalBytes: Long,
        videoFilePath: String,
        subtitleFilePath: String?,
    )

    /** Deletes a single episode record. */
    @Query("DELETE FROM download_episodes WHERE id = :id")
    suspend fun deleteEpisode(id: String)

    /** Deletes all episode records for [anilistId]. */
    @Query("DELETE FROM download_episodes WHERE anilistId = :anilistId")
    suspend fun deleteEpisodesForAnime(anilistId: Int)

    /** Deletes a parent anime record. */
    @Query("DELETE FROM downloaded_anime WHERE anilistId = :anilistId")
    suspend fun deleteAnime(anilistId: Int)
}

/** Room database backing AniGooli's downloads manager. */
@Database(
    entities = [DownloadedAnimeEntity::class, DownloadEpisodeEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class DownloadDatabase : RoomDatabase() {
    /** Exposes [DownloadDao]. */
    abstract fun downloadDao(): DownloadDao
}
