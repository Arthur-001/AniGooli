package com.anigooli.streaming

/** Episode entry parsed from `hentaimama.io` show page. */
data class HentaimamaEpisode(
    /** Display episode number string (e.g. "1"). */
    val episodeNumber: String,
    /** Full episode page URL. */
    val episodeUrl: String,
)

/**
 * Pure Kotlin parser and URL builder for the `hentaimama.io` adult anime streaming provider.
 */
object HentaimamaParser {
    /** Prefix added to provider IDs belonging to the `hentaimama.io` backend. */
    const val PROVIDER_PREFIX = "hentaimama:"

    /** Base URL of the `hentaimama.io` backend. */
    const val BASE_URL = "https://hentaimama.io"

    /** Browser User-Agent used when querying `hentaimama.io`. */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val tvShowHrefRegex = Regex(
        """href="(https://hentaimama\.io/tvshows/([^"/]+)/?)"""",
        RegexOption.IGNORE_CASE,
    )
    private val titleDivRegex = Regex(
        """<div\s+class="title"[^>]*>\s*<a[^>]*>([^<]+)</a>""",
        RegexOption.IGNORE_CASE,
    )
    private val altTitleRegex = Regex("""alt="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val imgSrcRegex = Regex("""<img[^>]+src="(https?://[^"]+)"[^>]*>""", RegexOption.IGNORE_CASE)
    private val episodeHrefRegex = Regex(
        """href="(https://hentaimama\.io/episodes/([^"/]+)/?)"""",
        RegexOption.IGNORE_CASE,
    )
    private val episodeNumSuffixRegex = Regex("""-episode-(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val idPostRegex = Regex("""name="idpost"\s+value="(\d+)"""", RegexOption.IGNORE_CASE)
    private val embedSrcRegex = Regex(
        """src="(https://hentaimama\.io/\?dt_embed=[^"]+)"""",
        RegexOption.IGNORE_CASE,
    )
    private val jwpFileRegex = Regex(
        """"file"\s*:\s*"(https?://[^"]+\.(?:mp4|m3u8)[^"]*)"""",
        RegexOption.IGNORE_CASE,
    )
    private val rawMediaRegex = Regex(
        """https?://[^"'\s<>]+\.(?:mp4|m3u8)(?:\?[^"'\s<>]*)?""",
        RegexOption.IGNORE_CASE,
    )

    /** Returns true if [providerId] belongs to the `hentaimama.io` backend. */
    fun isHentaimamaProviderId(providerId: String): Boolean = providerId.startsWith(PROVIDER_PREFIX)

    /** Prepends the `hentaimama:` prefix to a raw [slug]. */
    fun toProviderId(slug: String): String = "$PROVIDER_PREFIX${slug.removePrefix(PROVIDER_PREFIX)}"

    /** Extracts the raw `hentaimama.io` slug from a prefixed [providerId]. */
    fun extractSlug(providerId: String): String = providerId.removePrefix(PROVIDER_PREFIX)

    /** Parses `hentaimama.io/?s=...` HTML into [ProviderAnime] items. */
    fun parseSearchHtml(html: String): List<ProviderAnime> {
        val blocks = html.split("<article").drop(1)
        val seen = HashSet<String>()
        val results = ArrayList<ProviderAnime>()

        for (block in blocks) {
            val slug = tvShowHrefRegex.find(block)?.groupValues?.getOrNull(2)?.trim() ?: continue
            val rawTitle = titleDivRegex.find(block)?.groupValues?.getOrNull(1)?.trim()
                ?: altTitleRegex.find(block)?.groupValues?.getOrNull(1)?.trim()
                ?: continue
            val title = unescapeHtml(rawTitle)
            if (slug.isEmpty() || title.isEmpty()) continue

            val providerId = toProviderId(slug)
            if (!seen.add(providerId)) continue
            val cover = imgSrcRegex.find(block)?.groupValues?.getOrNull(1)?.trim()

            results.add(
                ProviderAnime(
                    providerId = providerId,
                    title = title,
                    englishTitle = title,
                    coverImage = cover,
                ),
            )
        }

        if (results.isEmpty()) {
            for (match in tvShowHrefRegex.findAll(html)) {
                val slug = match.groupValues[2].trim()
                if (slug.isEmpty()) continue
                val providerId = toProviderId(slug)
                if (!seen.add(providerId)) continue
                val fallbackTitle = slug.replace('-', ' ')
                results.add(
                    ProviderAnime(
                        providerId = providerId,
                        title = fallbackTitle,
                        englishTitle = fallbackTitle,
                    ),
                )
            }
        }
        return results
    }

    /** Parses `hentaimama.io/tvshows/<slug>/` HTML into sorted [HentaimamaEpisode] entries. */
    fun parseEpisodesHtml(html: String): List<HentaimamaEpisode> {
        val seenUrls = HashSet<String>()
        val rawLinks = ArrayList<Pair<String, String>>()
        for (match in episodeHrefRegex.findAll(html)) {
            val url = match.groupValues[1]
            val slug = match.groupValues[2]
            if (seenUrls.add(url)) {
                rawLinks.add(url to slug)
            }
        }
        if (rawLinks.isEmpty()) return emptyList()

        val seenNumbers = HashSet<String>()
        val episodes = ArrayList<HentaimamaEpisode>(rawLinks.size)
        rawLinks.forEachIndexed { index, (url, slug) ->
            val epNum = episodeNumSuffixRegex.find(slug)?.groupValues?.getOrNull(1)
                ?: (rawLinks.size - index).toString()
            if (seenNumbers.add(epNum)) {
                episodes.add(HentaimamaEpisode(episodeNumber = epNum, episodeUrl = url))
            }
        }

        return episodes.sortedWith(
            compareBy({ it.episodeNumber.toDoubleOrNull() ?: Double.MAX_VALUE }, { it.episodeNumber }),
        )
    }

    /** Extracts the WordPress `idpost` value from a `hentaimama.io/episodes/...` HTML page. */
    fun parsePostId(episodeHtml: String): String? =
        idPostRegex.find(episodeHtml)?.groupValues?.getOrNull(1)?.trim()

    /** Extracts the `dt_embed` iframe URL from `admin-ajax.php` (`get_player_contents`) JSON. */
    fun parseAjaxEmbedUrl(ajaxBody: String): String? {
        val unescaped = ajaxBody
            .replace("\\/", "/")
            .replace("\\\"", "\"")
        val raw = embedSrcRegex.find(unescaped)?.groupValues?.getOrNull(1)?.trim() ?: return null
        return unescapeHtml(raw)
    }

    /** Extracts direct `.mp4` or `.m3u8` stream candidates from a `hentaimama.io/?dt_embed=...` page. */
    fun parseEmbedCandidates(embedHtml: String): MediaCandidateList {
        val unescaped = embedHtml.replace("\\/", "/")
        val urls = jwpFileRegex.findAll(unescaped)
            .map { it.groupValues[1].trim() }
            .ifEmpty { rawMediaRegex.findAll(unescaped).map { it.value.trim() } }
            .distinct()
            .toList()
        if (urls.isEmpty()) return MediaCandidateList()

        val candidates = urls.map { url ->
            MediaCandidate(
                server = "HentaiMama",
                url = url,
                format = if (url.contains(".m3u8", ignoreCase = true)) "hls" else "mp4",
                quality = "1080p",
                referer = "$BASE_URL/",
            )
        }
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
