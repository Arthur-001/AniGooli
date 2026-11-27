package com.anigooli.downloads

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PreBufferManager"
private const val BYTES_PER_MB = 1024L * 1024L
private const val DEFAULT_SAFETY_RESERVE_MB = 500L
private const val RAM_SAFETY_RESERVE_MB = 256L
private const val MIN_RAM_BUFFER_MB = 32
private const val MAX_RAM_BUFFER_MB = 96
private const val RAM_FRACTION_DIVISOR = 4
private const val MP4_CHUNK_BYTES = 2 * 1024 * 1024L
private const val MP4_SLICE_BYTES = 1024 * 1024
private const val MAX_SINGLE_AUDIO_BYTES = 32L * 1024L * 1024L
private const val IO_BUFFER_SIZE = 32 * 1024
private const val DIGEST_PREFIX_HEX = 24
private const val DEFAULT_CAP_MB = 1024
private const val HTTP_OK = 200
private const val HTTP_PARTIAL = 206
private const val MAX_RETRY_ATTEMPTS = 3
private const val RETRY_DELAY_MS = 1_500L
private const val PROGRESS_STEP_BYTES = 512L * 1024L

/** Pure policy helpers for RAM pre-buffer sizing and slot keying, unit-tested on JVM. */
object PreBufferPolicy {

    /** User-configurable in-memory pre-buffer sizes in megabytes (500 MB up to 4 GB max). */
    val supportedOptionsMb: List<Int> = listOf(500, 1024, 1536, 2048, 3072, 4096)

    /** Formats a megabyte option into a readable UI label. */
    fun formatMbLabel(mb: Int): String = when {
        mb < 1024 -> "$mb MB"
        mb % 1024 == 0 -> "${mb / 1024} GB"
        else -> "${mb / 1024f} GB"
    }

    /** Clamps the user's configured pre-buffer cap against available memory leaving a safety reserve. */
    fun computeEffectiveCapBytes(
        configuredMb: Int,
        availableBytes: Long,
        safetyReserveBytes: Long = DEFAULT_SAFETY_RESERVE_MB * BYTES_PER_MB,
    ): Long {
        val clampedMb = configuredMb.coerceIn(supportedOptionsMb.first(), supportedOptionsMb.last())
        val requestedBytes = clampedMb.toLong() * BYTES_PER_MB
        val usable = (availableBytes - safetyReserveBytes).coerceAtLeast(0L)
        return minOf(requestedBytes, usable)
    }

    /** Computes safe ExoPlayer in-memory ring-buffer size in MB from device memory class to avoid OOMs. */
    fun computeSafeRamBufferMb(memoryClassMb: Int): Int {
        return (memoryClassMb / RAM_FRACTION_DIVISOR).coerceIn(MIN_RAM_BUFFER_MB, MAX_RAM_BUFFER_MB)
    }

    /** Builds a deterministic key for a single episode + quality + audio stream slot. */
    fun slotKey(
        anilistId: Int,
        episode: String,
        qualityLabel: String,
        audioTrack: String,
    ): String {
        val raw = "${anilistId}_${episode}_${qualityLabel}_${audioTrack}"
        return raw.replace(Regex("""[^a-zA-Z0-9._-]"""), "_")
    }

    /** Computes a short SHA-256 hash key for a URL or download filename. */
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

/** Live in-memory pre-buffer progress exposed to the video player UI. */
data class PreBufferState(
    /** Key of the active pre-buffered episode in RAM, or null when idle. */
    val activeSlotKey: String? = null,
    /** Total bytes held in RAM for the active pre-buffer slot so far. */
    val bufferedBytes: Long = 0L,
    /** Effective max RAM pre-buffer cap in bytes for the active episode. */
    val capBytes: Long = 0L,
    /** Estimated total video bytes if known, else 0. */
    val estimatedTotalBytes: Long = 0L,
    /** Total media playlist duration in ms when declared by `#EXTINF`, else 0. */
    val totalDurationMs: Long = 0L,
    /** Fraction from 0f..1f of pre-buffer progress against total or cap. */
    val progressFraction: Float = 0f,
    /** True when the entire video fit inside the RAM pre-buffer cap and finished downloading. */
    val isComplete: Boolean = false,
)

/** Metadata stored in RAM for a single pre-buffered episode slot. */
data class PreBufferSlotMeta(
    /** Stream URL associated with this slot. */
    val streamUrl: String,
    /** True if the stream is HLS (.m3u8). */
    val isHls: Boolean,
    /** Ordered video segment URLs when HLS. */
    val segmentUrls: List<String> = emptyList(),
    /** Optional init segment URL when HLS fMP4. */
    val initSegmentUrl: String? = null,
    /** Set of video segment URLs currently cached in RAM. */
    val completedSegmentUrls: Set<String> = emptySet(),
    /** Distinct encryption key URLs referenced by the HLS playlist. */
    val keyUrls: List<String> = emptyList(),
    /** Bytes cached in RAM for direct MP4 streams. */
    val mp4BytesBuffered: Long = 0L,
    /** Total MP4 Content-Length if returned by the server. */
    val mp4TotalBytes: Long = 0L,
    /** True when every segment or entire MP4 has been pre-buffered into RAM. */
    val isComplete: Boolean = false,
    /** Optional quality label for cached stream reconstruction. */
    val qualityLabel: String = "",
    /** Optional server name for cached stream reconstruction. */
    val serverName: String = "",
    /** Optional HTTP Referer header for stream requests. */
    val referer: String = "",
    /** Optional HTTP Origin header for stream requests. */
    val origin: String = "",
    /** Optional subtitle URL for cached playback reuse. */
    val subtitleUrl: String? = null,
    /** Optional separate audio URL for cached playback reuse. */
    val audioUrl: String? = null,
    /** Sum of `#EXTINF` durations in milliseconds when HLS, or 0. */
    val totalDurationMs: Long = 0L,
)

/** Result returned when exporting RAM pre-buffered segments/bytes to a permanent download directory. */
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

private class InMemorySlot(
    @Volatile var meta: PreBufferSlotMeta = PreBufferSlotMeta(streamUrl = "", isHls = false),
    @Volatile var lastAccessedMs: Long = System.currentTimeMillis(),
) {
    val playlists = ConcurrentHashMap<String, ByteArray>()
    val keys = ConcurrentHashMap<String, ByteArray>()
    val segments = ConcurrentHashMap<String, ByteBuffer>()
    val mp4Chunks = CopyOnWriteArrayList<ByteBuffer>()
    val mp4BufferedBytes = AtomicLong(0L)

    fun sizeBytes(): Long {
        var total = 0L
        playlists.values.forEach { total += it.size }
        keys.values.forEach { total += it.size }
        segments.values.forEach { total += it.limit() }
        total += mp4BufferedBytes.get()
        return total
    }

    fun release() {
        playlists.clear()
        keys.clear()
        segments.clear()
        mp4Chunks.clear()
        mp4BufferedBytes.set(0L)
    }
}

/**
 * Purely in-memory (RAM) slot store using off-heap direct [ByteBuffer]s, LRU budget eviction,
 * and zero disk footprint (automatically reclaimed when the OS terminates the app process).
 */
class InMemoryPreBufferStore {

