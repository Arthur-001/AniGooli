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

private const val FALLBACK_SEARCH_TIMEOUT_MS = 4_000L
private const val MAX_CACHE_ENTRIES = 50
private const val SCORE_EXACT_MATCH = 10_000
private const val SCORE_VARIANT_EXACT = 9_000
private const val SCORE_PREFIX_MATCH = 6_000
private const val SCORE_WORD_MATCH = 4_000
private const val SCORE_SUBSTRING_MATCH = 2_000

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
 * Merges AniList, `ani-cli` (`StreamingRepository`), and Jikan (`sfw=false`) search results,
 * ranks exact and prefix matches first, and caches results per normalized query.
 */
@Singleton
class ProviderSearchHelper @Inject constructor(
    private val streamingRepository: StreamingRepository,
    private val jikanMetadataClient: JikanMetadataClient,
) {
    private val fallbackLookup = ConcurrentHashMap<Int, SearchFallbackMeta>()
    private val searchCache = object : LinkedHashMap<String, ImmutableList<Media.Medium>>(
        MAX_CACHE_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ImmutableList<Media.Medium>>?,
        ): Boolean = size > MAX_CACHE_ENTRIES
    }

    /** Returns cached search results for [query] if present, or null on cache miss. */
    fun getCachedResults(query: String): ImmutableList<Media.Medium>? {
        val key = normalizeKey(query)
        if (key.isEmpty()) return null
        return synchronized(searchCache) { searchCache[key] }
    }

    /** Stores [results] in the in-memory LRU search cache for [query]. */
    fun putCachedResults(query: String, results: ImmutableList<Media.Medium>) {
        val key = normalizeKey(query)
        if (key.isEmpty()) return
        synchronized(searchCache) {
            searchCache[key] = results
        }
    }

    /** Returns cached [SearchFallbackMeta] for a synthetic or matched media ID if available. */
    fun getFallbackMeta(mediaId: Int): SearchFallbackMeta? = fallbackLookup[mediaId]

    /**
     * Fetches `ani-cli` and Jikan (`sfw=false`) results for [query] in parallel with bounded timeouts,
     * merges them with [anilistResults], and sorts exact/closest matches to the top.
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

        val ranked = buildMergedAndRanked(
            query = trimmed,
            anilistResults = anilistResults,
            providerItems = providerItems,
            jikanItems = jikanItems,
        )
        if (ranked.isNotEmpty()) {
            putCachedResults(trimmed, ranked)
        }
        ranked
    }

    /** Ranks [results] so exact title matches appear first and loose matches appear at the bottom. */
    fun rankByRelevance(
        query: String,
        results: List<Media.Medium>,
    ): ImmutableList<Media.Medium> {
        val normalizedQuery = normalizeTitle(query)
        if (normalizedQuery.isEmpty() || results.size <= 1) return results.toImmutableList()
        return results.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<Media.Medium>> { (idx, item) ->
                    computeRelevanceScore(item.title, normalizedQuery)
                }.thenBy { it.index },
            )
            .map { it.value }
            .toImmutableList()
    }

    private fun buildMergedAndRanked(
        query: String,
        anilistResults: List<Media.Medium>,
        providerItems: List<ProviderAnime>,
        jikanItems: List<JikanAnimeInfo>,
    ): ImmutableList<Media.Medium> {
        val jikanByMalId = jikanItems.associateBy { it.malId }
        val seenIds = anilistResults.map { it.id }.toHashSet()
        val seenNormalizedTitles = anilistResults
            .mapNotNull { it.title?.let(::normalizeTitle)?.takeIf { t -> t.isNotEmpty() } }
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
            ).map(::normalizeTitle).filter { it.isNotEmpty() }
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
                    seasonYear = jikanMatch?.year ?: provider.seasonYear,
                    studios = genrePreview,
                    format = (jikanMatch?.type ?: provider.format)
                        ?.let { Media.Format.safeValueOf(it.uppercase()) },
                    episodes = jikanMatch?.episodes ?: provider.episodeCount,
                )
            )
        }

        for (jikan in jikanItems) {
            if (jikan.malId in seenMalIds) continue
            val candidateTitles = listOfNotNull(
                jikan.titleEnglish,
                jikan.title,
                jikan.titleJapanese,
            ).map(::normalizeTitle).filter { it.isNotEmpty() }
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

        return rankByRelevance(query, merged)
    }

    private fun computeRelevanceScore(rawTitle: String?, normalizedQuery: String): Int {
        val title = normalizeTitle(rawTitle.orEmpty())
        if (title.isEmpty()) return 0
        val strippedVariant = title
            .replace("(uncensored)", "")
            .replace("(censored)", "")
            .trim()
        return when {
            title == normalizedQuery -> SCORE_EXACT_MATCH
            strippedVariant == normalizedQuery -> SCORE_VARIANT_EXACT
            title.startsWith(normalizedQuery) -> {
                SCORE_PREFIX_MATCH - (title.length - normalizedQuery.length).coerceAtMost(500)
            }
            Regex("""\b${Regex.escape(normalizedQuery)}\b""").containsMatchIn(title) -> {
                SCORE_WORD_MATCH - (title.length - normalizedQuery.length).coerceAtMost(500)
            }
            title.contains(normalizedQuery) -> {
                SCORE_SUBSTRING_MATCH - (title.length - normalizedQuery.length).coerceAtMost(500)
            }
            else -> 0
        }
    }

    private fun normalizeKey(query: String): String = query.trim().lowercase()

    private fun normalizeTitle(raw: String): String =
        raw.trim().lowercase().replace(Regex("""\s+"""), " ")

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
        ).map(::normalizeTitle).filter { it.isNotEmpty() }
        return jikanItems.firstOrNull { jikan ->
            val jikanTitles = listOfNotNull(
                jikan.titleEnglish,
                jikan.title,
                jikan.titleJapanese,
            ).map(::normalizeTitle).filter { it.isNotEmpty() }
            titles.any { t -> jikanTitles.any { jt -> jt == t } }
        }
    }
}
