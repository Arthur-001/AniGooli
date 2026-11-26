package com.anigooli.downloads

/** Parsed HLS media playlist containing optional initialization segment and ordered media segments. */
data class HlsMediaPlaylist(
    /** Optional `#EXT-X-MAP` initialization segment URL (for fragmented MP4 streams). */
    val initSegmentUrl: String?,
    /** Ordered list of media segment URLs. */
    val segmentUrls: List<String>,
    /** Whether the playlist is a master playlist pointing to sub-playlists instead of segments. */
    val isMasterPlaylist: Boolean = false,
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
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXT-X-MAP:") && initUrl == null) {
                val rawUri = mapUriRegex.find(line)?.groupValues?.getOrNull(1)
                if (!rawUri.isNullOrBlank()) {
                    initUrl = resolveUrl(rawUri, baseUrl)
                }
            } else if (!line.startsWith("#")) {
                segments.add(resolveUrl(line, baseUrl))
            }
        }
        return HlsMediaPlaylist(initSegmentUrl = initUrl, segmentUrls = segments, isMasterPlaylist = false)
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
}
