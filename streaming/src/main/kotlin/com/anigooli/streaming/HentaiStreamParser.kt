package com.anigooli.streaming

/**
 * Pure Kotlin parser and URL builder for the `hentai.stream` adult anime streaming provider.
 */
object HentaiStreamParser {
    /** Prefix added to provider IDs belonging to the `hentai.stream` backend. */
    const val PROVIDER_PREFIX = "hentaistream:"

    /** Base URL of the `hentai.stream` backend. */
    const val BASE_URL = "https://hentai.stream"

    /** Browser User-Agent used when querying `hentai.stream`. */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val cardSplitRegex = Regex("""<article\s+class="vv-card"[^>]*>""", RegexOption.IGNORE_CASE)
    private val videoHrefRegex = Regex("""href="/video/([^"/]+)/?"""", RegexOption.IGNORE_CASE)
    private val imgSrcRegex = Regex("""<img[^>]+src="([^"]+)"[^>]*>""", RegexOption.IGNORE_CASE)
    private val headingTitleRegex = Regex("""<h3[^>]*>\s*<a[^>]*>([^<]+)</a>""", RegexOption.IGNORE_CASE)
    private val altTitleRegex = Regex("""alt="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val mediaUrlRegex = Regex("""(?:https?://[^"'\s<>]+|/media/video/[^"'\s<>]+)\.(?:mp4|m3u8)(?:\?[^"'\s<>]*)?""", RegexOption.IGNORE_CASE)

    /** Returns true if [providerId] belongs to the `hentai.stream` backend. */
    fun isHentaiStreamProviderId(providerId: String): Boolean = providerId.startsWith(PROVIDER_PREFIX)

    /** Prepends the `hentaistream:` prefix to a raw [slug]. */
    fun toProviderId(slug: String): String = "$PROVIDER_PREFIX${slug.removePrefix(PROVIDER_PREFIX)}"

    /** Extracts the raw `hentai.stream` slug from a prefixed [providerId]. */
    fun extractSlug(providerId: String): String = providerId.removePrefix(PROVIDER_PREFIX)

    /** Parses `hentai.stream/search?q=...` HTML into [ProviderAnime] items. */
    fun parseSearchHtml(html: String): List<ProviderAnime> {
        val blocks = html.split(cardSplitRegex).drop(1)
        val seen = HashSet<String>()
        val results = ArrayList<ProviderAnime>()

        for (block in blocks) {
            val slug = videoHrefRegex.find(block)?.groupValues?.getOrNull(1)?.trim() ?: continue
            val rawTitle = headingTitleRegex.find(block)?.groupValues?.getOrNull(1)?.trim()
                ?: altTitleRegex.find(block)?.groupValues?.getOrNull(1)?.trim()
                ?: continue
            val title = unescapeHtml(rawTitle)
            if (slug.isEmpty() || title.isEmpty()) continue

            val providerId = toProviderId(slug)
            if (!seen.add(providerId)) continue

            val rawImg = imgSrcRegex.find(block)?.groupValues?.getOrNull(1)?.trim()
            val cover = when {
                rawImg == null -> null
                rawImg.startsWith("http") -> rawImg
                rawImg.startsWith("/") -> "$BASE_URL$rawImg"
                else -> "$BASE_URL/$rawImg"
            }

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

    /** Extracts direct `.mp4` or `.m3u8` stream candidates from a `hentai.stream/video/<slug>/` page. */
    fun parseVideoPageCandidates(html: String): MediaCandidateList {
        val urls = mediaUrlRegex.findAll(html)
            .map { match ->
                val raw = match.value.trim()
                if (raw.startsWith("/")) "$BASE_URL$raw" else raw
            }
            .distinct()
            .toList()
        if (urls.isEmpty()) return MediaCandidateList()

        val candidates = urls.map { url ->
            val isHls = url.contains(".m3u8", ignoreCase = true)
            MediaCandidate(
                server = "HentaiStream",
                url = url,
                format = if (isHls) "hls" else "mp4",
                quality = "1080p",
                referer = "$BASE_URL/",
            )
        }
        return MediaCandidateList(candidates = candidates)
    }

    private fun unescapeHtml(raw: String): String = raw
        .replace("&#039;", "'")
        .replace("&#x27;", "'")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}