    private val slots = ConcurrentHashMap<String, InMemorySlot>()

    private fun getOrCreateSlot(slotKey: String): InMemorySlot =
        slots.getOrPut(slotKey) { InMemorySlot() }

    /** Removes all slots from RAM except [keepSlotKey]. */
    fun evictAllExcept(keepSlotKey: String?) {
        val toRemove = slots.keys.filter { keepSlotKey == null || it != keepSlotKey }
        for (key in toRemove) {
            slots.remove(key)?.release()
        }
    }

    /** Clears all cached bytes inside [slotKey] and removes it from RAM. */
    fun clearSlot(slotKey: String) {
        slots.remove(slotKey)?.release()
    }

    /** Returns whether [slotKey] exists in RAM. */
    fun containsSlot(slotKey: String): Boolean = slots.containsKey(slotKey)

    /** Updates the LRU access timestamp of [slotKey] if present. */
    fun touchSlot(slotKey: String) {
        slots[slotKey]?.lastAccessedMs = System.currentTimeMillis()
    }

    /** Returns total RAM bytes currently held by [slotKey]. */
    fun slotSizeBytes(slotKey: String): Long = slots[slotKey]?.sizeBytes() ?: 0L

    /** Returns total RAM bytes currently held across all in-memory slots. */
    fun totalSizeBytes(): Long = slots.values.sumOf { it.sizeBytes() }

    /**
     * Evicts oldest non-active slots by LRU order until `totalSizeBytes() + incomingBytes <= capBytes`
     * or no other slots remain. Returns true if [activeSlotKey] + [incomingBytes] fits within [capBytes].
     */
    fun ensureBudgetForIncoming(
        activeSlotKey: String,
        incomingBytes: Long,
        capBytes: Long,
    ): Boolean {
        if (capBytes <= 0L) return false
        if (slotSizeBytes(activeSlotKey) + incomingBytes > capBytes) return false

        val otherSlots = slots.entries
            .filter { it.key != activeSlotKey }
            .sortedBy { it.value.lastAccessedMs }

        for ((key, _) in otherSlots) {
            if (totalSizeBytes() + incomingBytes <= capBytes) break
            slots.remove(key)?.release()
        }
        return totalSizeBytes() + incomingBytes <= capBytes
    }

    /** Allocates an off-heap direct [ByteBuffer] containing [data], respecting [capBytes] and OOM guards. */
    fun allocateDirectSafely(
        activeSlotKey: String,
        data: ByteArray,
        capBytes: Long,
    ): ByteBuffer? {
        if (data.isEmpty()) return null
        if (!ensureBudgetForIncoming(activeSlotKey, data.size.toLong(), capBytes)) return null
        tryAllocateDirect(data)?.let { return it }

        val otherKeys = slots.entries
            .filter { it.key != activeSlotKey }
            .sortedBy { it.value.lastAccessedMs }
            .map { it.key }
        for (key in otherKeys) {
            slots.remove(key)?.release()
            tryAllocateDirect(data)?.let { return it }
        }
        return null
    }

    private fun tryAllocateDirect(data: ByteArray): ByteBuffer? = try {
        ByteBuffer.allocateDirect(data.size).apply {
            put(data)
            flip()
        }
    } catch (_: OutOfMemoryError) {
        null
    }

    /** Stores HLS playlist text bytes in RAM for [slotKey] and [playlistUrl]. */
    fun putPlaylist(slotKey: String, playlistUrl: String, bytes: ByteArray) {
        getOrCreateSlot(slotKey).playlists[playlistUrl] = bytes
    }

    /** Retrieves cached HLS playlist bytes from RAM for [slotKey] and [playlistUrl]. */
    fun getPlaylist(slotKey: String, playlistUrl: String): ByteArray? =
        slots[slotKey]?.playlists?.get(playlistUrl)

    /** Stores HLS `#EXT-X-KEY` decryption key bytes in RAM for [slotKey] and [keyUrl]. */
    fun putKey(slotKey: String, keyUrl: String, bytes: ByteArray) {
        getOrCreateSlot(slotKey).keys[keyUrl] = bytes
    }

