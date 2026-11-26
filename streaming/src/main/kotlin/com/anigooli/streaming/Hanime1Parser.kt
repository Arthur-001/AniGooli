package com.anigooli.streaming

/**
 * Pure Kotlin parser and URL builder for the `hanime1.me` adult anime streaming provider.
 */
object Hanime1Parser {
    /** Prefix added to provider IDs belonging to the `hanime1.me` backend. */
    const val PROVIDER_PREFIX = "hanime1:"

    /** Base URL of the `hanime1.me` backend. */
    const val BASE_URL = "https://hanime1.me"

    /** Browser User-Agent used when querying `hanime1.me`. */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val cardSplitRegex = Regex("""class="video-item-container"""", RegexOption.IGNORE_CASE)
    private val titleAttrRegex = Regex("""title="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val watchHrefRegex = Regex(
        """href="https://hanime1\.me/watch\?v=(\d+)"""",
        RegexOption.IGNORE_CASE,
    )
    private val thumbRegex = Regex(
        """<img[^>]+class="main-thumb"[^>]+src="([^"]+)"""",
        RegexOption.IGNORE_CASE,
    )
    private val sourceTagRegex = Regex(
        """<source[^>]+src="(https?://[^"]+\.(?:mp4|m3u8)[^"]*)"[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val sizeAttrRegex = Regex("""size="(\d+)"""", RegexOption.IGNORE_CASE)

    /** Returns true if [providerId] belongs to the `hanime1.me` backend. */
    fun isHanime1ProviderId(providerId: String): Boolean = providerId.startsWith(PROVIDER_PREFIX)

    /** Prepends the `hanime1:` prefix to a numeric [videoId]. */
    fun toProviderId(videoId: String): String = "$PROVIDER_PREFIX${videoId.removePrefix(PROVIDER_PREFIX)}"

    /** Extracts the numeric `hanime1.me` video ID from a prefixed [providerId]. */
    fun extractVideoId(providerId: String): String = providerId.removePrefix(PROVIDER_PREFIX)

    /** Parses `hanime1.me/search?query=...` HTML into [ProviderAnime] items. */
    fun parseSearchHtml(html: String): List<ProviderAnime> {
        val parts = html.split(cardSplitRegex)
        val seen = HashSet<String>()
        val results = ArrayList<ProviderAnime>()

        for (i in 0 until parts.size - 1) {
            val headerSlice = parts[i].takeLast(240)
            val bodySlice = parts[i + 1].take(800)
            val videoId = watchHrefRegex.find(bodySlice)?.groupValues?.getOrNull(1)?.trim() ?: continue
            val rawTitle = titleAttrRegex.findAll(headerSlice).lastOrNull()?.groupValues?.getOrNull(1)?.trim()
                ?: continue
            val title = unescapeHtml(rawTitle)
            if (videoId.isEmpty() || title.isEmpty()) continue

            val providerId = toProviderId(videoId)
            if (!seen.add(providerId)) continue
            val cover = thumbRegex.find(bodySlice)?.groupValues?.getOrNull(1)?.trim()

            results.add(
                ProviderAnime(
                    providerId = providerId,
                    title = title,
                    englishTitle = title,
                    coverImage = cover,
                    episodeCount = 1,
                ),
            )
        }
        return results
    }

    /** Extracts direct `.mp4` or `.m3u8` stream candidates from a `hanime1.me/watch?v=<id>` page. */
    fun parseWatchPageCandidates(html: String): MediaCandidateList {
        val candidates = sourceTagRegex.findAll(html)
            .map { match ->
                val url = unescapeHtml(match.groupValues[1].trim())
                val size = sizeAttrRegex.find(match.value)?.groupValues?.getOrNull(1)
                val quality = if (size != null) "${size}p" else "720p"
                MediaCandidate(
                    server = "Hanime1",
                    url = url,
                    format = if (url.contains(".m3u8", ignoreCase = true)) "hls" else "mp4",
                    quality = quality,
                    referer = "$BASE_URL/",
                )
            }
            .distinctBy { it.url }
            .toList()
        return MediaCandidateList(candidates = candidates)
    }

    private fun unescapeHtml(raw: String): String = raw
        .replace("&#038;", "&")
        .replace("&#039;", "'")
        .replace("&#x27;", "'")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}
