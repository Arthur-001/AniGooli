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
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.absoluteValue

/**
 * Merges `ani-cli` (`StreamingRepository`) search results and Jikan (`sfw=false`) metadata
 * into AniList search results so adult, ecchi, and provider-only anime appear with covers and details.
 */
@Singleton
class ProviderSearchHelper @Inject constructor(
    private val streamingRepository: StreamingRepository,
    private val jikanMetadataClient: JikanMetadataClient,
) {
    private val providerLookup = ConcurrentHashMap<Int, ProviderAnime>()

    /** Returns cached [ProviderAnime] metadata for a synthetic or matched media ID if available. */
    fun getProviderEntry(mediaId: Int): ProviderAnime? = providerLookup[mediaId]

    /**
     * Fetches `ani-cli` and Jikan (`sfw=false`) results for [query] in parallel and appends
     * any non-duplicate titles (including Ecchi / Erotica / Hentai) after [anilistResults].
     */
    suspend fun mergeProviderResults(
        query: String,
        anilistResults: List<Media.Medium>,
    ): ImmutableList<Media.Medium> = coroutineScope {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@coroutineScope anilistResults.toImmutableList()

        val providerDeferred = async {
            streamingRepository.searchProvider(trimmed).getOrDefault(emptyList())
        }
        val jikanDeferred = async {
            jikanMetadataClient.searchAnime(trimmed, limit = 10)
        }

        val providerItems = providerDeferred.await()
        val jikanItems = jikanDeferred.await()
        if (providerItems.isEmpty()) return@coroutineScope anilistResults.toImmutableList()

        val jikanByMalId = jikanItems.associateBy { it.malId }
        val seenIds = anilistResults.map { it.id }.toHashSet()
        val seenNormalizedTitles = anilistResults
            .mapNotNull { it.title?.lowercase()?.trim() }
            .toHashSet()

        val merged = ArrayList<Media.Medium>(anilistResults.size + providerItems.size)
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

            val resolvedId = if (anilistId != null) {
                anilistId
            } else {
                var synthetic = -(provider.providerId.hashCode().absoluteValue.coerceAtLeast(1))
                while (synthetic in seenIds) {
                    synthetic -= 1
                }
                synthetic
            }

            seenIds.add(resolvedId)
            candidateTitles.forEach { seenNormalizedTitles.add(it) }
            providerLookup[resolvedId] = provider

            val genrePreview = jikanMatch?.genres
                ?.take(3)
                ?.takeIf { it.isNotEmpty() }
                ?.toImmutableList()
                ?: persistentListOf("ani-cli")

            merged.add(
                Media.Medium(
                    id = resolvedId,
                    coverImage = jikanMatch?.coverImageUrl ?: provider.coverImage,
                    title = provider.englishTitle ?: provider.title,
                    season = null,
                    seasonYear = jikanMatch?.year,
                    studios = genrePreview,
                    format = jikanMatch?.type?.let { Media.Format.safeValueOf(it.uppercase()) },
                    episodes = jikanMatch?.episodes,
                )
            )
        }

        merged.toImmutableList()
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
