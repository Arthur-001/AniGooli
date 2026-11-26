package com.anigooli.streaming

import kotlinx.serialization.Serializable

/** Parsed raw stream candidate extracted from an episode page prior to HLS expansion. */
@Serializable
data class MediaCandidate(
    /** Upstream server display name. */
    val server: String,
    /** Stream URL (MP4 or master m3u8 playlist). */
    val url: String,
    /** Stream format: "hls" or "mp4". */
    val format: String,
    /** Quality hint if specified on the stream entry. */
    val quality: String,
    /** Optional HTTP Referer header. */
    val referer: String = "",
    /** Optional HTTP Origin header. */
    val origin: String = "",
    /** Optional English subtitle file URL. */
    val subtitleUrl: String? = null,
)

/** Wrapper around extracted episode media candidates. */
@Serializable
data class MediaCandidateList(
    /** Extracted stream candidates. */
    val candidates: List<MediaCandidate> = emptyList(),
)

/** Parsed HLS variant entry. */
@Serializable
data class M3u8Variant(
    /** Quality label such as "1080p" or "default". */
    val quality: String,
    /** Vertical resolution in pixels, or 0 if unknown. */
    val height: Int,
    /** Absolute media playlist URL. */
    val url: String,
)

/** Parsed HLS master playlist output. */
@Serializable
data class M3u8ParseResult(
    /** Optional separate default English/primary audio playlist URL. */
    val audioUrl: String? = null,
    /** Video variants ordered highest resolution first. */
    val variants: List<M3u8Variant> = emptyList(),
)

/**
 * Pure Kotlin fallback parser matching `scripts/extractor.js`.
 * Used when AndroidX [androidx.javascriptengine.JavaScriptSandbox] is unavailable on a device.
 */
@Suppress("TooManyFunctions", "NestedBlockDepth")
object NativeMiruroParser {

