package com.anigooli.downloads

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Persistent status of a downloaded or queued episode. */
enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
}

/** Room entity grouping downloaded episodes by anime. */
@Entity(tableName = "downloaded_anime")
data class DownloadedAnimeEntity(
    /** AniList ID of the anime (or negative hash for custom entries). */
    @PrimaryKey val anilistId: Int,
    /** Display title of the anime. */
    val title: String,
    /** Cover image URL if available. */
    val coverUrl: String?,
    /** Directory path where episodes of this anime are saved. */
    val folderPath: String,
    /** Last update timestamp in epoch milliseconds. */
    val updatedAt: Long,
)

/** Room entity tracking download progress, pause/resume checkpoints, and final file paths for an episode. */
@Entity(tableName = "download_episodes")
data class DownloadEpisodeEntity(
    /** Deterministic ID formatted as "<anilistId>_<episodeNumber>". */
    @PrimaryKey val id: String,
    /** Parent AniList anime ID. */
    val anilistId: Int,
    /** Display title of the anime. */
    val animeTitle: String,
    /** Episode identifier string (e.g. "1", "12.5"). */
    val episodeNumber: String,
    /** Quality label (e.g. "1080p", "720p"). */
    val qualityLabel: String,
    /** Audio track name ("JAPANESE" or "ENGLISH_DUB"). */
    val audioTrack: String,
    /** Current download status name ([DownloadStatus.name]). */
    val status: String,
    /** Bytes written to local storage so far. */
    val bytesDownloaded: Long,
    /** Estimated or actual total byte size. */
    val totalBytes: Long,
    /** Count of segments completed so far (for HLS). */
    val completedSegments: Int,
    /** Total segment count (for HLS), or 0 for single-file MP4. */
    val totalSegments: Int,
    /** Completion percentage from 0 to 100. */
    val progressPercent: Int,
    /** Final playable video file path when completed. */
    val videoFilePath: String?,
    /** Local English .vtt subtitle file path if available. */
    val subtitleFilePath: String?,
    /** Stream URL used for downloading/resuming. */
    val streamUrl: String,
    /** Optional HTTP Referer header. */
    val referer: String,
    /** Optional HTTP Origin header. */
    val origin: String,
    /** True if [streamUrl] is an HLS playlist. */
    val isHls: Boolean,
    /** Optional separate HLS audio track URL. */
    val audioUrl: String?,
    /** Optional remote English subtitle URL. */
    val subtitleUrl: String?,
    /** Error summary when status is [DownloadStatus.FAILED]. */
    val errorMessage: String?,
    /** Creation timestamp in epoch milliseconds. */
    val createdAt: Long,
)
