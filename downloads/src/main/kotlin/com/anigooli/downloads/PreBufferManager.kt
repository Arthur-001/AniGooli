package com.anigooli.downloads

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.anigooli.streaming.StreamSource
import com.imashnake.animite.api.preferences.PreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PreBufferManager"
private const val BYTES_PER_MB = 1024L * 1024L
private const val DISK_SAFETY_RESERVE_MB = 500L
private const val MIN_RAM_BUFFER_MB = 32
private const val MAX_RAM_BUFFER_MB = 96
private const val RAM_FRACTION_DIVISOR = 4
private const val MP4_CHUNK_BYTES = 2 * 1024 * 1024L
private const val IO_BUFFER_SIZE = 32 * 1024
private const val PERCENT_SCALE = 100f
private const val DIGEST_PREFIX_HEX = 24
private const val DEFAULT_CAP_MB = 1024
private const val HTTP_OK = 200
private const val HTTP_PARTIAL = 206

/** Pure policy helpers for pre-buffer sizing and slot keying, unit-tested on JVM. */
object PreBufferPolicy {

    /** User-configurable pre-buffer cache sizes in megabytes (500 MB up to 4 GB max). */
    val supportedOptionsMb: List<Int> = listOf(500, 1024, 1536, 2048, 3072, 4096)

    /** Formats a megabyte option into a readable UI label. */
    fun formatMbLabel(mb: Int): String = when {
        mb < 1024 -> "$mb MB"
        mb % 1024 == 0 -> "${mb / 1024} GB"
        else -> "${mb / 1024f} GB"
    }

    /** Clamps the user's configured pre-buffer cap against available disk space leaving a 500 MB safety margin. */
    fun computeEffectiveCapBytes(
        configuredMb: Int,
        availableDiskBytes: Long,
        safetyReserveBytes: Long = DISK_SAFETY_RESERVE_MB * BYTES_PER_MB,
    ): Long {
        val clampedMb = configuredMb.coerceIn(supportedOptionsMb.first(), supportedOptionsMb.last())
        val requestedBytes = clampedMb.toLong() * BYTES_PER_MB
        val usableDisk = (availableDiskBytes - safetyReserveBytes).coerceAtLeast(0L)
        return minOf(requestedBytes, usableDisk)
    }

    /** Computes safe ExoPlayer in-memory ring-buffer size in MB from device memory class to avoid OOMs. */
    fun computeSafeRamBufferMb(memoryClassMb: Int): Int {
        return (memoryClassMb / RAM_FRACTION_DIVISOR).coerceIn(MIN_RAM_BUFFER_MB, MAX_RAM_BUFFER_MB)
    }

    /** Builds a deterministic filesystem-safe key for a single episode + quality + audio stream slot. */
    fun slotKey(
        anilistId: Int,
        episode: String,
        qualityLabel: String,
        audioTrack: String,
    ): String {
        val raw = "${anilistId}_${episode}_${qualityLabel}_${audioTrack}"
        return raw.replace(Regex("""[^a-zA-Z0-9._-]"""), "_")
    }

    /** Computes a short SHA-256 hash filename key for a remote segment URL. */
    fun urlHash(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(DIGEST_PREFIX_HEX)
    }

    /** Matches stream URLs ignoring rotating query tokens/timestamps. */
    fun isSameStream(firstUrl: String, secondUrl: String): Boolean {
        if (firstUrl == secondUrl) return true
        val a = firstUrl.substringBefore('?')
        val b = secondUrl.substringBefore('?')
        return a.isNotEmpty() && a == b
    }
}

/** Live pre-buffer progress exposed to the video player UI. */
data class PreBufferState(
    /** Key of the single active pre-buffered episode, or null when idle. */
    val activeSlotKey: String? = null,
    /** Total bytes written to the pre-buffer slot so far. */
    val bufferedBytes: Long = 0L,
    /** Effective max pre-buffer cap in bytes for the active episode. */
    val capBytes: Long = 0L,
    /** Estimated total video bytes if known, else 0. */
    val estimatedTotalBytes: Long = 0L,
    /** Fraction from 0f..1f of pre-buffer progress against total or cap. */
    val progressFraction: Float = 0f,
    /** True when the entire video fit inside the pre-buffer cap and finished downloading. */
    val isComplete: Boolean = false,
)