    private val itemsMarkerRegex = Regex(""""?items"?\s*:\s*\[""")
    private val extIdsRegex = Regex(""""?external_ids"?\s*:\s*\{""")
    private val titleBlockRegex = Regex(""""?title"?\s*:\s*\{""")
    private val episodeNumRegex = Regex("""(?:[,{;]"?number"?\s*:|\.number\s*=)\s*(\d+(?:\.\d+)?)""")
    private val tracksMarkerRegex = Regex(""""?tracks"?\s*:\s*\[""")
    private val subArrayRegex = Regex(""""?subtitles"?\s*:\s*\[""")
    private val subVarRegex = Regex(""""?subtitles"?\s*:\s*([a-zA-Z_$][a-zA-Z0-9_$]*)\s*[,}]""")
    private val providersRegex = Regex(""""?providers"?\s*:\s*\[""")
    private val serversRegex = Regex(""""?servers"?\s*:\s*\[""")
    private val streamsRegex = Regex(""""?streams"?\s*:\s*\[""")
    private val defaultTrueRegex = Regex("""(?:^|[,{\s])"?default"?\s*:\s*true(?:[,}\s]|$)""")
    private val enLangRegex = Regex("""^(?:en(?:[-_][a-zA-Z]+|g)?|.*english.*)$""", RegexOption.IGNORE_CASE)
    private val originRegex = Regex("""^(https?://[^/]+)""", RegexOption.IGNORE_CASE)
    private val uriAttrRegex = Regex("""URI="([^"]*)"""")
    private val resolutionAttrRegex = Regex("""RESOLUTION=\d+x(\d+)""")

    /** Parses miruro search HTML into a list of [ProviderAnime]. */
    fun parseSearchResponse(html: String): List<ProviderAnime> {
        val marker = itemsMarkerRegex.find(html) ?: return emptyList()
        val arraySlice = extractBalancedSlice(html, marker.range.last, '[', ']')
        if (arraySlice.length < 2) return emptyList()
        val objects = splitTopLevelObjects(arraySlice.substring(1, arraySlice.length - 1))
        val seen = HashSet<String>()
        val results = ArrayList<ProviderAnime>()

        for (item in objects) {
            val providerId = matchStringProp(item, "id")
            if (providerId.isEmpty() || !seen.add(providerId)) continue
            val extSlice = extIdsRegex.find(item)?.let { m ->
                extractBalancedSlice(item, item.indexOf('{', m.range.first), '{', '}')
            } ?: item
            val anilistId = matchArrayFirstString(extSlice, "anilist").toIntOrNull()
            val malId = matchArrayFirstString(extSlice, "mal").toIntOrNull()
            val titleSlice = titleBlockRegex.find(item)?.let { m ->
                extractBalancedSlice(item, item.indexOf('{', m.range.first), '{', '}')
            } ?: item
            val eng = matchStringProp(titleSlice, "english")
            val rom = matchStringProp(titleSlice, "romaji")
            val nat = matchStringProp(titleSlice, "native")
            val display = eng.ifEmpty { rom.ifEmpty { nat } }
            if (display.isEmpty()) continue
            results.add(
                ProviderAnime(
                    providerId = providerId,
                    anilistId = anilistId,
                    malId = malId,
                    title = display,
                    englishTitle = eng.ifEmpty { null },
                    romajiTitle = rom.ifEmpty { null },
                )
            )
        }
        return results
    }

    /** Parses episode numbers from a miruro watch page and returns them sorted numerically. */
    fun parseEpisodesResponse(html: String): List<String> {
        val seen = LinkedHashSet<String>()
        episodeNumRegex.findAll(html).forEach { seen.add(it.groupValues[1]) }
        return seen.sortedBy { it.toDoubleOrNull() ?: Double.MAX_VALUE }
    }

    /** Parses candidate stream URLs and subtitle links from a miruro episode page. */
    fun parseMediaResponse(html: String, mode: String): MediaCandidateList {
        val marker = tracksMarkerRegex.find(html) ?: return MediaCandidateList()
        val hoisted = html.substring(0, marker.range.first)
        val tracksSlice = extractBalancedSlice(html, marker.range.last, '[', ']')
        if (tracksSlice.length < 2) return MediaCandidateList()
        val trackObjects = splitTopLevelObjects(tracksSlice.substring(1, tracksSlice.length - 1))
        val wanted = if (mode.equals("dub", ignoreCase = true)) {
            listOf("dub")
        } else {
            listOf("ssub", "sub")
        }
        val candidates = ArrayList<MediaCandidate>()
        for (kind in wanted) {
            val matchedTrack = trackObjects.firstOrNull { matchStringProp(it, "track") == kind } ?: continue
            collectCandidatesFromTrack(matchedTrack, hoisted, candidates)
            if (candidates.isNotEmpty()) break
        }
        return MediaCandidateList(candidates)
    }

    /** Parses an HLS master playlist into resolved variants and optional audio track URL. */
    fun parseM3u8(playlistText: String, baseUrl: String): M3u8ParseResult? {
        val normalized = playlistText.replace("\r", "")
        if (!normalized.contains("#EXTM3U")) return null
        val lines = normalized.split("\n")
        var defaultAudio = ""
        var fallbackAudio = ""
        val variants = ArrayList<M3u8Variant>()

        var index = 0
        while (index < lines.size) {
            val line = lines[index].trim()
            if (line.startsWith("#EXT-X-MEDIA:") && line.contains("TYPE=AUDIO")) {
                val uri = uriAttrRegex.find(line)?.groupValues?.getOrNull(1).orEmpty()
                if (uri.isNotEmpty()) {
                    val resolved = resolveUrl(uri, baseUrl)
                    if (line.contains("DEFAULT=YES") && defaultAudio.isEmpty()) {
                        defaultAudio = resolved
                    } else if (fallbackAudio.isEmpty()) {
                        fallbackAudio = resolved
                    }
                }
            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                val height = resolutionAttrRegex.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                val quality = if (height > 0) "${height}p" else "default"
                var nextIdx = index + 1
                while (nextIdx < lines.size) {
                    val nextLine = lines[nextIdx].trim()
                    if (nextLine.isNotEmpty() && !nextLine.startsWith("#")) {
                        variants.add(M3u8Variant(quality, height, resolveUrl(nextLine, baseUrl)))
                        index = nextIdx
                        break
                    }
                    nextIdx++
                }
            }
            index++
        }

        if (variants.isEmpty()) {
            variants.add(M3u8Variant("default", 0, baseUrl))
        } else {
            variants.sortByDescending { it.height }
        }
        val audio = defaultAudio.ifEmpty { fallbackAudio }.ifEmpty { null }
        return M3u8ParseResult(audioUrl = audio, variants = variants)
    }

    private fun collectCandidatesFromTrack(
        trackBlock: String,
        hoisted: String,
        out: MutableList<MediaCandidate>,
    ) {
        val provObjects = extractNestedObjects(trackBlock, providersRegex).ifEmpty { listOf(trackBlock) }
        for (prov in provObjects) {
            val subUrl = pickEnglishSubtitle(prov, hoisted)
            val srvObjects = extractNestedObjects(prov, serversRegex).ifEmpty { listOf(prov) }
            for (srv in srvObjects) {
                val serverName = matchStringProp(srv, "server")
                    .ifEmpty { matchStringProp(srv, "name") }
                    .ifEmpty { "Server" }
                val referer = matchStringProp(srv, "Referer").ifEmpty { matchStringProp(srv, "referer") }
                val origin = matchStringProp(srv, "Origin")
                    .ifEmpty { matchStringProp(srv, "origin") }
                    .ifEmpty { deriveOrigin(referer) }
                val streamObjects = extractNestedObjects(srv, streamsRegex)
                for (stream in streamObjects) {
                    val streamUrl = matchStringProp(stream, "url")
                    if (streamUrl.isEmpty()) continue
                    val fmt = matchStringProp(stream, "format").lowercase()
                    val isHls = fmt.contains("hls") || streamUrl.substringBefore('?').endsWith(".m3u8", true)
                    val quality = matchStringProp(stream, "quality").ifEmpty { "default" }
                    out.add(
                        MediaCandidate(
                            server = serverName,
                            url = streamUrl,
                            format = if (isHls) "hls" else "mp4",
                            quality = quality,
                            referer = referer,
                            origin = origin,
                            subtitleUrl = subUrl,
                        )
                    )
                }
            }
        }
    }

    private fun extractNestedObjects(container: String, markerRegex: Regex): List<String> {
        val m = markerRegex.find(container) ?: return emptyList()
        val slice = extractBalancedSlice(container, m.range.last, '[', ']')
        if (slice.length < 2) return emptyList()
        return splitTopLevelObjects(slice.substring(1, slice.length - 1))
    }

    private fun pickEnglishSubtitle(provBlock: String, hoisted: String): String? {
        val subSlice = subArrayRegex.find(provBlock)?.let { m ->
            extractBalancedSlice(provBlock, m.range.last, '[', ']')
        } ?: run {
            val varName = subVarRegex.find(provBlock)?.groupValues?.getOrNull(1) ?: return null
            val escaped = Regex.escape(varName)
            val hoistedRe = Regex("""$escaped\[\d+\]\s*=\s*\{[^;]*""")
            val entries = hoistedRe.findAll(hoisted).map { it.value }.toList()
            if (entries.isEmpty()) "" else "[${entries.joinToString(",")}]"
        }
        if (subSlice.length < 2) return null
        val subObjects = splitTopLevelObjects(subSlice.substring(1, subSlice.length - 1))
        var fallback: String? = null
        for (obj in subObjects) {
            val lang = matchStringProp(obj, "language").ifEmpty { matchStringProp(obj, "lang") }
            val label = matchStringProp(obj, "label")
            val file = matchStringProp(obj, "file").ifEmpty { matchStringProp(obj, "url") }
            if (file.isEmpty()) continue
            if (!enLangRegex.matches(lang) && !enLangRegex.matches(label)) continue
            if (defaultTrueRegex.containsMatchIn(obj)) return file
            if (fallback == null) fallback = file
        }
        return fallback
    }

    private fun deriveOrigin(url: String): String = originRegex.find(url)?.groupValues?.getOrNull(1).orEmpty()

    private fun resolveUrl(uri: String, baseUrl: String): String {
        val trimmed = uri.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return trimmed
        if (trimmed.startsWith("//")) return "https:$trimmed"
        val origin = deriveOrigin(baseUrl)
        if (trimmed.startsWith("/")) return "$origin$trimmed"
        val baseNoQuery = baseUrl.substringBefore('?')
        val lastSlash = baseNoQuery.lastIndexOf('/')
        val baseDir = if (lastSlash >= 0) baseNoQuery.substring(0, lastSlash + 1) else "$origin/"
        return "$baseDir$trimmed"
    }

    private fun matchStringProp(block: String, key: String): String {
        val re = Regex("""(?:^|[,{\s;])"?($key)"?\s*:\s*"([^"]*)"""")
        return re.find(block)?.groupValues?.getOrNull(2).orEmpty()
    }

    private fun matchArrayFirstString(block: String, key: String): String {
        val re = Regex("""(?:^|[,{\s;])"?($key)"?\s*:\s*\[\s*"([^"]+)"""")
        return re.find(block)?.groupValues?.getOrNull(2).orEmpty()
    }

    private fun extractBalancedSlice(
        text: String,
        startIdx: Int,
        openChar: Char,
        closeChar: Char,
    ): String {
        if (startIdx !in text.indices) return ""
        var depth = 0
        var inString = false
        var quote = ' '
        var escaped = false
        for (i in startIdx until text.length) {
            val ch = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == quote) {
                    inString = false
                }
                continue
            }
            if (ch == '"' || ch == '\'') {
                inString = true
                quote = ch
            } else if (ch == openChar) {
                depth++
            } else if (ch == closeChar) {
                depth--
                if (depth == 0) return text.substring(startIdx, i + 1)
            }
        }
        return text.substring(startIdx)
    }

    private fun splitTopLevelObjects(inner: String): List<String> {
        val result = ArrayList<String>()
        var depth = 0
        var inString = false
        var quote = ' '
        var escaped = false
        var start = -1
        for (i in inner.indices) {
            val ch = inner[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == quote) {
                    inString = false
                }
                continue
            }
            if (ch == '"' || ch == '\'') {
                inString = true
                quote = ch
            } else if (ch == '{') {
                if (depth == 0) start = i
                depth++
            } else if (ch == '}') {
                depth--
                if (depth == 0 && start >= 0) {
                    result.add(inner.substring(start, i + 1))
                    start = -1
                }
            }
        }
        return result
    }
}
