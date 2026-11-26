package com.anigooli.streaming

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.util.Base64

/** Episode entry parsed from `hianime.at` episode list endpoint. */
data class HianimeEpisode(
    /** Display episode number string (e.g. "1"). */
    val episodeNumber: String,
    /** Numeric episode ID used for server lookup. */
    val episodeId: String,
)

/**
 * Pure Kotlin parser and URL builder for the `hianime.at` pipeline used by upstream `ani-cli`.
 */
object HianimeParser {
    /** Prefix added to provider IDs belonging to the `hianime.at` backend. */
    const val PROVIDER_PREFIX = "hianime:"

    /** Base URL of the `hianime.at` backend. */
    const val BASE_URL = "https://hianime.at"

    /** Browser User-Agent used by `ani-cli` when querying `hianime.at`. */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val json = Json { ignoreUnknownKeys = true }
    private val zokoKey = "otaku-embed-v1".toByteArray(Charsets.UTF_8)

    private val filmNameRegex = Regex(
        """<h3\s+class="film-name">\s*<a[^>]*href="[^"]*/([^"/?#]+)"[^>]*title="([^"]*)"[^>]*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val jnameRegex = Regex("""data-jname="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val imgSrcRegex = Regex(
        """<img[^>]+(?:data-src|src)="(https?://[^"]+)"[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val epsCountRegex = Regex("""class="tick-item tick-eps"[^>]*>(\d+)<""", RegexOption.IGNORE_CASE)
    private val epDataNumberRegex = Regex("""data-number="([^"]*)"""")
    private val epDataIdRegex = Regex("""data-id="([0-9]+)"""")
    private val serverTypeRegex = Regex("""data-type="([^"]*)"""")
    private val serverNameRegex = Regex("""data-server-name="([^"]*)"""")
    private val serverHashRegex = Regex("""data-hash="([^"]*)"""")
    private val windowPRegex = Regex("""window\.__P="([^"]*)"""")

    /** Returns true if [providerId] belongs to the `hianime.at` backend. */
    fun isHianimeProviderId(providerId: String): Boolean = providerId.startsWith(PROVIDER_PREFIX)

    /** Prepends the `hianime:` prefix to a raw [slug]. */
    fun toProviderId(slug: String): String = "$PROVIDER_PREFIX${slug.removePrefix(PROVIDER_PREFIX)}"

    /** Extracts the raw `hianime.at` slug from a prefixed [providerId]. */
    fun extractSlug(providerId: String): String = providerId.removePrefix(PROVIDER_PREFIX)

    /** Extracts the numeric show ID suffix from a `hianime.at` [slug] (e.g. `9744` from `overflow-uncensored-9744`). */
    fun extractNumericId(slug: String): String = slug.substringAfterLast('-')

    /** Parses `hianime.at/search` HTML into [ProviderAnime] items. */
    fun parseSearchHtml(html: String): List<ProviderAnime> {
        val mainBody = html.substringBefore("""id="main-sidebar"""")
        val blocks = mainBody.split("""<div class="flw-item""")
            .drop(1)
            .ifEmpty { mainBody.split("""<div class="film-detail">""").drop(1) }
        val seen = HashSet<String>()
        val results = ArrayList<ProviderAnime>()

        for (block in blocks) {
            val match = filmNameRegex.find(block) ?: continue
            val slug = match.groupValues[1].trim()
            val rawTitle = unescapeHtml(match.groupValues[2].trim())
            if (slug.isEmpty() || rawTitle.isEmpty()) continue

            val providerId = toProviderId(slug)
            if (!seen.add(providerId)) continue

            val romaji = jnameRegex.find(block)?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { it.isNotEmpty() }?.let(::unescapeHtml)
            val cover = imgSrcRegex.find(block)?.groupValues?.getOrNull(1)?.trim()
            val epCount = epsCountRegex.find(block)?.groupValues?.getOrNull(1)?.toIntOrNull()

            results.add(
                ProviderAnime(
                    providerId = providerId,
                    title = rawTitle,
                    englishTitle = rawTitle,
                    romajiTitle = romaji,
                    coverImage = cover,
                    episodeCount = epCount,
                ),
            )
        }
        return results
    }

    /** Parses `hianime.at/api/theme/episode/list/<id>` response into sorted [HianimeEpisode] entries. */
    fun parseEpisodesHtml(rawResponse: String, slug: String): List<HianimeEpisode> {
        val unescaped = rawResponse.replace("\\", "")
        val chunks = unescaped.split("ep-item").drop(1)
        val seen = HashSet<String>()
        val episodes = ArrayList<HianimeEpisode>()

        for (chunk in chunks) {
            if (!chunk.contains("/watch/$slug?ep=")) continue
            val epNum = epDataNumberRegex.find(chunk)?.groupValues?.getOrNull(1)?.trim() ?: continue
            val epId = epDataIdRegex.find(chunk)?.groupValues?.getOrNull(1)?.trim() ?: continue
            if (epNum.isEmpty() || epId.isEmpty() || !seen.add(epNum)) continue
            episodes.add(HianimeEpisode(episodeNumber = epNum, episodeId = epId))
        }

        return episodes.sortedWith(compareBy({ it.episodeNumber.toDoubleOrNull() ?: Double.MAX_VALUE }, { it.episodeNumber }))
    }

    /** Extracts and Base64-decodes the ZokoAnime embed URL for [mode] (`sub` or `dub`). */
    fun parseServerEmbedUrl(rawResponse: String, mode: String): String? {
        val unescaped = rawResponse.replace("\\", "")
        val items = unescaped.split("server-item").drop(1)
        val matchingType = items.filter {
            serverTypeRegex.find(it)?.groupValues?.getOrNull(1)?.equals(mode, ignoreCase = true) == true
        }
        val preferred = matchingType.firstOrNull {
            serverNameRegex.find(it)?.groupValues?.getOrNull(1)?.equals("ZokoAnime", ignoreCase = true) == true
        } ?: matchingType.firstOrNull()
            ?: return null

        val hash = serverHashRegex.find(preferred)?.groupValues?.getOrNull(1)?.trim() ?: return null
        if (hash.isEmpty()) return null
        return runCatching {
            String(Base64.getDecoder().decode(hash), Charsets.UTF_8).trim()
        }.getOrNull()?.takeIf { it.startsWith("http") }
    }

    /** Extracts and XOR-deobfuscates `window.__P` from ZokoAnime embed HTML. */
    fun extractAndDeobfuscateEmbed(embedHtml: String): String? {
        val blob = windowPRegex.find(embedHtml)?.groupValues?.getOrNull(1)?.trim() ?: return null
        if (blob.isEmpty()) return null
        return deobfuscateZokoBlob(blob)
    }

    /** Decodes Base64 and XOR-decrypts [blob] with `"otaku-embed-v1"`. */
    fun deobfuscateZokoBlob(blob: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(blob)
        val decoded = ByteArray(bytes.size)
        for (i in bytes.indices) {
            decoded[i] = (bytes[i].toInt() xor zokoKey[i % zokoKey.size].toInt()).toByte()
        }
        String(decoded, Charsets.UTF_8)
    }.getOrNull()

    /** Parses the deobfuscated ZokoAnime JSON payload into a [MediaCandidateList]. */
    fun parseZokoEmbedJson(jsonText: String, embedUrl: String): MediaCandidateList {
        val root = runCatching { json.parseToJsonElement(jsonText).jsonObject }.getOrNull()
            ?: return MediaCandidateList()
        val src = root["src"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (!src.startsWith("http")) return MediaCandidateList()

        val subtitles = root["subtitles"]?.jsonArray.orEmpty().mapNotNull { el ->
            runCatching { el.jsonObject }.getOrNull()
        }
        val subtitleUrl = subtitles.firstOrNull {
            it["default"]?.jsonPrimitive?.booleanOrNull == true
        }?.get("src")?.jsonPrimitive?.content?.trim()
            ?: subtitles.firstOrNull {
                val label = it["label"]?.jsonPrimitive?.content.orEmpty()
                label.contains("eng", ignoreCase = true)
            }?.get("src")?.jsonPrimitive?.content?.trim()
            ?: subtitles.firstOrNull()?.get("src")?.jsonPrimitive?.content?.trim()

        val referer = runCatching {
            val uri = URI(embedUrl)
            "${uri.scheme}://${uri.authority}/"
        }.getOrDefault("")

        return MediaCandidateList(
            candidates = listOf(
                MediaCandidate(
                    server = "ZokoAnime",
                    url = src,
                    format = if (src.contains(".m3u8", ignoreCase = true)) "hls" else "mp4",
                    quality = "auto",
                    referer = referer,
                    subtitleUrl = subtitleUrl?.takeIf { it.startsWith("http") },
                ),
            ),
        )
    }

    private fun unescapeHtml(raw: String): String = raw
        .replace("&#039;", "'")
        .replace("&#x27;", "'")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}