    /** Retrieves cached HLS decryption key bytes from RAM for [slotKey] and [keyUrl]. */
    fun getKey(slotKey: String, keyUrl: String): ByteArray? =
        slots[slotKey]?.keys?.get(keyUrl)

    /** Stores a direct [ByteBuffer] segment in RAM for [slotKey] and [segmentUrl]. */
    fun putSegment(slotKey: String, segmentUrl: String, buffer: ByteBuffer) {
        getOrCreateSlot(slotKey).segments[segmentUrl] = buffer
    }

    /** Returns a read-only duplicate [ByteBuffer] for [segmentUrl] inside [slotKey], or null if absent. */
    fun getSegment(slotKey: String, segmentUrl: String): ByteBuffer? =
        slots[slotKey]?.segments?.get(segmentUrl)?.duplicate()

    /** Appends an MP4 direct [ByteBuffer] chunk into [slotKey]'s RAM buffer list. */
    fun appendMp4Chunk(slotKey: String, buffer: ByteBuffer) {
        val slot = getOrCreateSlot(slotKey)
        slot.mp4Chunks.add(buffer)
        slot.mp4BufferedBytes.addAndGet(buffer.limit().toLong())
    }

    /** Returns total MP4 bytes buffered in RAM for [slotKey]. */
    fun mp4BufferedBytes(slotKey: String): Long =
        slots[slotKey]?.mp4BufferedBytes?.get() ?: 0L

    /** Reads up to [length] MP4 bytes from RAM starting at [position] into [dest]. */
    fun readMp4Bytes(
        slotKey: String,
        position: Long,
        dest: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val slot = slots[slotKey] ?: return -1
        var cursor = 0L
        for (chunk in slot.mp4Chunks) {
            val chunkLen = chunk.limit()
            val chunkEnd = cursor + chunkLen
            if (position < chunkEnd) {
                val inChunkPos = (position - cursor).toInt()
                val toCopy = minOf(length, chunkLen - inChunkPos)
                val dup = chunk.duplicate()
                dup.position(inChunkPos)
                dup.get(dest, offset, toCopy)
                return toCopy
            }
            cursor = chunkEnd
        }
        return -1
    }

    /** Finds any in-memory slot for [anilistId] and [episode] with reusable buffered data. */
    fun findCachedSlot(anilistId: Int, episode: String): Pair<String, PreBufferSlotMeta>? {
        val safeEp = episode.replace(Regex("""[^a-zA-Z0-9._-]"""), "_")
        val prefix = "${anilistId}_${safeEp}_"
        val candidates = slots.entries
            .filter { it.key.startsWith(prefix) }
            .sortedByDescending { it.value.lastAccessedMs }
        for ((key, _) in candidates) {
            val meta = hasReusableCache(key) ?: continue
            return key to meta
        }
        return null
    }

    /** Returns metadata when [slotKey] has a 100% complete cache verified in RAM. */
    fun hasVerifiedCompleteCache(slotKey: String): PreBufferSlotMeta? {
        val slot = slots[slotKey] ?: return null
        val meta = slot.meta
        if (!meta.isComplete || meta.streamUrl.isBlank()) return null
        val verified = if (meta.isHls) {
            meta.segmentUrls.isNotEmpty() &&
                meta.completedSegmentUrls.size >= meta.segmentUrls.size &&
                slot.playlists.containsKey(meta.streamUrl) &&
                slot.segments.containsKey(meta.segmentUrls.first())
        } else {
            slot.mp4BufferedBytes.get() > 0L
        }
        return if (verified) meta else null
    }

    /** Returns metadata when [slotKey] has any playable cached media in RAM (partial or complete). */
    fun hasReusableCache(slotKey: String): PreBufferSlotMeta? {
        val slot = slots[slotKey] ?: return null
        val meta = slot.meta
        if (meta.streamUrl.isBlank()) return null
        val usable = if (meta.isHls) {
            meta.completedSegmentUrls.isNotEmpty() &&
                slot.playlists.containsKey(meta.streamUrl) &&
                meta.segmentUrls.firstOrNull()?.let { slot.segments.containsKey(it) } == true
        } else {
            slot.mp4BufferedBytes.get() > 0L
        }
        return if (usable) meta else null
    }

    /** Reads slot metadata from RAM if valid. */
    fun readMeta(slotKey: String): PreBufferSlotMeta? =
        slots[slotKey]?.meta?.takeIf { it.streamUrl.isNotBlank() }

    /** Saves slot metadata in RAM and refreshes its LRU access timestamp. */
    fun writeMeta(slotKey: String, meta: PreBufferSlotMeta) {
        val slot = getOrCreateSlot(slotKey)
        slot.meta = meta
        slot.lastAccessedMs = System.currentTimeMillis()
    }