/** Metadata saved inside a single pre-buffer slot directory. */
@Serializable
data class PreBufferSlotMeta(
    /** Stream URL associated with this slot. */
    val streamUrl: String,
    /** True if the stream is HLS (.m3u8). */
    val isHls: Boolean,
    /** Ordered segment URLs when HLS. */
    val segmentUrls: List<String> = emptyList(),
    /** Optional init segment URL when HLS fMP4. */
    val initSegmentUrl: String? = null,
    /** Set of segment URLs successfully cached on disk. */
    val completedSegmentUrls: Set<String> = emptySet(),
    /** Bytes cached for direct MP4 streams. */
    val mp4BytesBuffered: Long = 0L,
    /** Total MP4 Content-Length if returned by the server. */
    val mp4TotalBytes: Long = 0L,
    /** True when every segment or entire MP4 has been pre-buffered. */
    val isComplete: Boolean = false,
    /** Optional quality label for offline stream reconstruction. */
    val qualityLabel: String = "",
    /** Optional server name for offline stream reconstruction. */
    val serverName: String = "",
    /** Optional HTTP Referer header for stream requests. */
    val referer: String = "",
    /** Optional HTTP Origin header for stream requests. */
    val origin: String = "",
    /** Optional subtitle URL for cached playback reuse. */
    val subtitleUrl: String? = null,
    /** Optional separate audio URL for cached playback reuse. */
    val audioUrl: String? = null,
)

/** Result returned when exporting pre-buffered segments/bytes to a permanent download directory. */
data class PreBufferExportResult(
    /** True if any reusable pre-buffered data existed for the requested slot. */
    val hasReusableData: Boolean = false,
    /** True when 100% of the video was already pre-buffered. */
    val isFullyPreBuffered: Boolean = false,
    /** Map from remote segment URL -> copied local segment file inside download working dir. */
    val exportedSegmentsByUrl: Map<String, File> = emptyMap(),
    /** Copied MP4 prefix file inside download working dir if direct MP4 pre-buffer existed. */
    val exportedMp4PartFile: File? = null,
    /** Total MP4 length hint if known. */
    val mp4TotalBytes: Long = 0L,
)

/**
 * Disk-slot manager implementing LRU budget eviction and reusable segment export.
 */
