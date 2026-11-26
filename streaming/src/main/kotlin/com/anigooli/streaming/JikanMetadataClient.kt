package com.anigooli.streaming

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

private const val JIKAN_BASE_URL = "https://api.jikan.moe/v4"

/** Metadata record retrieved from the public Jikan (MyAnimeList) v4 API including 18+/Ecchi/Erotica titles. */
data class JikanAnimeInfo(
    /** MyAnimeList anime ID. */
    val malId: Int,
    /** Primary display title. */
    val title: String,
    /** Optional English title. */
    val titleEnglish: String? = null,
    /** Optional Japanese native title. */
    val titleJapanese: String? = null,
    /** Cover poster image URL. */
    val coverImageUrl: String? = null,
    /** Full synopsis / description text. */
    val synopsis: String? = null,
    /** Genres including Ecchi, Erotica, Hentai, etc. */
    val genres: List<String> = emptyList(),
    /** Total episode count when known. */
    val episodes: Int? = null,
    /** Average score on 0..10 scale. */
    val score: Double? = null,
    /** Release year when available. */
    val year: Int? = null,
    /** Age rating label (e.g. "Rx - Hentai", "R+ - Mild Nudity"). */
    val rating: String? = null,
    /** Anime format/type (e.g. "TV", "OVA"). */
    val type: String? = null,
)

@Serializable
private data class JikanSingleResponse(
    val data: JikanAnimeDto? = null,
)

@Serializable
private data class JikanSearchResponse(
    val data: List<JikanAnimeDto> = emptyList(),
)

@Serializable
private data class JikanEpisodesResponse(
    val data: List<JikanEpisodeDto> = emptyList(),
)

@Serializable
private data class JikanAnimeDto(
    @SerialName("mal_id") val malId: Int = 0,
    val title: String = "",
    @SerialName("title_english") val titleEnglish: String? = null,
    @SerialName("title_japanese") val titleJapanese: String? = null,
    val images: JikanImagesDto? = null,
    val synopsis: String? = null,
    val genres: List<JikanNamedDto> = emptyList(),
    @SerialName("explicit_genres") val explicitGenres: List<JikanNamedDto> = emptyList(),
    val episodes: Int? = null,
    val score: Double? = null,
    val year: Int? = null,
    val rating: String? = null,
    val type: String? = null,
)

@Serializable
private data class JikanImagesDto(
    val jpg: JikanImageUrlsDto? = null,
    val webp: JikanImageUrlsDto? = null,
)

@Serializable
private data class JikanImageUrlsDto(
    @SerialName("large_image_url") val largeImageUrl: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
)

@Serializable
private data class JikanNamedDto(
    val name: String = "",
)

@Serializable
private data class JikanEpisodeDto(
    @SerialName("mal_id") val malId: Int = 0,
    val title: String? = null,
)

/**
 * Client for querying Jikan (MAL) v4 anime metadata and episode names,
 * including Ecchi / Erotica / Hentai entries not always exposed by standard AniList queries.
 */
@Singleton
class JikanMetadataClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Fetches detailed anime metadata by MyAnimeList ID. */
    suspend fun fetchByMalId(malId: Int): JikanAnimeInfo? = withContext(Dispatchers.IO) {
        if (malId <= 0) return@withContext null
        runCatching {
            val body = get("$JIKAN_BASE_URL/anime/$malId") ?: return@runCatching null
            json.decodeFromString<JikanSingleResponse>(body).data?.toInfo()
        }.getOrNull()
    }

    /** Searches Jikan v4 with `sfw=false` so Ecchi/Erotica/Hentai metadata and covers resolve cleanly. */
    suspend fun searchAnime(query: String, limit: Int = 10): List<JikanAnimeInfo> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return@withContext emptyList()
            runCatching {
                val encoded = URLEncoder.encode(trimmed, "UTF-8")
                val url = "$JIKAN_BASE_URL/anime?q=$encoded&sfw=false&limit=${limit.coerceIn(1, 25)}"
                val body = get(url) ?: return@runCatching emptyList()
                json.decodeFromString<JikanSearchResponse>(body).data.map { it.toInfo() }
            }.getOrDefault(emptyList())
        }

    /** Fetches episode titles for [malId] at [page] (100 episodes per page on Jikan v4). */
    suspend fun fetchEpisodeTitles(malId: Int, page: Int = 1): Map<String, String> =
        withContext(Dispatchers.IO) {
            if (malId <= 0 || page < 1) return@withContext emptyMap()
            runCatching {
                val body = get("$JIKAN_BASE_URL/anime/$malId/episodes?page=$page")
                    ?: return@runCatching emptyMap()
                val items = json.decodeFromString<JikanEpisodesResponse>(body).data
                buildMap {
                    items.forEach { ep ->
                        val title = ep.title?.trim()
                        if (ep.malId > 0 && !title.isNullOrEmpty()) {
                            put(ep.malId.toString(), title)
                        }
                    }
                }
            }.getOrDefault(emptyMap())
        }

    private fun get(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", ExtractorConfig.DEFAULT_USER_AGENT)
            .build()
        return okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    }

    private fun JikanAnimeDto.toInfo(): JikanAnimeInfo {
        val cover = images?.webp?.largeImageUrl
            ?: images?.jpg?.largeImageUrl
            ?: images?.webp?.imageUrl
            ?: images?.jpg?.imageUrl
        val combinedGenres = (genres + explicitGenres)
            .map { it.name.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return JikanAnimeInfo(
            malId = malId,
            title = title.ifBlank { titleEnglish ?: titleJapanese.orEmpty() },
            titleEnglish = titleEnglish,
            titleJapanese = titleJapanese,
            coverImageUrl = cover,
            synopsis = synopsis,
            genres = combinedGenres,
            episodes = episodes,
            score = score,
            year = year,
            rating = rating,
            type = type,
        )
    }
}
