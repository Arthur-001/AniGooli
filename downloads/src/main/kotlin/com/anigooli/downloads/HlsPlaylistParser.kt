package com.anigooli.downloads

/** Parsed HLS media playlist containing optional initialization segment, keys, and ordered media segments. */
data class HlsMediaPlaylist(
    /** Optional `#EXT-X-MAP` initialization segment URL (for fragmented MP4 streams). */
    val initSegmentUrl: String?,
    /** Ordered list of media segment URLs. */
    val segmentUrls: List<String>,
    /** Whether the playlist is a master playlist pointing to sub-playlists instead of segments. */
    val isMasterPlaylist: Boolean = false,
    /** Distinct `#EXT-X-KEY` encryption key URIs referenced by this playlist. */
    val keyUrls: List<String> = emptyList(),
    /** Sum of `#EXTINF` segment durations in milliseconds, or 0 if not declared. */
    val totalDurationMs: Long = 0L,
)

/** Parses HLS `.m3u8` media playlists into absolute segment URLs. */
object HlsPlaylistParser {

    private val mapUriRegex = Regex("""URI="([^"]+)"""")
    private val originRegex = Regex("""^(https?://[^/]+)""", RegexOption.IGNORE_CASE)

    /** Parses [playlistText] resolved relative to [baseUrl]. */
    fun parseMediaPlaylist(playlistText: String, baseUrl: String): HlsMediaPlaylist {
        val normalized = playlistText.replace("\r", "")
        if (!normalized.contains("#EXTM3U")) {
            return HlsMediaPlaylist(initSegmentUrl = null, segmentUrls = emptyList())
        }
        val lines = normalized.split("\n")
        if (lines.any { it.trim().startsWith("#EXT-X-STREAM-INF:") }) {
            val firstVariant = extractFirstMasterVariantUrl(lines, baseUrl)
            return HlsMediaPlaylist(
                initSegmentUrl = null,
                segmentUrls = listOfNotNull(firstVariant),
                isMasterPlaylist = true,
            )
        }

        var initUrl: String? = null
        val segments = ArrayList<String>()
        val keys = LinkedHashSet<String>()
        var totalDurationMs = 0L
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXT-X-MAP:") && initUrl == null) {
                val rawUri = mapUriRegex.find(line)?.groupValues?.getOrNull(1)
                if (!rawUri.isNullOrBlank()) {
                    initUrl = resolveUrl(rawUri, baseUrl)
                }
            } else if (line.startsWith("#EXT-X-KEY:")) {
                val rawUri = mapUriRegex.find(line)?.groupValues?.getOrNull(1)
                if (!rawUri.isNullOrBlank()) {
                    val resolvedKey = resolveUrl(rawUri, baseUrl)
                    if (resolvedKey.isNotEmpty()) keys.add(resolvedKey)
                }
            } else if (line.startsWith("#EXTINF:")) {
                val sec = line.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull()
                if (sec != null && sec > 0.0) {
                    totalDurationMs += (sec * 1000.0).toLong()
                }
            } else if (!line.startsWith("#")) {
                segments.add(resolveUrl(line, baseUrl))
            }
        }
        return HlsMediaPlaylist(
            initSegmentUrl = initUrl,
            segmentUrls = segments,
            isMasterPlaylist = false,
            keyUrls = keys.toList(),
            totalDurationMs = totalDurationMs,
        )
    }

    private fun extractFirstMasterVariantUrl(lines: List<String>, baseUrl: String): String? {
        for (i in lines.indices) {
            if (lines[i].trim().startsWith("#EXT-X-STREAM-INF:")) {
                for (j in (i + 1) until lines.size) {
                    val candidate = lines[j].trim()
                    if (candidate.isNotEmpty() && !candidate.startsWith("#")) {
                        return resolveUrl(candidate, baseUrl)
                    }
                }
            }
        }
        return null
    }

    /** Resolves relative or protocol-relative [uri] against [baseUrl]. */
    fun resolveUrl(uri: String, baseUrl: String): String {
        val trimmed = uri.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return trimmed
        if (trimmed.startsWith("//")) return "https:$trimmed"
        val origin = originRegex.find(baseUrl)?.groupValues?.getOrNull(1).orEmpty()
        if (trimmed.startsWith("/")) return "$origin$trimmed"
        val baseNoQuery = baseUrl.substringBefore('?')
        val lastSlash = baseNoQuery.lastIndexOf('/')
        val baseDir = if (lastSlash >= 0) baseNoQuery.substring(0, lastSlash + 1) else "$origin/"
        return "$baseDir$trimmed"
    }

    /**
     * Rewrites [playlistText] into an offline-playable playlist containing only cached variants
     * (for master playlists) or the contiguous prefix of cached segments (for media playlists).
     */
    fun trimToCachedPlaylist(
        playlistText: String,
        baseUrl: String,
        isVariantCached: (String) -> Boolean,
        isSegmentCached: (String) -> Boolean,
    ): String {
        val normalized = playlistText.replace("\r", "")
        val lines = normalized.split("\n")
        if (lines.any { it.trim().startsWith("#EXT-X-STREAM-INF:") }) {
            val out = ArrayList<String>()
            var i = 0
            var keptAny = false
            while (i < lines.size) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    var uriIdx = i + 1
                    while (uriIdx < lines.size && (lines[uriIdx].trim().isEmpty() || lines[uriIdx].trim().startsWith("#"))) {
                        uriIdx++
                    }
                    val rawUri = lines.getOrNull(uriIdx)?.trim().orEmpty()
                    val resolved = if (rawUri.isNotEmpty()) resolveUrl(rawUri, baseUrl) else ""
                    if (resolved.isNotEmpty() && isVariantCached(resolved)) {
                        out.add(lines[i])
                        out.add(resolved)
                        keptAny = true
                        break
                    }
                    i = uriIdx + 1
                    continue
                }
                if (line.isNotEmpty()) out.add(lines[i])
                i++
            }
            if (!keptAny) return ""
            return out.joinToString("\n")
        }

        val out = ArrayList<String>()
        val pendingSegmentMeta = ArrayList<String>()
        var keptSegments = 0
        var stopped = false
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXT-X-ENDLIST")) continue
            if (line.startsWith("#EXT-X-MAP:")) {
                val rawUri = mapUriRegex.find(line)?.groupValues?.getOrNull(1)
                if (!rawUri.isNullOrBlank()) {
                    val resolved = resolveUrl(rawUri, baseUrl)
                    out.add(line.replace(rawUri, resolved))
                } else {
                    out.add(line)
                }
            } else if (line.startsWith("#EXT-X-KEY:")) {
                val rawUri = mapUriRegex.find(line)?.groupValues?.getOrNull(1)
                val resolvedLine = if (!rawUri.isNullOrBlank()) {
                    line.replace(rawUri, resolveUrl(rawUri, baseUrl))
                } else {
                    line
                }
                pendingSegmentMeta.add(resolvedLine)
            } else if (line.startsWith("#EXTINF:") || line.startsWith("#EXT-X-BYTERANGE:") || line.startsWith("#EXT-X-DISCONTINUITY")) {
                pendingSegmentMeta.add(line)
            } else if (line.startsWith("#")) {
                out.add(line)
            } else {
                val resolved = resolveUrl(line, baseUrl)
                if (!isSegmentCached(resolved)) {
                    stopped = true
                    break
                }
                out.addAll(pendingSegmentMeta)
                pendingSegmentMeta.clear()
                out.add(resolved)
                keptSegments++
            }
        }
        if (keptSegments == 0) return ""
        if (stopped || !lines.any { it.trim() == "#EXT-X-ENDLIST" }) {
            out.add("#EXT-X-ENDLIST")
        }
        return out.joinToString("\n")
    }
}