    /** Writes reusable segments or MP4 bytes from RAM into [targetDir] when exporting to a permanent download. */
    fun exportTo(slotKey: String, expectedStreamUrl: String, targetDir: File): PreBufferExportResult {
        val slot = slots[slotKey] ?: return PreBufferExportResult()
        val meta = slot.meta
        if (!PreBufferPolicy.isSameStream(meta.streamUrl, expectedStreamUrl)) return PreBufferExportResult()
        if (!targetDir.exists()) targetDir.mkdirs()

        return if (meta.isHls) {
            val copiedMap = HashMap<String, File>()
            for (url in meta.completedSegmentUrls) {
                val buf = slot.segments[url]?.duplicate() ?: continue
                if (buf.remaining() <= 0) continue
                val dst = File(targetDir, "seg_${PreBufferPolicy.urlHash(url)}.bin")
                writeBufferToFile(buf, dst)
                copiedMap[url] = dst
            }
            PreBufferExportResult(
                hasReusableData = copiedMap.isNotEmpty(),
                isFullyPreBuffered = meta.isComplete &&
                    meta.segmentUrls.isNotEmpty() &&
                    copiedMap.size >= meta.segmentUrls.size,
                exportedSegmentsByUrl = copiedMap,
            )
        } else {
            val totalMp4 = slot.mp4BufferedBytes.get()
            if (totalMp4 <= 0L || slot.mp4Chunks.isEmpty()) return PreBufferExportResult()
            val dst = File(targetDir, "video.mp4.part")
            FileOutputStream(dst, false).use { out ->
                val scratch = ByteArray(IO_BUFFER_SIZE)
                for (chunk in slot.mp4Chunks) {
                    val dup = chunk.duplicate()
                    while (dup.hasRemaining()) {
                        val n = minOf(scratch.size, dup.remaining())
                        dup.get(scratch, 0, n)
                        out.write(scratch, 0, n)
                    }
                }
            }
            PreBufferExportResult(
                hasReusableData = true,
                isFullyPreBuffered = meta.isComplete,
                exportedMp4PartFile = dst,
                mp4TotalBytes = meta.mp4TotalBytes,
            )
        }
    }

    private fun writeBufferToFile(buf: ByteBuffer, dst: File) {
        FileOutputStream(dst, false).use { out ->
            val scratch = ByteArray(IO_BUFFER_SIZE)
            while (buf.hasRemaining()) {
                val n = minOf(scratch.size, buf.remaining())
                buf.get(scratch, 0, n)
                out.write(scratch, 0, n)
            }
        }
    }
}

/**
 * Singleton manager providing purely in-memory (RAM) off-heap pre-buffering (`500 MB` .. `4 GB`),
 * LRU eviction across episodes, zero disk usage, instant Media3 DataSource serving, and download export.
 */
@Singleton
class PreBufferManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val preferencesRepository: PreferencesRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slotMutex = Mutex()
    private val store = InMemoryPreBufferStore()

    private val _state = MutableStateFlow(PreBufferState())
    /** Observable RAM pre-buffer status for the active video. */
    val state: StateFlow<PreBufferState> = _state.asStateFlow()

    private var activeJob: Job? = null
    private var activeJobSlotKey: String? = null
    private var activeJobStreamUrl: String? = null
    @Volatile
    private var activeSlotKey: String? = null

    init {
        // Clean up any legacy on-disk pre-buffer directories from older app versions.
        scope.launch {
            runCatching { File(context.cacheDir, "prebuffer").deleteRecursively() }
        }
    }