class PreBufferSlotStore(
    private val rootDir: File,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /** Ensures only [keepSlotKey] exists under [rootDir], deleting every other slot immediately. */
    fun evictAllExcept(keepSlotKey: String?) {
        if (!rootDir.exists()) {
            rootDir.mkdirs()
            return
        }
        rootDir.listFiles()?.forEach { child ->
            if (keepSlotKey == null || child.name != keepSlotKey) {
                child.deleteRecursively()
            }
        }
    }

    /** Updates the last-modified timestamp of [slotKey] for LRU eviction ordering. */
    fun touchSlot(slotKey: String) {
        slotDir(slotKey).setLastModified(System.currentTimeMillis())
    }

    /** Returns total bytes stored inside [slotKey]. */
    fun slotSizeBytes(slotKey: String): Long {
        val dir = File(rootDir, slotKey)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * Evicts oldest non-active slots until `totalSizeBytes() + incomingBytes <= capBytes`
     * or no other slots remain. Returns true if [activeSlotKey] is still within [capBytes].
     */
    fun ensureBudgetForIncoming(
        activeSlotKey: String,
        incomingBytes: Long,
        capBytes: Long,
    ): Boolean {
        if (!rootDir.exists()) {
            rootDir.mkdirs()
            return capBytes > 0L
        }
        if (slotSizeBytes(activeSlotKey) >= capBytes) return false

        val otherSlots = rootDir.listFiles()
            ?.filter { it.isDirectory && it.name != activeSlotKey }
            ?.sortedBy { it.lastModified() }
            .orEmpty()

        for (oldSlot in otherSlots) {
            if (totalSizeBytes() + incomingBytes <= capBytes) break
            oldSlot.deleteRecursively()
        }
        return slotSizeBytes(activeSlotKey) < capBytes
    }

    /** Returns total bytes currently stored across [rootDir]. */
    fun totalSizeBytes(): Long {
        if (!rootDir.exists()) return 0L
        return rootDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Returns the directory for [slotKey]. */
    fun slotDir(slotKey: String): File = File(rootDir, slotKey).apply { mkdirs() }

    /** Returns the segment file path inside [slotKey] for [segmentUrl]. */
    fun segmentFile(slotKey: String, segmentUrl: String): File =
        File(slotDir(slotKey), "seg_${PreBufferPolicy.urlHash(segmentUrl)}.bin")

    /** Returns the cached HLS playlist file path inside [slotKey] for [playlistUrl]. */
    fun playlistFile(slotKey: String, playlistUrl: String): File =
        File(slotDir(slotKey), "pl_${PreBufferPolicy.urlHash(playlistUrl)}.m3u8")

    /** Finds any cached slot on disk for [anilistId] and [episode] with reusable buffered data. */
    fun findCachedSlot(anilistId: Int, episode: String): Pair<String, PreBufferSlotMeta>? {
        if (!rootDir.exists()) return null
        val safeEp = episode.replace(Regex("""[^a-zA-Z0-9._-]"""), "_")
        val prefix = "${anilistId}_${safeEp}_"
        val dirs = rootDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(prefix) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        for (dir in dirs) {
            val meta = readMeta(dir.name) ?: continue
            val hasData = if (meta.isHls) {
                meta.completedSegmentUrls.isNotEmpty()
            } else {
                meta.mp4BytesBuffered > 0L && mp4PartialFile(dir.name).exists()
            }
            if (hasData) return dir.name to meta
        }
        return null
    }

    /** Returns true when [slotKey] has a complete cache verified on disk. */
    fun hasVerifiedCompleteCache(slotKey: String): PreBufferSlotMeta? {
        val meta = readMeta(slotKey) ?: return null
        if (!meta.isComplete) return null
        val verified = if (meta.isHls) {
            meta.segmentUrls.isNotEmpty() &&
                meta.completedSegmentUrls.size >= meta.segmentUrls.size &&
                playlistFile(slotKey, meta.streamUrl).exists()
        } else {
            mp4PartialFile(slotKey).let { it.exists() && it.length() > 0L }
        }
        return if (verified) meta else null
    }

    /** Returns the MP4 partial file path inside [slotKey]. */
    fun mp4PartialFile(slotKey: String): File = File(slotDir(slotKey), "mp4_partial.bin")

    /** Reads slot metadata if valid on disk. */
    fun readMeta(slotKey: String): PreBufferSlotMeta? = runCatching {
        val file = File(slotDir(slotKey), "slot_meta.json")
        if (!file.exists()) return null
        json.decodeFromString<PreBufferSlotMeta>(file.readText(Charsets.UTF_8))
    }.getOrNull()

    /** Atomically writes slot metadata. */
    fun writeMeta(slotKey: String, meta: PreBufferSlotMeta) {
        val dir = slotDir(slotKey)
        val tmp = File(dir, "slot_meta.json.tmp")
        val target = File(dir, "slot_meta.json")
        tmp.writeText(json.encodeToString(meta), Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /** Copies any reusable segments or MP4 prefix bytes from [slotKey] into [targetDir]. */
    fun exportTo(slotKey: String, expectedStreamUrl: String, targetDir: File): PreBufferExportResult {
        val meta = readMeta(slotKey) ?: return PreBufferExportResult()
        if (meta.streamUrl != expectedStreamUrl) return PreBufferExportResult()
        if (!targetDir.exists()) targetDir.mkdirs()

        if (meta.isHls) {
            val copiedMap = HashMap<String, File>()
            for (url in meta.completedSegmentUrls) {
                val src = segmentFile(slotKey, url)
                if (src.exists() && src.length() > 0L) {
                    val dst = File(targetDir, src.name)
                    src.copyTo(dst, overwrite = true)
                    copiedMap[url] = dst
                }
            }
            return PreBufferExportResult(
                hasReusableData = copiedMap.isNotEmpty(),
                isFullyPreBuffered = meta.isComplete &&
                    meta.segmentUrls.isNotEmpty() &&
                    copiedMap.size >= meta.segmentUrls.size,
                exportedSegmentsByUrl = copiedMap,
            )
        } else {
            val mp4File = mp4PartialFile(slotKey)
            if (!mp4File.exists() || mp4File.length() == 0L) return PreBufferExportResult()
            val dst = File(targetDir, "video.mp4.part")
            mp4File.copyTo(dst, overwrite = true)
            return PreBufferExportResult(
                hasReusableData = true,
                isFullyPreBuffered = meta.isComplete,
                exportedMp4PartFile = dst,
                mp4TotalBytes = meta.mp4TotalBytes,
            )
        }
    }
}

/**
 * Singleton manager enforcing a single-video pre-buffer slot with configurable disk cap
 * (`500 MB` .. `4 GB`), safe RAM bounds, instant disk DataSource serving, and seamless download reuse.
 */
@Singleton
class PreBufferManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val preferencesRepository: PreferencesRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slotMutex = Mutex()
    private val store = PreBufferSlotStore(File(context.cacheDir, "prebuffer"))

    private val _state = MutableStateFlow(PreBufferState())
    /** Observable pre-buffer status for the active video. */
    val state: StateFlow<PreBufferState> = _state.asStateFlow()

    private var activeJob: Job? = null
    @Volatile
    private var activeSlotKey: String? = null
    @Volatile
    private var activeStreamUrl: String? = null

    /** Returns recommended ExoPlayer RAM buffer size in MB scaled safely to the device's memory class. */
    fun recommendedRamBufferMb(): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryClass = am?.memoryClass ?: MIN_RAM_BUFFER_MB
        return PreBufferPolicy.computeSafeRamBufferMb(memoryClass)
    }

    /** Returns current pre-buffer cache usage on disk in bytes. */
    fun getCacheSizeBytes(): Long = store.totalSizeBytes()

    /**
     * Returns a cached [StreamSource] for [anilistId] + [episode] if any pre-buffered segments
     * or MP4 bytes exist on disk, enabling offline playback of the pre-buffered portion.
     */
    fun findCachedSourceForEpisode(anilistId: Int, episode: String): StreamSource? {
        val (_, meta) = store.findCachedSlot(anilistId, episode) ?: return null
        return StreamSource(
            qualityLabel = meta.qualityLabel.ifBlank { "Cached" },
            height = 0,
            serverName = meta.serverName.ifBlank { "Pre-buffered" },
            url = meta.streamUrl,
            referer = meta.referer,
            origin = meta.origin,
            isHls = meta.isHls,
            subtitleUrl = meta.subtitleUrl,
            audioUrl = meta.audioUrl,
        )
    }

    /**
     * If [slotKey] already has a 100% pre-buffered copy verified on disk, returns a [StreamSource]
     * bound to the cached stream URL so rotating CDN tokens/URLs on re-entry never restart
     * pre-buffering from 0%.
     */
    fun resolveReusableSource(slotKey: String, freshSource: StreamSource): StreamSource {
        val meta = store.hasVerifiedCompleteCache(slotKey) ?: return freshSource
        store.touchSlot(slotKey)
        return StreamSource(
            qualityLabel = meta.qualityLabel.ifBlank { freshSource.qualityLabel },
            height = freshSource.height,
            serverName = meta.serverName.ifBlank { freshSource.serverName },
            url = meta.streamUrl,
            referer = meta.referer.ifBlank { freshSource.referer },
            origin = meta.origin.ifBlank { freshSource.origin },
            isHls = meta.isHls,
            subtitleUrl = freshSource.subtitleUrl ?: meta.subtitleUrl,
            audioUrl = freshSource.audioUrl ?: meta.audioUrl,
        )
    }

    /** Cancels active pre-buffering and wipes all pre-buffer cache files immediately. */
    fun clearCache() {
        scope.launch {
            slotMutex.withLock {
                activeJob?.cancelAndJoin()
                activeJob = null
                activeSlotKey = null
                activeStreamUrl = null
                store.evictAllExcept(null)
                _state.value = PreBufferState()
            }
        }
    }

    /**
     * Starts pre-buffering [source] under [slotKey], continuing in the background across UI exits
     * and evicting older cached slots via LRU only when total storage would exceed [capBytes].
     */
    fun startPreBuffer(slotKey: String, source: StreamSource) {
        activeSlotKey = slotKey
        activeStreamUrl = source.url
        scope.launch {
            slotMutex.withLock {
                if (activeSlotKey == slotKey && activeStreamUrl == source.url && activeJob?.isActive == true) {
                    store.touchSlot(slotKey)
                    return@withLock
                }
                activeJob?.cancelAndJoin()
                store.touchSlot(slotKey)
                activeSlotKey = slotKey
                activeStreamUrl = source.url

                val configuredMb = preferencesRepository.preBufferMaxMb.firstOrNull() ?: DEFAULT_CAP_MB
                val availableBytes = runCatching {
                    StatFs(context.cacheDir.absolutePath).availableBytes
                }.getOrDefault(configuredMb.toLong() * BYTES_PER_MB)
                val capBytes = PreBufferPolicy.computeEffectiveCapBytes(configuredMb, availableBytes)

                val existingMeta = store.readMeta(slotKey)?.takeIf {
                    PreBufferPolicy.isSameStream(it.streamUrl, source.url)
                }
                val initialBuffered = store.slotSizeBytes(slotKey)
                val verifiedComplete = store.hasVerifiedCompleteCache(slotKey) != null && existingMeta != null
                val initialFraction = when {
                    verifiedComplete -> 1f
                    existingMeta != null && existingMeta.isHls && existingMeta.segmentUrls.isNotEmpty() ->
                        (existingMeta.completedSegmentUrls.size.toFloat() / existingMeta.segmentUrls.size).coerceIn(0f, 1f)
                    existingMeta != null && !existingMeta.isHls && existingMeta.mp4TotalBytes > 0L ->
                        (existingMeta.mp4BytesBuffered.toFloat() / existingMeta.mp4TotalBytes).coerceIn(0f, 1f)
                    capBytes > 0L -> (initialBuffered.toFloat() / capBytes).coerceIn(0f, 1f)
                    else -> 0f
                }

                _state.value = PreBufferState(
                    activeSlotKey = slotKey,
                    bufferedBytes = initialBuffered,
                    capBytes = capBytes,
                    estimatedTotalBytes = existingMeta?.mp4TotalBytes ?: 0L,
                    progressFraction = initialFraction,
                    isComplete = verifiedComplete,
                )

                if (verifiedComplete) {
                    activeJob = null
                    return@withLock
                }

                activeJob = scope.launch {
                    runCatching {
                        if (source.isHls) {
                            prefetchHls(slotKey, source, capBytes)
                        } else {
                            prefetchMp4(slotKey, source, capBytes)
                        }
                    }.onFailure { err ->
                        Log.d(TAG, "Pre-buffer ended for $slotKey: ${err.message}")
                    }
                }
            }
        }
    }

    /** Copies any already pre-buffered segments/bytes for [slotKey] into [targetDir] for download reuse. */
    suspend fun exportToDownload(
        slotKey: String,
        streamUrl: String,
        targetDir: File,
    ): PreBufferExportResult {
        return slotMutex.withLock {
            store.exportTo(slotKey, streamUrl, targetDir)
        }
    }

    /**
     * Creates a Media3 [DataSource.Factory] that serves pre-buffered HLS segments or MP4 prefixes
     * straight from disk, transparently falling back to [OkHttpDataSource] on cache misses.
     */
    fun createDataSourceFactory(
        userAgent: String,
        referer: String,
        origin: String,
        slotKeyOverride: String? = null,
        offlineCachedOnlyProvider: () -> Boolean = { false },
    ): DataSource.Factory {
        val defaultProps = buildMap {
            if (referer.isNotBlank()) put("Referer", referer)
            if (origin.isNotBlank()) put("Origin", origin)
        }
        val okHttpFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(defaultProps)

        return DataSource.Factory {
            PreBufferDataSource(
                networkDelegate = okHttpFactory.createDataSource(),
                store = store,
                activeSlotProvider = { slotKeyOverride ?: activeSlotKey },
                offlineCachedOnlyProvider = offlineCachedOnlyProvider,
            )
        }
    }

    private suspend fun prefetchHls(slotKey: String, source: StreamSource, capBytes: Long) {
        if (capBytes <= 0L) return
        val masterPlFile = store.playlistFile(slotKey, source.url)
        val playlistText = fetchString(source.url, source.referer, source.origin)
            ?.also { runCatching { masterPlFile.writeText(it, Charsets.UTF_8) } }
            ?: runCatching { masterPlFile.takeIf { it.exists() }?.readText(Charsets.UTF_8) }.getOrNull()
            ?: return
        var parsed = HlsPlaylistParser.parseMediaPlaylist(playlistText, source.url)
        var resolvedPlaylistUrl = source.url
        if (parsed.isMasterPlaylist && parsed.segmentUrls.isNotEmpty()) {
            resolvedPlaylistUrl = parsed.segmentUrls.first()
            val subPlFile = store.playlistFile(slotKey, resolvedPlaylistUrl)
            val subText = fetchString(resolvedPlaylistUrl, source.referer, source.origin)
                ?.also { runCatching { subPlFile.writeText(it, Charsets.UTF_8) } }
                ?: runCatching { subPlFile.takeIf { it.exists() }?.readText(Charsets.UTF_8) }.getOrNull()
                ?: return
            parsed = HlsPlaylistParser.parseMediaPlaylist(subText, resolvedPlaylistUrl)
        }

        val allSegments = buildList {
            parsed.initSegmentUrl?.let { add(it) }
            addAll(parsed.segmentUrls)
        }
        if (allSegments.isEmpty()) return

        val existing = store.readMeta(slotKey)
        val sameStream = existing != null && PreBufferPolicy.isSameStream(existing.streamUrl, source.url)
        val completed = if (sameStream && existing != null) {
            existing.completedSegmentUrls.toMutableSet()
        } else {
            mutableSetOf()
        }

        var currentBytes = completed.sumOf { url ->
            val f = store.segmentFile(slotKey, url)
            if (f.exists()) f.length() else 0L
        }

        val initialDone = completed.size >= allSegments.size
        store.writeMeta(
            slotKey,
            PreBufferSlotMeta(
                streamUrl = source.url,
                isHls = true,
                segmentUrls = allSegments,
                initSegmentUrl = parsed.initSegmentUrl,
                completedSegmentUrls = completed,
                isComplete = initialDone,
                qualityLabel = source.qualityLabel,
                serverName = source.serverName,
                referer = source.referer,
                origin = source.origin,
                subtitleUrl = source.subtitleUrl,
                audioUrl = source.audioUrl,
            )
        )
        _state.value = PreBufferState(
            activeSlotKey = slotKey,
            bufferedBytes = currentBytes,
            capBytes = capBytes,
            progressFraction = (completed.size.toFloat() / allSegments.size).coerceIn(0f, 1f),
            isComplete = initialDone,
        )

        for (segUrl in allSegments) {
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) break
            if (!store.ensureBudgetForIncoming(slotKey, 0L, capBytes)) break
            val dest = store.segmentFile(slotKey, segUrl)
            if (dest.exists() && dest.length() > 0L && segUrl in completed) continue

            val written = downloadSegmentWithRetry(segUrl, dest, source.referer, source.origin)
            if (written > 0L) {
                completed.add(segUrl)
                currentBytes += written
                store.ensureBudgetForIncoming(slotKey, 0L, capBytes)
                val isDone = completed.size >= allSegments.size
                store.writeMeta(
                    slotKey,
                    PreBufferSlotMeta(
                        streamUrl = source.url,
                        isHls = true,
                        segmentUrls = allSegments,
                        initSegmentUrl = parsed.initSegmentUrl,
                        completedSegmentUrls = completed.toSet(),
                        isComplete = isDone,
                        qualityLabel = source.qualityLabel,
                        serverName = source.serverName,
                        referer = source.referer,
                        origin = source.origin,
                        subtitleUrl = source.subtitleUrl,
                        audioUrl = source.audioUrl,
                    )
                )
                val fraction = (completed.size.toFloat() / allSegments.size).coerceIn(0f, 1f)
                _state.value = PreBufferState(
                    activeSlotKey = slotKey,
                    bufferedBytes = currentBytes,
                    capBytes = capBytes,
                    progressFraction = fraction,
                    isComplete = isDone,
                )
            }
        }
    }

    private suspend fun prefetchMp4(slotKey: String, source: StreamSource, capBytes: Long) {
        if (capBytes <= 0L) return
        val partFile = store.mp4PartialFile(slotKey)
        val existingMeta = store.readMeta(slotKey)
        val sameStream = existingMeta != null && PreBufferPolicy.isSameStream(existingMeta.streamUrl, source.url)
        if (existingMeta != null && !sameStream) {
            partFile.delete()
        }

        var offset = if (partFile.exists()) partFile.length() else 0L
        var totalLength = existingMeta?.takeIf { sameStream }?.mp4TotalBytes ?: 0L
        val alreadyDone = totalLength > 0L && offset >= totalLength
        if (offset > 0L || alreadyDone) {
            val denom = if (totalLength > 0L) totalLength else capBytes
            _state.value = PreBufferState(
                activeSlotKey = slotKey,
                bufferedBytes = offset,
                capBytes = capBytes,
                estimatedTotalBytes = totalLength,
                progressFraction = (offset.toFloat() / denom.toFloat()).coerceIn(0f, 1f),
                isComplete = alreadyDone,
            )
        }

        while (kotlinx.coroutines.currentCoroutineContext().isActive && offset < capBytes) {
            if (totalLength > 0L && offset >= totalLength) break
            val endByte = minOf(offset + MP4_CHUNK_BYTES - 1L, capBytes - 1L)
            val plannedChunk = (endByte - offset + 1L).coerceAtLeast(0L)
            if (!store.ensureBudgetForIncoming(slotKey, plannedChunk, capBytes)) break
            val reqBuilder = Request.Builder()
                .url(source.url)
                .header("Range", "bytes=$offset-$endByte")
            if (source.referer.isNotBlank()) reqBuilder.header("Referer", source.referer)
            if (source.origin.isNotBlank()) reqBuilder.header("Origin", source.origin)

            val resp = executeWithRetry(reqBuilder.build()) ?: break
            val respCode = resp.code
            val body = resp.body
            if ((respCode != HTTP_PARTIAL && respCode != HTTP_OK) || body == null) {
                resp.close()
                break
            }

            if (totalLength == 0L) {
                totalLength = parseTotalSizeFromHeaders(resp.header("Content-Range"), body.contentLength())
            }
            if (respCode == HTTP_OK && offset > 0L) {
                // Server ignored Range header; restart from 0 cleanly once
                offset = 0L
                partFile.delete()
            }

            val bytesWritten = appendResponseChunk(body.byteStream(), partFile, offset == 0L) { chunkDelta ->
                val liveOffset = offset + chunkDelta
                val liveDenom = if (totalLength > 0L) totalLength else capBytes
                if (liveDenom > 0L) {
                    _state.value = PreBufferState(
                        activeSlotKey = slotKey,
                        bufferedBytes = liveOffset,
                        capBytes = capBytes,
                        estimatedTotalBytes = totalLength,
                        progressFraction = (liveOffset.toFloat() / liveDenom.toFloat()).coerceIn(0f, 1f),
                        isComplete = false,
                    )
                }
            }
            resp.close()
            if (bytesWritten <= 0L) break

            offset += bytesWritten
            val isDone = (totalLength > 0L && offset >= totalLength) || respCode == HTTP_OK
            store.writeMeta(
                slotKey,
                PreBufferSlotMeta(
                    streamUrl = source.url,
                    isHls = false,
                    mp4BytesBuffered = offset,
                    mp4TotalBytes = totalLength,
                    isComplete = isDone,
                    qualityLabel = source.qualityLabel,
                    serverName = source.serverName,
                    referer = source.referer,
                    origin = source.origin,
                    subtitleUrl = source.subtitleUrl,
                    audioUrl = source.audioUrl,
                )
            )
            val denom = if (totalLength > 0L) totalLength else capBytes
            _state.value = PreBufferState(
                activeSlotKey = slotKey,
                bufferedBytes = offset,
                capBytes = capBytes,
                estimatedTotalBytes = totalLength,
                progressFraction = (offset.toFloat() / denom.toFloat()).coerceIn(0f, 1f),
                isComplete = isDone,
            )
            if (respCode == HTTP_OK) break
        }
    }

    private fun parseTotalSizeFromHeaders(contentRange: String?, contentLength: Long): Long {
        if (!contentRange.isNullOrBlank() && contentRange.contains("/")) {
            val total = contentRange.substringAfter("/").trim().toLongOrNull()
            if (total != null && total > 0L) return total
        }
        return if (contentLength > 0L) contentLength else 0L
    }

    private fun appendResponseChunk(
        input: java.io.InputStream,
        target: File,
        truncate: Boolean,
        onProgress: ((Long) -> Unit)? = null,
    ): Long {
        var total = 0L
        var lastReported = 0L
        val reportStep = 512L * 1024L
        val buf = ByteArray(IO_BUFFER_SIZE)
        FileOutputStream(target, !truncate).use { out ->
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                out.write(buf, 0, read)
                total += read
                if (onProgress != null && total - lastReported >= reportStep) {
                    lastReported = total
                    onProgress(total)
                }
            }
        }
        return total
    }

    private suspend fun executeWithRetry(request: Request, maxAttempts: Int = 3): okhttp3.Response? {
        repeat(maxAttempts) { attempt ->
            val resp = runCatching { okHttpClient.newCall(request).execute() }.getOrNull()
            if (resp != null && (resp.isSuccessful || resp.code == HTTP_PARTIAL)) {
                return resp
            }
            resp?.close()
            if (attempt < maxAttempts - 1) delay(1_500L)
        }
        return null
    }

    private suspend fun downloadSegmentWithRetry(
        url: String,
        dest: File,
        referer: String,
        origin: String,
        maxAttempts: Int = 3,
    ): Long {
        repeat(maxAttempts) { attempt ->
            val written = runCatching { downloadUrlToFile(url, dest, referer, origin) }.getOrDefault(0L)
            if (written > 0L) return written
            if (attempt < maxAttempts - 1) delay(1_500L)
        }
        return 0L
    }

    private fun fetchString(url: String, referer: String, origin: String): String? = runCatching {
        val builder = Request.Builder().url(url).get()
        if (referer.isNotBlank()) builder.header("Referer", referer)
        if (origin.isNotBlank()) builder.header("Origin", origin)
        okHttpClient.newCall(builder.build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    }.getOrNull()

    private fun downloadUrlToFile(url: String, dest: File, referer: String, origin: String): Long {
        val builder = Request.Builder().url(url).get()
        if (referer.isNotBlank()) builder.header("Referer", referer)
        if (origin.isNotBlank()) builder.header("Origin", origin)
        val tmp = File(dest.parentFile, "${dest.name}.tmp")
        return okHttpClient.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return@use 0L
            val stream = resp.body?.byteStream() ?: return@use 0L
            val written = appendResponseChunk(stream, tmp, truncate = true)
            if (written > 0L && !tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            written
        }
    }
}

private class PreBufferDataSource(
    private val networkDelegate: DataSource,
    private val store: PreBufferSlotStore,
    private val activeSlotProvider: () -> String?,
    private val offlineCachedOnlyProvider: () -> Boolean,
) : DataSource {

    private var fileDelegate: FileDataSource? = null
    private var memoryDelegate: ByteArrayDataSource? = null
    private var partialMp4: PartialMp4Reader? = null
    private var activeDelegate: DataSource? = null
    private val transferListeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners.add(transferListener)
        networkDelegate.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val url = dataSpec.uri.toString()
        val slotKey = activeSlotProvider()
        if (slotKey != null) {
            val playlistFile = store.playlistFile(slotKey, url)
            if (playlistFile.exists() && playlistFile.length() > 0L && dataSpec.position < playlistFile.length()) {
                if (offlineCachedOnlyProvider()) {
                    val trimmed = runCatching {
                        val text = playlistFile.readText(Charsets.UTF_8)
                        HlsPlaylistParser.trimToCachedPlaylist(
                            playlistText = text,
                            baseUrl = url,
                            isVariantCached = { store.playlistFile(slotKey, it).exists() },
                            isSegmentCached = {
                                val f = store.segmentFile(slotKey, it)
                                f.exists() && f.length() > 0L
                            },
                        )
                    }.getOrNull()
                    if (!trimmed.isNullOrBlank()) {
                        val bytes = trimmed.toByteArray(Charsets.UTF_8)
                        val mem = ByteArrayDataSource(bytes).also { m -> transferListeners.forEach(m::addTransferListener) }
                        memoryDelegate = mem
                        activeDelegate = mem
                        return mem.open(dataSpec)
                    }
                }
                val fs = FileDataSource().also { f -> transferListeners.forEach(f::addTransferListener) }
                val localSpec = dataSpec.buildUpon().setUri(Uri.fromFile(playlistFile)).build()
                fileDelegate = fs
                activeDelegate = fs
                return fs.open(localSpec)
            }
            val segFile = store.segmentFile(slotKey, url)
            if (segFile.exists() && segFile.length() > 0L && dataSpec.position < segFile.length()) {
                val fs = FileDataSource().also { f -> transferListeners.forEach(f::addTransferListener) }
                val localSpec = dataSpec.buildUpon().setUri(Uri.fromFile(segFile)).build()
                fileDelegate = fs
                activeDelegate = fs
                return fs.open(localSpec)
            }
            val meta = store.readMeta(slotKey)
            if (meta != null && !meta.isHls && PreBufferPolicy.isSameStream(meta.streamUrl, url)) {
                val mp4File = store.mp4PartialFile(slotKey)
                if (mp4File.exists() && mp4File.length() > 0L) {
                    if (meta.isComplete) {
                        val fs = FileDataSource().also { f -> transferListeners.forEach(f::addTransferListener) }
                        val localSpec = dataSpec.buildUpon().setUri(Uri.fromFile(mp4File)).build()
                        fileDelegate = fs
                        activeDelegate = fs
                        return fs.open(localSpec)
                    }
                    if (dataSpec.position < mp4File.length()) {
                        val reader = PartialMp4Reader(
                            mp4File = mp4File,
                            totalBytes = meta.mp4TotalBytes,
                            originalSpec = dataSpec,
                            networkDelegate = networkDelegate,
                            offlineCachedOnlyProvider = offlineCachedOnlyProvider,
                        )
                        partialMp4 = reader
                        activeDelegate = null
                        return reader.open()
                    }
                }
            }
        }
        activeDelegate = networkDelegate
        return networkDelegate.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        partialMp4?.let { return it.read(buffer, offset, length) }
        return activeDelegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? = partialMp4?.uri ?: activeDelegate?.uri

    override fun close() {
        runCatching { partialMp4?.close() }
        runCatching { memoryDelegate?.close() }
        runCatching { fileDelegate?.close() }
        runCatching {
            if (activeDelegate === networkDelegate) networkDelegate.close()
        }
        partialMp4 = null
        memoryDelegate = null
        fileDelegate = null
        activeDelegate = null
    }
}

private class PartialMp4Reader(
    private val mp4File: File,
    private val totalBytes: Long,
    private val originalSpec: DataSpec,
    private val networkDelegate: DataSource,
    private val offlineCachedOnlyProvider: () -> Boolean,
) {
    private var raf: RandomAccessFile? = null
    private var currentPos: Long = originalSpec.position
    private var remainingSpecBytes: Long = originalSpec.length
    private var networkOpened: Boolean = false
    private var networkExhausted: Boolean = false

    val uri: Uri get() = originalSpec.uri

    fun open(): Long {
        raf = RandomAccessFile(mp4File, "r").apply { seek(currentPos) }
        return when {
            originalSpec.length != C.LENGTH_UNSET.toLong() -> originalSpec.length
            totalBytes > 0L -> (totalBytes - currentPos).coerceAtLeast(0L)
            else -> C.LENGTH_UNSET.toLong()
        }
    }

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remainingSpecBytes == 0L) return C.RESULT_END_OF_INPUT
        val maxRead = if (remainingSpecBytes != C.LENGTH_UNSET.toLong()) {
            minOf(length.toLong(), remainingSpecBytes).toInt()
        } else {
            length
        }

        val cachedLen = mp4File.length()
        if (currentPos < cachedLen) {
            val fileAvail = (cachedLen - currentPos).coerceAtMost(maxRead.toLong()).toInt()
            val n = raf?.read(buffer, offset, fileAvail) ?: -1
            if (n > 0) {
                currentPos += n
                if (remainingSpecBytes != C.LENGTH_UNSET.toLong()) remainingSpecBytes -= n
                return n
            }
        }

        if (networkExhausted || offlineCachedOnlyProvider() || (totalBytes > 0L && currentPos >= totalBytes)) {
            return C.RESULT_END_OF_INPUT
        }

        if (!networkOpened) {
            val opened = runCatching {
                val netSpec = originalSpec.buildUpon()
                    .setPosition(currentPos)
                    .setLength(remainingSpecBytes)
                    .build()
                networkDelegate.open(netSpec)
                true
            }.getOrDefault(false)
            if (!opened) {
                networkExhausted = true
                return C.RESULT_END_OF_INPUT
            }
            networkOpened = true
        }

        return try {
            val n = networkDelegate.read(buffer, offset, maxRead)
            if (n > 0) {
                currentPos += n
                if (remainingSpecBytes != C.LENGTH_UNSET.toLong()) remainingSpecBytes -= n
            }
            n
        } catch (_: IOException) {
            networkExhausted = true
            C.RESULT_END_OF_INPUT
        }
    }

    fun close() {
        runCatching { raf?.close() }
        raf = null
        if (networkOpened) {
            runCatching { networkDelegate.close() }
            networkOpened = false
        }
    }
}
