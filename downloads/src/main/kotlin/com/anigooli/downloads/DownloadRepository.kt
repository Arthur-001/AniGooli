package com.anigooli.downloads

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.anigooli.streaming.AudioTrack
import com.anigooli.streaming.StreamSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Repository managing episode downloads, WorkManager scheduling, and local file cleanup. */
@Singleton
class DownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao,
) {
    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    /** Streams all parent anime entries with downloaded or queued episodes. */
    fun observeDownloadedAnime(): Flow<List<DownloadedAnimeEntity>> =
        downloadDao.observeDownloadedAnime()

    /** Streams all download episode records. */
    fun observeAllEpisodes(): Flow<List<DownloadEpisodeEntity>> =
        downloadDao.observeAllEpisodes()

    /** Streams download records for a single [anilistId]. */
    fun observeEpisodesForAnime(anilistId: Int): Flow<List<DownloadEpisodeEntity>> =
        downloadDao.observeEpisodesForAnime(anilistId)

    /** Enqueues or starts downloading [episodeNumber] using [source], reusing pre-buffered segments. */
    suspend fun enqueueDownload(
        anilistId: Int,
        animeTitle: String,
        coverUrl: String?,
        episodeNumber: String,
        audioTrack: AudioTrack,
        source: StreamSource,
    ) = withContext(Dispatchers.IO) {
        val id = episodeDownloadId(anilistId, episodeNumber)
        val animeDir = File(File(context.filesDir, "downloads"), anilistId.toString())
        downloadDao.upsertAnime(
            DownloadedAnimeEntity(
                anilistId = anilistId,
                title = animeTitle,
                coverUrl = coverUrl,
                folderPath = animeDir.absolutePath,
                updatedAt = System.currentTimeMillis(),
            )
        )
        downloadDao.upsertEpisode(
            DownloadEpisodeEntity(
                id = id,
                anilistId = anilistId,
                animeTitle = animeTitle,
                episodeNumber = episodeNumber,
                qualityLabel = source.qualityLabel,
                audioTrack = audioTrack.name,
                status = DownloadStatus.QUEUED.name,
                bytesDownloaded = 0L,
                totalBytes = 0L,
                completedSegments = 0,
                totalSegments = 0,
                progressPercent = 0,
                videoFilePath = null,
                subtitleFilePath = null,
                streamUrl = source.url,
                referer = source.referer,
                origin = source.origin,
                isHls = source.isHls,
                audioUrl = source.audioUrl,
                subtitleUrl = source.subtitleUrl,
                errorMessage = null,
                createdAt = System.currentTimeMillis(),
            )
        )
        scheduleWorker(id)
    }

    /** Pauses an active download while preserving partial segments/chunks on disk for resume. */
    suspend fun pauseDownload(episodeId: String) = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork(workName(episodeId))
        downloadDao.updateStatus(episodeId, DownloadStatus.PAUSED.name, null)
    }

    /** Resumes or retries a paused/failed download from its saved checkpoint. */
    suspend fun resumeDownload(episodeId: String) = withContext(Dispatchers.IO) {
        downloadDao.updateStatus(episodeId, DownloadStatus.QUEUED.name, null)
        scheduleWorker(episodeId)
    }

    /** Deletes a single episode's files from device storage and removes its database entry. */
    suspend fun deleteEpisode(episodeId: String) = withContext(Dispatchers.IO) {
        workManager.cancelUniqueWork(workName(episodeId))
        val ep = downloadDao.getEpisodeById(episodeId) ?: return@withContext
        val safeEp = ep.episodeNumber.replace(Regex("""[^a-zA-Z0-9._-]"""), "_")
        val epDir = File(File(File(context.filesDir, "downloads"), ep.anilistId.toString()), "ep_$safeEp")
        epDir.deleteRecursively()
        ep.videoFilePath?.let { File(it).delete() }
        ep.subtitleFilePath?.let { File(it).delete() }
        downloadDao.deleteEpisode(episodeId)

        if (downloadDao.getEpisodesForAnime(ep.anilistId).isEmpty()) {
            File(File(context.filesDir, "downloads"), ep.anilistId.toString()).deleteRecursively()
            downloadDao.deleteAnime(ep.anilistId)
        }
    }

    /** Deletes all downloaded and queued episodes for [anilistId] from device storage. */
    suspend fun deleteAnime(anilistId: Int) = withContext(Dispatchers.IO) {
        val episodes = downloadDao.getEpisodesForAnime(anilistId)
        episodes.forEach { workManager.cancelUniqueWork(workName(it.id)) }
        File(File(context.filesDir, "downloads"), anilistId.toString()).deleteRecursively()
        downloadDao.deleteEpisodesForAnime(anilistId)
        downloadDao.deleteAnime(anilistId)
    }

    /** Returns total disk space used by all completed and partial downloads in bytes. */
    suspend fun totalDownloadedBytes(): Long = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "downloads")
        if (!root.exists()) return@withContext 0L
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun scheduleWorker(episodeId: String) {
        val request = OneTimeWorkRequestBuilder<EpisodeDownloadWorker>()
            .setInputData(workDataOf(EpisodeDownloadWorker.KEY_EPISODE_ID to episodeId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        workManager.enqueueUniqueWork(
            workName(episodeId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun workName(episodeId: String) = "anigooli_dl_$episodeId"

    companion object {
        /** Deterministic database primary key for an episode download. */
        fun episodeDownloadId(anilistId: Int, episodeNumber: String): String =
            "${anilistId}_${episodeNumber.trim()}"
    }
}