    /** Returns recommended ExoPlayer RAM ring-buffer size in MB scaled safely to the device's memory class. */
    fun recommendedRamBufferMb(): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryClass = am?.memoryClass ?: MIN_RAM_BUFFER_MB
        return PreBufferPolicy.computeSafeRamBufferMb(memoryClass)
    }

    /** Returns current pre-buffer usage held in RAM in bytes (zero disk space used). */
    fun getCacheSizeBytes(): Long = store.totalSizeBytes()

    /** Returns true when [slotKey] currently has playable pre-buffered bytes in RAM. */
    fun hasCachedDataForSlot(slotKey: String?): Boolean {
        if (slotKey.isNullOrBlank()) return false
        return store.hasReusableCache(slotKey) != null
    }

    /**
     * Returns a cached [StreamSource] for [anilistId] + [episode] if pre-buffered data exists
     * in RAM, enabling offline playback of the pre-buffered portion.
     */
    fun findCachedSourceForEpisode(anilistId: Int, episode: String): StreamSource? {
        val (_, meta) = store.findCachedSlot(anilistId, episode) ?: return null
        return buildSourceFromMeta(meta, fallback = null)
    }

    /**
     * If [slotKey] already has a verified 100% or reusable partial cache in RAM for the same stream,
     * returns a [StreamSource] bound to the cached URL so rotating tokens/CDNs don't discard progress.
     */
    fun resolveReusableSource(slotKey: String, freshSource: StreamSource): StreamSource {
        store.hasVerifiedCompleteCache(slotKey)?.let { meta ->
            store.touchSlot(slotKey)
            return buildSourceFromMeta(meta, fallback = freshSource)
        }
        store.hasReusableCache(slotKey)?.let { meta ->
            if (PreBufferPolicy.isSameStream(meta.streamUrl, freshSource.url)) {
                store.touchSlot(slotKey)
                return buildSourceFromMeta(meta, fallback = freshSource)
            }
        }
        return freshSource
    }

    private fun buildSourceFromMeta(meta: PreBufferSlotMeta, fallback: StreamSource?): StreamSource {
        return StreamSource(
            qualityLabel = meta.qualityLabel.ifBlank { fallback?.qualityLabel ?: "Cached" },
            height = fallback?.height ?: 0,
            serverName = meta.serverName.ifBlank { fallback?.serverName ?: "Pre-buffered" },
            url = meta.streamUrl,
            referer = meta.referer.ifBlank { fallback?.referer.orEmpty() },
            origin = meta.origin.ifBlank { fallback?.origin.orEmpty() },
            isHls = meta.isHls,
            subtitleUrl = fallback?.subtitleUrl ?: meta.subtitleUrl,
            audioUrl = fallback?.audioUrl ?: meta.audioUrl,
        )
    }

    /** Cancels active pre-buffering and immediately releases all in-memory buffers. */
    fun clearCache() {
        scope.launch {
            slotMutex.withLock {
                activeJob?.cancelAndJoin()
                activeJob = null
                activeJobSlotKey = null
                activeJobStreamUrl = null
                activeSlotKey = null
                store.evictAllExcept(null)
                _state.value = PreBufferState()
            }
        }
    }

    /**
     * Starts in-memory RAM pre-buffering of [source] under [slotKey], continuing in the background
     * across UI exits while the process stays alive and evicting older slots via LRU on cap pressure.
     */
    fun startPreBuffer(slotKey: String, source: StreamSource) {
        activeSlotKey = slotKey
        scope.launch {
            slotMutex.withLock {
                if (activeJobSlotKey == slotKey && activeJobStreamUrl == source.url && activeJob?.isActive == true) {
                    store.touchSlot(slotKey)
                    return@withLock
                }
                activeJob?.cancelAndJoin()
                store.touchSlot(slotKey)
                activeSlotKey = slotKey
                activeJobSlotKey = slotKey
                activeJobStreamUrl = source.url

                val capBytes = computeEffectiveRamCapBytes()
                val existingMeta = store.readMeta(slotKey)?.takeIf {
                    PreBufferPolicy.isSameStream(it.streamUrl, source.url)
                }
                val initialBuffered = store.slotSizeBytes(slotKey)
                val verifiedComplete = store.hasVerifiedCompleteCache(slotKey) != null && existingMeta != null
                val initialFraction = computeProgressFraction(existingMeta, verifiedComplete, initialBuffered, capBytes)

                _state.value = PreBufferState(
                    activeSlotKey = slotKey,
                    bufferedBytes = initialBuffered,
                    capBytes = capBytes,
                    estimatedTotalBytes = existingMeta?.mp4TotalBytes ?: 0L,
                    totalDurationMs = existingMeta?.totalDurationMs ?: 0L,
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
                        Log.d(TAG, "RAM pre-buffer ended for $slotKey: ${err.message}")
                    }
                }
            }
        }
    }

    private suspend fun computeEffectiveRamCapBytes(): Long {
        val configuredMb = preferencesRepository.preBufferMaxMb.firstOrNull() ?: DEFAULT_CAP_MB
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        val availableRam = if (am != null) {
            am.getMemoryInfo(memInfo)
            memInfo.availMem
        } else {
            (configuredMb.toLong() + RAM_SAFETY_RESERVE_MB) * BYTES_PER_MB
        }
        val reserve = maxOf(RAM_SAFETY_RESERVE_MB * BYTES_PER_MB, memInfo.threshold)
        return PreBufferPolicy.computeEffectiveCapBytes(configuredMb, availableRam, reserve)
    }

    private fun computeProgressFraction(
        meta: PreBufferSlotMeta?,
        verifiedComplete: Boolean,
        bufferedBytes: Long,
        capBytes: Long,
    ): Float = when {
        verifiedComplete -> 1f
        meta != null && meta.isHls && meta.segmentUrls.isNotEmpty() ->
            (meta.completedSegmentUrls.size.toFloat() / meta.segmentUrls.size).coerceIn(0f, 1f)
        meta != null && !meta.isHls && meta.mp4TotalBytes > 0L ->
            (meta.mp4BytesBuffered.toFloat() / meta.mp4TotalBytes).coerceIn(0f, 1f)
        capBytes > 0L -> (bufferedBytes.toFloat() / capBytes).coerceIn(0f, 1f)
        else -> 0f
    }

    /** Copies any already pre-buffered segments/bytes from RAM into [targetDir] for permanent download reuse. */
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
     * Creates a Media3 [DataSource.Factory] that serves pre-buffered HLS playlists, decryption keys,
     * segments, and partial/complete MP4s directly from RAM, falling back to [OkHttpDataSource].
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
        val playlistText = fetchString(source.url, source.referer, source.origin)
            ?: store.getPlaylist(slotKey, source.url)?.toString(Charsets.UTF_8)
            ?: return

        var parsed = HlsPlaylistParser.parseMediaPlaylist(playlistText, source.url)
        var resolvedPlaylistUrl = source.url
        var subPlaylistText: String? = null
        if (parsed.isMasterPlaylist && parsed.segmentUrls.isNotEmpty()) {
            resolvedPlaylistUrl = parsed.segmentUrls.first()
            subPlaylistText = fetchString(resolvedPlaylistUrl, source.referer, source.origin)
                ?: store.getPlaylist(slotKey, resolvedPlaylistUrl)?.toString(Charsets.UTF_8)
                ?: return
            parsed = HlsPlaylistParser.parseMediaPlaylist(subPlaylistText, resolvedPlaylistUrl)
        }

        val allSegments = buildList {
            parsed.initSegmentUrl?.let { add(it) }
            addAll(parsed.segmentUrls)
        }.distinct()
        if (allSegments.isEmpty()) return

        val existing = store.readMeta(slotKey)
        val sameStream = existing != null && PreBufferPolicy.isSameStream(existing.streamUrl, source.url)
        if (existing != null && !sameStream) {
            store.clearSlot(slotKey)
        }
        store.putPlaylist(slotKey, source.url, playlistText.toByteArray(Charsets.UTF_8))
        if (subPlaylistText != null && resolvedPlaylistUrl != source.url) {
            store.putPlaylist(slotKey, resolvedPlaylistUrl, subPlaylistText.toByteArray(Charsets.UTF_8))
        }
        cacheHlsKeys(slotKey, parsed.keyUrls, source)
        val audioSegments = prepareAudioTrackForHls(slotKey, source, capBytes)

        val completed = if (sameStream && existing != null) {
            existing.completedSegmentUrls.filterTo(mutableSetOf()) { store.getSegment(slotKey, it) != null }
        } else {
            mutableSetOf()
        }

        val initialDone = completed.size >= allSegments.size
        val baseMeta = PreBufferSlotMeta(
            streamUrl = source.url,
            isHls = true,
            segmentUrls = allSegments,
            initSegmentUrl = parsed.initSegmentUrl,
            completedSegmentUrls = completed.toSet(),
            keyUrls = parsed.keyUrls,
            isComplete = initialDone,
            qualityLabel = source.qualityLabel,
            serverName = source.serverName,
            referer = source.referer,
            origin = source.origin,
            subtitleUrl = source.subtitleUrl,
            audioUrl = source.audioUrl,
            totalDurationMs = parsed.totalDurationMs,
        )
        store.writeMeta(slotKey, baseMeta)
        emitHlsState(slotKey, capBytes, baseMeta, completed.size, allSegments.size, initialDone)
        if (initialDone) return

        var audioCursor = 0
        for (idx in allSegments.indices) {
            if (!currentCoroutineContext().isActive) break
            val segUrl = allSegments[idx]
            if (segUrl !in completed || store.getSegment(slotKey, segUrl) == null) {
                val bytes = downloadBytesWithRetry(segUrl, source.referer, source.origin) ?: break
                val buf = store.allocateDirectSafely(slotKey, bytes, capBytes) ?: break
                store.putSegment(slotKey, segUrl, buf)
                completed.add(segUrl)
            }

            if (audioSegments.isNotEmpty()) {
                val targetAudioCount = (((idx + 1).toLong() * audioSegments.size) / allSegments.size)
                    .toInt()
                    .coerceIn(0, audioSegments.size)
                while (audioCursor < targetAudioCount && currentCoroutineContext().isActive) {
                    val aUrl = audioSegments[audioCursor]
                    if (store.getSegment(slotKey, aUrl) == null) {
                        val aBytes = downloadBytesWithRetry(aUrl, source.referer, source.origin) ?: break
                        val aBuf = store.allocateDirectSafely(slotKey, aBytes, capBytes) ?: break
                        store.putSegment(slotKey, aUrl, aBuf)
                    }
                    audioCursor++
                }
            }

            val isDone = completed.size >= allSegments.size
            val updatedMeta = baseMeta.copy(
                completedSegmentUrls = completed.toSet(),
                isComplete = isDone,
            )
            store.writeMeta(slotKey, updatedMeta)
            emitHlsState(slotKey, capBytes, updatedMeta, completed.size, allSegments.size, isDone)
        }
    }

    private suspend fun cacheHlsKeys(slotKey: String, keyUrls: List<String>, source: StreamSource) {
        for (keyUrl in keyUrls) {
            if (store.getKey(slotKey, keyUrl) != null) continue
            val keyBytes = downloadBytesWithRetry(keyUrl, source.referer, source.origin)
            if (keyBytes != null && keyBytes.isNotEmpty()) {
                store.putKey(slotKey, keyUrl, keyBytes)
            }
        }
    }

    private suspend fun prepareAudioTrackForHls(
        slotKey: String,
        source: StreamSource,
        capBytes: Long,
    ): List<String> {
        val audioUrl = source.audioUrl?.takeIf { it.isNotBlank() } ?: return emptyList()
        if (audioUrl.substringBefore('?').endsWith(".m3u8", true) || source.isHls) {
            val text = fetchString(audioUrl, source.referer, source.origin)
                ?.also { store.putPlaylist(slotKey, audioUrl, it.toByteArray(Charsets.UTF_8)) }
                ?: store.getPlaylist(slotKey, audioUrl)?.toString(Charsets.UTF_8)
                ?: return emptyList()
            val parsedAudio = HlsPlaylistParser.parseMediaPlaylist(text, audioUrl)
            cacheHlsKeys(slotKey, parsedAudio.keyUrls, source)
            return buildList {
                parsedAudio.initSegmentUrl?.let { add(it) }
                addAll(parsedAudio.segmentUrls)
            }.distinct()
        }
        if (store.getSegment(slotKey, audioUrl) == null) {
            val bytes = downloadBytesWithRetry(audioUrl, source.referer, source.origin, MAX_SINGLE_AUDIO_BYTES)
            if (bytes != null && bytes.isNotEmpty()) {
                store.allocateDirectSafely(slotKey, bytes, capBytes)?.let { buf ->
                    store.putSegment(slotKey, audioUrl, buf)
                }
            }
        }
        return emptyList()
    }

    private fun emitHlsState(
        slotKey: String,
        capBytes: Long,
        meta: PreBufferSlotMeta,
        completedCount: Int,
        totalCount: Int,
        isComplete: Boolean,
    ) {
        val fraction = if (totalCount > 0) {
            (completedCount.toFloat() / totalCount).coerceIn(0f, 1f)
        } else {
            0f
        }
        _state.value = PreBufferState(
            activeSlotKey = slotKey,
            bufferedBytes = store.slotSizeBytes(slotKey),
            capBytes = capBytes,
            estimatedTotalBytes = 0L,
            totalDurationMs = meta.totalDurationMs,
            progressFraction = fraction,
            isComplete = isComplete,
        )
    }

    private suspend fun prefetchMp4(slotKey: String, source: StreamSource, capBytes: Long) {
        if (capBytes <= 0L) return
        val existingMeta = store.readMeta(slotKey)
        val sameStream = existingMeta != null && PreBufferPolicy.isSameStream(existingMeta.streamUrl, source.url)
        if (existingMeta != null && !sameStream) {
            store.clearSlot(slotKey)
        }

        var offset = store.mp4BufferedBytes(slotKey)
        var totalLength = existingMeta?.takeIf { sameStream }?.mp4TotalBytes ?: 0L
        val alreadyDone = existingMeta?.isComplete == true && sameStream && offset > 0L
        if (offset > 0L || alreadyDone) {
            emitMp4State(slotKey, capBytes, offset, totalLength, alreadyDone)
        }

        while (currentCoroutineContext().isActive && offset < capBytes) {
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
                store.clearSlot(slotKey)
                offset = 0L
            }

            val bytesWritten = streamMp4IntoRam(
                input = body.byteStream(),
                slotKey = slotKey,
                initialOffset = offset,
                totalLength = totalLength,
                capBytes = capBytes,
            )
            resp.close()
            if (bytesWritten <= 0L) break

            offset = store.mp4BufferedBytes(slotKey)
            val isDone = (totalLength > 0L && offset >= totalLength) ||
                (respCode == HTTP_OK && offset < capBytes)
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
                ),
            )
            emitMp4State(slotKey, capBytes, offset, totalLength, isDone)
            if (respCode == HTTP_OK) break
        }
    }

    private fun streamMp4IntoRam(
        input: InputStream,
        slotKey: String,
        initialOffset: Long,
        totalLength: Long,
        capBytes: Long,
    ): Long {
        var totalWritten = 0L
        var lastReported = 0L
        val ioBuf = ByteArray(IO_BUFFER_SIZE)
        val sliceOut = ByteArrayOutputStream(MP4_SLICE_BYTES)

        fun flushSlice(): Boolean {
            if (sliceOut.size() == 0) return true
            val bytes = sliceOut.toByteArray()
            sliceOut.reset()
            val direct = store.allocateDirectSafely(slotKey, bytes, capBytes) ?: return false
            store.appendMp4Chunk(slotKey, direct)
            return true
        }

        while (true) {
            val maxRead = (MP4_SLICE_BYTES - sliceOut.size()).coerceAtMost(ioBuf.size).coerceAtLeast(1)
            val read = input.read(ioBuf, 0, maxRead)
            if (read <= 0) break
            val roomInCap = (capBytes - (initialOffset + totalWritten)).coerceAtLeast(0L)
            val usable = minOf(read.toLong(), roomInCap).toInt()
            if (usable <= 0) break

            sliceOut.write(ioBuf, 0, usable)
            totalWritten += usable
            if (sliceOut.size() >= MP4_SLICE_BYTES && !flushSlice()) break
            if (totalWritten - lastReported >= PROGRESS_STEP_BYTES) {
                lastReported = totalWritten
                flushSlice()
                emitMp4State(slotKey, capBytes, initialOffset + totalWritten, totalLength, isComplete = false)
            }
            if (initialOffset + totalWritten >= capBytes) break
        }
        flushSlice()
        return totalWritten
    }

    private fun emitMp4State(
        slotKey: String,
        capBytes: Long,
        offset: Long,
        totalLength: Long,
        isComplete: Boolean,
    ) {
        val denom = if (totalLength > 0L) totalLength else capBytes
        val frac = if (denom > 0L) (offset.toFloat() / denom.toFloat()).coerceIn(0f, 1f) else 0f
        _state.value = PreBufferState(
            activeSlotKey = slotKey,
            bufferedBytes = offset,
            capBytes = capBytes,
            estimatedTotalBytes = totalLength,
            progressFraction = frac,
            isComplete = isComplete,
        )
    }

    private fun parseTotalSizeFromHeaders(contentRange: String?, contentLength: Long): Long {
        if (!contentRange.isNullOrBlank() && contentRange.contains("/")) {
            val total = contentRange.substringAfter("/").trim().toLongOrNull()
            if (total != null && total > 0L) return total
        }
        return if (contentLength > 0L) contentLength else 0L
    }

    private suspend fun executeWithRetry(request: Request): Response? {
        repeat(MAX_RETRY_ATTEMPTS) { attempt ->
            val resp = runCatching { okHttpClient.newCall(request).execute() }.getOrNull()
            if (resp != null && (resp.isSuccessful || resp.code == HTTP_PARTIAL)) {
                return resp
            }
            resp?.close()
            if (attempt < MAX_RETRY_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
        }
        return null
    }

    private suspend fun downloadBytesWithRetry(
        url: String,
        referer: String,
        origin: String,
        maxBytes: Long = Long.MAX_VALUE,
    ): ByteArray? {
        repeat(MAX_RETRY_ATTEMPTS) { attempt ->
            val bytes = runCatching { downloadBytes(url, referer, origin, maxBytes) }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) return bytes
            if (attempt < MAX_RETRY_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
        }
        return null
    }

    private fun downloadBytes(
        url: String,
        referer: String,
        origin: String,
        maxBytes: Long,
    ): ByteArray? {
        val builder = Request.Builder().url(url).get()
        if (referer.isNotBlank()) builder.header("Referer", referer)
        if (origin.isNotBlank()) builder.header("Origin", origin)
        return okHttpClient.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body ?: return@use null
            val len = body.contentLength()
            if (len > maxBytes) return@use null
            val stream = body.byteStream()
            val out = ByteArrayOutputStream(if (len > 0L) len.toInt() else IO_BUFFER_SIZE)
            val buf = ByteArray(IO_BUFFER_SIZE)
            while (true) {
                val read = stream.read(buf)
                if (read <= 0) break
                if (out.size() + read.toLong() > maxBytes) return@use null
                out.write(buf, 0, read)
            }
            out.toByteArray()
        }
    }

    private fun fetchString(url: String, referer: String, origin: String): String? = runCatching {
        val builder = Request.Builder().url(url).get()
        if (referer.isNotBlank()) builder.header("Referer", referer)
        if (origin.isNotBlank()) builder.header("Origin", origin)
        okHttpClient.newCall(builder.build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    }.getOrNull()
}

