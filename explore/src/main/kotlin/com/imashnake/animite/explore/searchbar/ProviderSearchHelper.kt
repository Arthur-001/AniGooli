package com.imashnake.animite.explore.searchbar

import com.anigooli.streaming.JikanAnimeInfo
import com.anigooli.streaming.JikanMetadataClient
import com.anigooli.streaming.ProviderAnime
import com.anigooli.streaming.StreamingRepository
import com.imashnake.animite.api.anilist.sanitize.media.Media
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue

private const val FALLBACK_SEARCH_TIMEOUT_MS = 5_000L

/** Resolved metadata for a search result sourced from `ani-cli` or Jikan fallback. */
data class SearchFallbackMeta(
    /** Provider ID on `ani-cli` (`miruro`) if found. */
    val providerId: String? = null,
    /** MyAnimeList ID if known. */
    val malId: Int? = null,
    /** Display title fallback. */
    val fallbackTitle: String? = null,
)

/**
 * Merges `ani-cli` (`StreamingRepository`) search results and Jikan (`sfw=false`) metadata
 * into AniList search results so adult, ecchi, and partial-match anime appear with covers and details.
 */
@Singleton
class ProviderSearchHelper @Inject constructor(
    private val streamingRepository: StreamingRepository,
    private val jikanMetadataClient: JikanMetadataClient,
) {
    private val fallbackLookup = ConcurrentHashMap<Int, SearchFallbackMeta>()

    /** Returns cached [SearchFallbackMeta] for a synthetic or matched media ID if available. */
    fun getFallbackMeta(mediaId: Int): SearchFallbackMeta? = fallbackLookup[mediaId]

    /**
     * Fetches `ani-cli` and Jikan (`sfw=false`) results for [query] in parallel with bounded timeouts
     * and appends any non-duplicate titles (including Ecchi / Erotica / Hentai) after [anilistResults].
     */
    suspend fun mergeProviderResults(
        query: String,
        anilistResults: List<Media.Medium>,
    ): ImmutableList<Media.Medium> = coroutineScope {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@coroutineScope anilistResults.toImmutableList()

        val providerDeferred = async {
            withTimeoutOrNull(FALLBACK_SEARCH_TIMEOUT_MS) {
                streamingRepository.searchProvider(trimmed).getOrDefault(emptyList())
            }.orEmpty()
        }
        val jikanDeferred = async {
            withTimeoutOrNull(FALLBACK_SEARCH_TIMEOUT_MS) {
                jikanMetadataClient.searchAnime(trimmed, limit = 10)
            }.orEmpty()
        }

        val providerItems = providerDeferred.await()
        val jikanItems = jikanDeferred.await()
        if (providerItems.isEmpty() && jikanItems.isEmpty()) {
            return@coroutineScope anilistResults.toImmutableList()
        }

        val jikanByMalId = jikanItems.associateBy { it.malId }
        val seenIds = anilistResults.map { it.id }.toHashSet()
        val seenNormalizedTitles = anilistResults
            .mapNotNull { it.title?.lowercase()?.trim() }
            .toHashSet()
        val seenMalIds = HashSet<Int>()

        val merged = ArrayList<Media.Medium>(anilistResults.size + providerItems.size + jikanItems.size)
        merged.addAll(anilistResults)

        for (provider in providerItems) {
            val anilistId = provider.anilistId?.takeIf { it > 0 }
            if (anilistId != null && anilistId in seenIds) continue

            val candidateTitles = listOfNotNull(
                provider.englishTitle,
                provider.romajiTitle,
                provider.title,
            ).map { it.lowercase().trim() }
            if (candidateTitles.any { it in seenNormalizedTitles }) continue

            val jikanMatch = provider.malId?.let { jikanByMalId[it] }
                ?: findBestJikanMatch(provider, jikanItems)
            val malId = provider.malId ?: jikanMatch?.malId
            if (malId != null) seenMalIds.add(malId)

            val resolvedId = if (anilistId != null) {
                anilistId
            } else {
                nextSyntheticId(provider.providerId.hashCode(), seenIds)
            }

            seenIds.add(resolvedId)
            candidateTitles.forEach { seenNormalizedTitles.add(it) }
            val displayTitle = provider.englishTitle ?: provider.title
            fallbackLookup[resolvedId] = SearchFallbackMeta(
                providerId = provider.providerId,
                malId = malId,
                fallbackTitle = displayTitle,
            )

            val genrePreview = jikanMatch?.genres
                ?.take(3)
                ?.takeIf { it.isNotEmpty() }
                ?.toImmutableList()
                ?: persistentListOf("ani-cli")

            merged.add(
                Media.Medium(
                    id = resolvedId,
                    coverImage = jikanMatch?.coverImageUrl ?: provider.coverImage,
                    title = displayTitle,
                    season = null,
                    seasonYear = jikanMatch?.year,
                    studios = genrePreview,
                    format = jikanMatch?.type?.let { Media.Format.safeValueOf(it.uppercase()) },
                    episodes = jikanMatch?.episodes,
                )
            )
        }

        // Also include Jikan matches missed by AniList (e.g. partial token queries like "stein" -> "Steins;Gate")
        for (jikan in jikanItems) {
            if (jikan.malId in seenMalIds) continue
            val candidateTitles = listOfNotNull(
                jikan.titleEnglish,
                jikan.title,
                jikan.titleJapanese,
            ).map { it.lowercase().trim() }
            if (candidateTitles.any { it in seenNormalizedTitles }) continue

            val resolvedId = nextSyntheticId(jikan.malId, seenIds)
            seenIds.add(resolvedId)
            seenMalIds.add(jikan.malId)
            candidateTitles.forEach { seenNormalizedTitles.add(it) }
            val displayTitle = jikan.titleEnglish ?: jikan.title
            fallbackLookup[resolvedId] = SearchFallbackMeta(
                providerId = null,
                malId = jikan.malId,
                fallbackTitle = displayTitle,
            )

            merged.add(
                Media.Medium(
                    id = resolvedId,
                    coverImage = jikan.coverImageUrl,
                    title = displayTitle,
                    season = null,
                    seasonYear = jikan.year,
                    studios = jikan.genres.take(3).toImmutableList(),
                    format = jikan.type?.let { Media.Format.safeValueOf(it.uppercase()) },
                    episodes = jikan.episodes,
                )
            )
        }

        merged.toImmutableList()
    }

    private fun nextSyntheticId(seed: Int, seenIds: Set<Int>): Int {
        var candidate = -(seed.absoluteValue.coerceAtLeast(1))
        while (candidate in seenIds) {
            candidate -= 1
        }
        return candidate
    }

    private fun findBestJikanMatch(
        provider: ProviderAnime,
        jikanItems: List<JikanAnimeInfo>,
    ): JikanAnimeInfo? {
        val titles = listOfNotNull(
            provider.englishTitle,
            provider.romajiTitle,
            provider.title,
        ).map { it.lowercase().trim() }
        return jikanItems.firstOrNull { jikan ->
            val jikanTitles = listOfNotNull(
                jikan.titleEnglish,
                jikan.title,
                jikan.titleJapanese,
            ).map { it.lowercase().trim() }
            titles.any { t -> jikanTitles.any { jt -> jt == t } }
        }
    }
}
