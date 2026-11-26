package com.anigooli.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

private const val TAG = "EpisodeDownloadWorker"
private const val CHANNEL_ID = "anigooli_downloads"
private const val PROGRESS_MAX = 100
private const val BUFFER_BYTES = 64 * 1024
private const val MUXER_BUFFER_BYTES = 1024 * 1024
private const val PROGRESS_UPDATE_STEP = 4
private const val HTTP_OK = 200
private const val HTTP_PARTIAL = 206
private const val SEGMENT_PAD_WIDTH = 5

/**
 * WorkManager worker that downloads full episodes to local storage, reusing any already
 * pre-buffered segments or MP4 prefix bytes from [PreBufferManager] without loading large
 * videos into RAM. Supports pause, resume, subtitles, and native audio+video muxing.
 */
@HiltWorker
class EpisodeDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val downloadDao: DownloadDao,
    private val preBufferManager: PreBufferManager,
    private val okHttpClient: OkHttpClient,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val episodeId = inputData.getString(KEY_EPISODE_ID) ?: return@withContext Result.failure()
        val entity = downloadDao.getEpisodeById(episodeId) ?: return@withContext Result.failure()

        ensureChannel()
        val notifId = episodeId.hashCode() and Int.MAX_VALUE
        setForeground(createForegroundInfo(notifId, entity.animeTitle, entity.episodeNumber, entity.progressPercent))

        val animeDir = File(File(applicationContext.filesDir, "downloads"), entity.anilistId.toString())
        val epDir = File(animeDir, "ep_${sanitizeFileSegment(entity.episodeNumber)}").apply { mkdirs() }
        val workDir = File(epDir, "work").apply { mkdirs() }

        downloadDao.upsertAnime(
            DownloadedAnimeEntity(
                anilistId = entity.anilistId,
                title = entity.animeTitle,
                coverUrl = null,
                folderPath = animeDir.absolutePath,
                updatedAt = System.currentTimeMillis(),
            )
        )
        downloadDao.updateStatus(episodeId, DownloadStatus.DOWNLOADING.name, null)

        val slotKey = PreBufferPolicy.slotKey(
            entity.anilistId,
            entity.episodeNumber,
            entity.qualityLabel,
            entity.audioTrack,
        )
        val preBufferExport = preBufferManager.exportToDownload(slotKey, entity.streamUrl, workDir)

        return@withContext runCatching {
            val videoFile = if (entity.isHls) {
                downloadHlsEpisode(entity, epDir, workDir, preBufferExport, notifId)
            } else {
                downloadMp4Episode(entity, epDir, workDir, preBufferExport, notifId)
            }

            if (isStopped) {
                downloadDao.updateStatus(episodeId, DownloadStatus.PAUSED.name, null)
                return@runCatching Result.success()
            }

            val subFile = downloadSubtitleIfPresent(entity, epDir)
            val finalSize = videoFile.length()
            downloadDao.markCompleted(
                id = episodeId,
                finalBytes = finalSize,
                videoFilePath = videoFile.absolutePath,
                subtitleFilePath = subFile?.absolutePath,
            )
            workDir.deleteRecursively()
            Result.success()
        }.getOrElse { err ->
            if (isStopped) {
                downloadDao.updateStatus(episodeId, DownloadStatus.PAUSED.name, null)
                Result.success()
            } else {
                Log.e(TAG, "Download failed for $episodeId: ${err.message}", err)
                downloadDao.updateStatus(episodeId, DownloadStatus.FAILED.name, err.message ?: "Download error")
                Result.failure()
            }
        }
    }

    private suspend fun downloadHlsEpisode(
        entity: DownloadEpisodeEntity,
        epDir: File,
        workDir: File,
        preBufferExport: PreBufferExportResult,
        notifId: Int,
    ): File {
        val playlistText = fetchText(entity.streamUrl, entity.referer, entity.origin)
            ?: error("Unable to fetch HLS playlist")
        var parsed = HlsPlaylistParser.parseMediaPlaylist(playlistText, entity.streamUrl)
        if (parsed.isMasterPlaylist && parsed.segmentUrls.isNotEmpty()) {
            val subPlaylistUrl = parsed.segmentUrls.first()
            val subText = fetchText(subPlaylistUrl, entity.referer, entity.origin)
                ?: error("Unable to fetch HLS variant playlist")
            parsed = HlsPlaylistParser.parseMediaPlaylist(subText, subPlaylistUrl)
        }
        val allSegments = buildList {
            parsed.initSegmentUrl?.let { add(it) }
            addAll(parsed.segmentUrls)
        }
        if (allSegments.isEmpty()) error("HLS playlist contains no media segments")

        val totalCount = allSegments.size
        val segFiles = ArrayList<File>(totalCount)
        var bytesWritten = 0L

        for ((index, segUrl) in allSegments.withIndex()) {
            if (isStopped || !currentCoroutineContext().isActive) break
            val targetSeg = File(workDir, "seg_${index.toString().padStart(SEGMENT_PAD_WIDTH, '0')}.bin")
            val preBufferedSeg = preBufferExport.exportedSegmentsByUrl[segUrl]
            if (!targetSeg.exists() || targetSeg.length() == 0L) {
                if (preBufferedSeg != null && preBufferedSeg.exists() && preBufferedSeg.length() > 0L) {
                    preBufferedSeg.renameTo(targetSeg)
                } else {
                    downloadBinary(segUrl, targetSeg, entity.referer, entity.origin)
                }
            }
            bytesWritten += targetSeg.length()
            segFiles.add(targetSeg)

            if (index % PROGRESS_UPDATE_STEP == 0 || index == totalCount - 1) {
                val pct = ((index + 1) * PROGRESS_MAX / totalCount).coerceIn(0, PROGRESS_MAX)
                val estTotal = (bytesWritten / (index + 1).toFloat() * totalCount).toLong()
                downloadDao.updateProgress(
                    id = entity.id,
                    status = DownloadStatus.DOWNLOADING.name,
                    bytesDownloaded = bytesWritten,
                    totalBytes = estTotal,
                    completedSegments = index + 1,
                    totalSegments = totalCount,
                    progressPercent = pct,
                )
                setForeground(createForegroundInfo(notifId, entity.animeTitle, entity.episodeNumber, pct))
            }
        }

        if (isStopped) return File(epDir, "paused.tmp")

        val videoOnlyFile = File(epDir, "episode_${sanitizeFileSegment(entity.episodeNumber)}.ts")
        assembleSegments(segFiles, videoOnlyFile)

        val audioUrl = entity.audioUrl
        if (!audioUrl.isNullOrBlank()) {
            val audioFile = downloadHlsAudio(audioUrl, workDir, epDir, entity.referer, entity.origin)
            if (audioFile != null) {
                val muxed = File(epDir, "episode_${sanitizeFileSegment(entity.episodeNumber)}.mp4")
                if (tryMuxVideoAndAudio(videoOnlyFile, audioFile, muxed)) {
                    videoOnlyFile.delete()
                    audioFile.delete()
                    return muxed
                }
            }
        }
        return videoOnlyFile
    }

    private suspend fun downloadMp4Episode(
        entity: DownloadEpisodeEntity,
        epDir: File,
        workDir: File,
        preBufferExport: PreBufferExportResult,
        notifId: Int,
    ): File {
        val partFile = File(workDir, "video.mp4.part")
        if (!partFile.exists() || partFile.length() == 0L) {
            preBufferExport.exportedMp4PartFile?.takeIf { it.exists() && it.length() > 0L }?.let {
                it.renameTo(partFile)
            }
        }

        var offset = if (partFile.exists()) partFile.length() else 0L
        val knownTotal = preBufferExport.mp4TotalBytes
        if (preBufferExport.isFullyPreBuffered && knownTotal > 0L && offset >= knownTotal) {
            val finalFile = File(epDir, "episode_${sanitizeFileSegment(entity.episodeNumber)}.mp4")
            partFile.copyTo(finalFile, overwrite = true)
            partFile.delete()
            return finalFile
        }

        val reqBuilder = Request.Builder().url(entity.streamUrl).get()
        if (offset > 0L) reqBuilder.header("Range", "bytes=$offset-")
        if (entity.referer.isNotBlank()) reqBuilder.header("Referer", entity.referer)
        if (entity.origin.isNotBlank()) reqBuilder.header("Origin", entity.origin)

        okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (resp.code != HTTP_OK && resp.code != HTTP_PARTIAL) {
                error("HTTP ${resp.code} while downloading MP4")
            }
            val body = resp.body ?: error("Empty MP4 response body")
            if (resp.code == HTTP_OK && offset > 0L) {
                offset = 0L
                partFile.delete()
            }
            val remainingLen = body.contentLength().coerceAtLeast(0L)
            val totalBytes = if (knownTotal > 0L) knownTotal else offset + remainingLen
            val buffer = ByteArray(BUFFER_BYTES)
            var lastReportedPct = -1

            FileOutputStream(partFile, offset > 0L).use { out ->
                body.byteStream().use { input ->
                    while (!isStopped && currentCoroutineContext().isActive) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        offset += read
                        val pct = if (totalBytes > 0L) {
                            (offset * PROGRESS_MAX / totalBytes).toInt().coerceIn(0, PROGRESS_MAX)
                        } else 0
                        if (pct != lastReportedPct) {
                            lastReportedPct = pct
                            downloadDao.updateProgress(
                                id = entity.id,
                                status = DownloadStatus.DOWNLOADING.name,
                                bytesDownloaded = offset,
                                totalBytes = totalBytes,
                                completedSegments = 0,
                                totalSegments = 0,
                                progressPercent = pct,
                            )
                            setForeground(createForegroundInfo(notifId, entity.animeTitle, entity.episodeNumber, pct))
                        }
                    }
                }
            }
        }

        if (isStopped) return partFile
        val finalFile = File(epDir, "episode_${sanitizeFileSegment(entity.episodeNumber)}.mp4")
        if (!partFile.renameTo(finalFile)) {
            partFile.copyTo(finalFile, overwrite = true)
            partFile.delete()
        }
        return finalFile
    }

    private fun downloadHlsAudio(
        audioPlaylistUrl: String,
        workDir: File,
        epDir: File,
        referer: String,
        origin: String,
    ): File? = runCatching {
        val text = fetchText(audioPlaylistUrl, referer, origin) ?: return null
        val parsed = HlsPlaylistParser.parseMediaPlaylist(text, audioPlaylistUrl)
        val segs = buildList {
            parsed.initSegmentUrl?.let { add(it) }
            addAll(parsed.segmentUrls)
        }
        if (segs.isEmpty()) return null
        val files = segs.mapIndexed { idx, u ->
            File(workDir, "aud_${idx.toString().padStart(SEGMENT_PAD_WIDTH, '0')}.bin").also { dst ->
                if (!dst.exists() || dst.length() == 0L) {
                    downloadBinary(u, dst, referer, origin)
                }
            }
        }
        val audioOut = File(epDir, "audio_track.ts")
        assembleSegments(files, audioOut)
        audioOut
    }.getOrNull()

    private fun tryMuxVideoAndAudio(videoFile: File, audioFile: File, outputFile: File): Boolean = runCatching {
        val videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
        val audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val vTrackIdx = findTrackIndex(videoExtractor, "video/") ?: return false
            val aTrackIdx = findTrackIndex(audioExtractor, "audio/") ?: return false
            videoExtractor.selectTrack(vTrackIdx)
            audioExtractor.selectTrack(aTrackIdx)
            val muxVTrack = muxer.addTrack(videoExtractor.getTrackFormat(vTrackIdx))
            val muxATrack = muxer.addTrack(audioExtractor.getTrackFormat(aTrackIdx))
            muxer.start()
            copySamples(videoExtractor, muxer, muxVTrack)
            copySamples(audioExtractor, muxer, muxATrack)
            muxer.stop()
            true
        } finally {
            muxer.release()
            videoExtractor.release()
            audioExtractor.release()
        }
    }.getOrDefault(false)

    private fun findTrackIndex(extractor: MediaExtractor, mimePrefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(mimePrefix)) return i
        }
        return null
    }

    private fun copySamples(extractor: MediaExtractor, muxer: MediaMuxer, muxTrackIndex: Int) {
        val buf = ByteBuffer.allocate(MUXER_BUFFER_BYTES)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val sampleSize = extractor.readSampleData(buf, 0)
            if (sampleSize < 0) break
            info.offset = 0
            info.size = sampleSize
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(muxTrackIndex, buf, info)
            extractor.advance()
        }
    }

    private fun assembleSegments(segFiles: List<File>, outputFile: File) {
        val tmp = File(outputFile.parentFile, "${outputFile.name}.assembling")
        val buf = ByteArray(BUFFER_BYTES)
        FileOutputStream(tmp).use { out ->
            for (seg in segFiles) {
                if (!seg.exists()) continue
                seg.inputStream().use { input ->
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                    }
                }
            }
        }
        if (!tmp.renameTo(outputFile)) {
            tmp.copyTo(outputFile, overwrite = true)
            tmp.delete()
        }
    }

    private fun downloadSubtitleIfPresent(entity: DownloadEpisodeEntity, epDir: File): File? {
        val subUrl = entity.subtitleUrl?.takeIf { it.isNotBlank() } ?: return null
        val subFile = File(epDir, "episode_${sanitizeFileSegment(entity.episodeNumber)}.vtt")
        return runCatching {
            downloadBinary(subUrl, subFile, entity.referer, entity.origin)
            subFile.takeIf { it.exists() && it.length() > 0L }
        }.getOrNull()
    }

    private fun fetchText(url: String, referer: String, origin: String): String? {
        val builder = Request.Builder().url(url).get()
        if (referer.isNotBlank()) builder.header("Referer", referer)
        if (origin.isNotBlank()) builder.header("Origin", origin)
        return okHttpClient.newCall(builder.build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    }

    private fun downloadBinary(url: String, dest: File, referer: String, origin: String) {
        val builder = Request.Builder().url(url).get()
        if (referer.isNotBlank()) builder.header("Referer", referer)
        if (origin.isNotBlank()) builder.header("Origin", origin)
        val tmp = File(dest.parentFile, "${dest.name}.tmp")
        okHttpClient.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} fetching $url")
            val input = resp.body?.byteStream() ?: error("Empty body for $url")
            val buf = ByteArray(BUFFER_BYTES)
            FileOutputStream(tmp).use { out ->
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                }
            }
        }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        applicationContext.getString(R.string.downloads_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                )
            }
        }
    }

    private fun createForegroundInfo(
        notifId: Int,
        animeTitle: String,
        episode: String,
        progress: Int,
    ): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(animeTitle)
            .setContentText("Downloading Episode $episode ($progress%)")
            .setProgress(PROGRESS_MAX, progress, progress == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notifId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notifId, notification)
        }
    }

    private fun sanitizeFileSegment(raw: String): String {
        return raw.replace(Regex("""[^a-zA-Z0-9._-]"""), "_")
    }

    companion object {
        /** InputData key specifying the [DownloadEpisodeEntity.id] to process. */
        const val KEY_EPISODE_ID = "episode_id"
    }
}