private class PreBufferDataSource(
    private val networkDelegate: DataSource,
    private val store: InMemoryPreBufferStore,
    private val activeSlotProvider: () -> String?,
    private val offlineCachedOnlyProvider: () -> Boolean,
) : DataSource {

    private var memoryDelegate: DataSource? = null
    private var partialMp4: PartialMp4RamReader? = null
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
            store.getPlaylist(slotKey, url)?.takeIf { dataSpec.position < it.size }?.let { rawBytes ->
                val servedBytes = if (offlineCachedOnlyProvider()) {
                    val trimmed = runCatching {
                        val text = rawBytes.toString(Charsets.UTF_8)
                        HlsPlaylistParser.trimToCachedPlaylist(
                            playlistText = text,
                            baseUrl = url,
                            isVariantCached = { store.getPlaylist(slotKey, it) != null },
                            isSegmentCached = { store.getSegment(slotKey, it) != null },
                        )
                    }.getOrNull()
                    if (!trimmed.isNullOrBlank()) trimmed.toByteArray(Charsets.UTF_8) else rawBytes
                } else {
                    rawBytes
                }
                val mem = ByteArrayDataSource(servedBytes).also { ds ->
                    transferListeners.forEach(ds::addTransferListener)
                }
                memoryDelegate = mem
                activeDelegate = mem
                return mem.open(dataSpec)
            }

            store.getKey(slotKey, url)?.takeIf { dataSpec.position < it.size }?.let { keyBytes ->
                val mem = ByteArrayDataSource(keyBytes).also { ds ->
                    transferListeners.forEach(ds::addTransferListener)
                }
                memoryDelegate = mem
                activeDelegate = mem
                return mem.open(dataSpec)
            }

            store.getSegment(slotKey, url)?.takeIf { dataSpec.position < it.limit() }?.let { buf ->
                val mem = DirectByteBufferDataSource(dataSpec.uri, buf).also { ds ->
                    transferListeners.forEach(ds::addTransferListener)
                }
                memoryDelegate = mem
                activeDelegate = mem
                return mem.open(dataSpec)
            }

            val meta = store.readMeta(slotKey)
            val cachedMp4Len = store.mp4BufferedBytes(slotKey)
            if (meta != null &&
                !meta.isHls &&
                PreBufferPolicy.isSameStream(meta.streamUrl, url) &&
                cachedMp4Len > 0L &&
                dataSpec.position < cachedMp4Len
            ) {
                val reader = PartialMp4RamReader(
                    slotKey = slotKey,
                    store = store,
                    totalBytes = meta.mp4TotalBytes,
                    isComplete = meta.isComplete,
                    originalSpec = dataSpec,
                    networkDelegate = networkDelegate,
                    offlineCachedOnlyProvider = offlineCachedOnlyProvider,
                )
                partialMp4 = reader
                activeDelegate = null
                return reader.open()
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
        runCatching {
            if (activeDelegate === networkDelegate) networkDelegate.close()
        }
        partialMp4 = null
        memoryDelegate = null
        activeDelegate = null
    }
}

private class DirectByteBufferDataSource(
    private val sourceUri: Uri,
    private val buffer: ByteBuffer,
) : DataSource {
    private var bytesRemaining = 0

    override fun addTransferListener(transferListener: TransferListener) = Unit

    override fun open(dataSpec: DataSpec): Long {
        val total = buffer.limit()
        if (dataSpec.position >= total) {
            bytesRemaining = 0
            return 0L
        }
        buffer.position(dataSpec.position.toInt())
        val available = buffer.remaining()
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(available.toLong(), dataSpec.length).toInt()
        } else {
            available
        }
        return bytesRemaining.toLong()
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining <= 0) return C.RESULT_END_OF_INPUT
        val toRead = minOf(length, bytesRemaining)
        buffer.get(target, offset, toRead)
        bytesRemaining -= toRead
        return toRead
    }

    override fun getUri(): Uri = sourceUri

    override fun close() {
        bytesRemaining = 0
    }
}

private class PartialMp4RamReader(
    private val slotKey: String,
    private val store: InMemoryPreBufferStore,
    private val totalBytes: Long,
    private val isComplete: Boolean,
    private val originalSpec: DataSpec,
    private val networkDelegate: DataSource,
    private val offlineCachedOnlyProvider: () -> Boolean,
) {
    private var currentPos: Long = originalSpec.position
    private var remainingSpecBytes: Long = originalSpec.length
    private var networkOpened: Boolean = false
    private var networkExhausted: Boolean = false

    val uri: Uri get() = originalSpec.uri

    fun open(): Long {
        val cachedLen = store.mp4BufferedBytes(slotKey)
        val offlineBound = isComplete || offlineCachedOnlyProvider()
        return when {
            offlineBound -> {
                val avail = (cachedLen - currentPos).coerceAtLeast(0L)
                if (originalSpec.length != C.LENGTH_UNSET.toLong()) {
                    minOf(originalSpec.length, avail)
                } else {
                    avail
                }
            }
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

        val cachedLen = store.mp4BufferedBytes(slotKey)
        if (currentPos < cachedLen) {
            val fileAvail = (cachedLen - currentPos).coerceAtMost(maxRead.toLong()).toInt()
            val n = store.readMp4Bytes(slotKey, currentPos, buffer, offset, fileAvail)
            if (n > 0) {
                currentPos += n
                if (remainingSpecBytes != C.LENGTH_UNSET.toLong()) remainingSpecBytes -= n
                return n
            }
        }

        if (isComplete || networkExhausted || offlineCachedOnlyProvider() || (totalBytes > 0L && currentPos >= totalBytes)) {
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
        if (networkOpened) {
            runCatching { networkDelegate.close() }
            networkOpened = false
        }
    }
}
