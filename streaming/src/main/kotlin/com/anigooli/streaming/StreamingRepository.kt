package com.anigooli.streaming

import com.imashnake.animite.api.preferences.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_HLS_PLAYLIST_FETCHES = 4

/**
 * High-level repository exposing anime matching, episode listing, stream extraction,
 * quality selection (`1080p` default + highest fallback), and remote extractor auto-updates.
 */
@Singleton
class StreamingRepository @Inject constructor(
    private val httpClient: CloudflareAwareHttpClient,
    private val runtime: JsExtractorRuntime,
    private val scriptManager: ExtractorScriptManager,
    private val preferencesRepository: PreferencesRepository,
) {
    /** Refreshes the remote extractor script and upstream `ani-cli` config. */
    suspend fun refreshExtractor(force: Boolean = false): ExtractorStatus {
        return scriptManager.refreshIfNeeded(force = force)
    }

    /** Returns current status metadata for the active streaming script. */
    suspend fun getExtractorStatus(): ExtractorStatus = scriptManager.getStatus()

    /** Searches the streaming provider directly by [query] (including adult/ecchi entries). */
    suspend fun searchProvider(query: String): Result<List<ProviderAnime>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val trimmed = query.trim()
                if (trimmed.isEmpty()) return@runCatching emptyList()
                val req = runtime.buildSearchRequest(trimmed)
                val resp = httpClient.get(req.url, req.headers, allowCloudflareBypass = false)
                runtime.parseSearchResponse(resp.body)
            }
        }

    /**
     * Resolves both uncensored and censored provider entries for an anime when available,
     * prioritizing the uncensored release as [ResolvedAnimeVersions.defaultProvider].
     */
    suspend fun resolveAnimeVersions(
        anilistId: Int,
        candidateTitles: List<String>,
        explicitProviderId: String? = null,
    ): Result<ResolvedAnimeVersions> = withContext(Dispatchers.IO) {
        runCatching {
            scriptManager.refreshIfNeeded(force = false)
            val cleanedTitles = candidateTitles
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            val primaryTitle = cleanedTitles.firstOrNull()
            val queries = buildList {
                addAll(cleanedTitles)
                if (primaryTitle != null && !primaryTitle.contains("uncensored", ignoreCase = true)) {
                    add("$primaryTitle uncensored")
                }
            }.distinct()

            if (queries.isEmpty() && explicitProviderId.isNullOrBlank()) {
                throw IOException("No searchable title available")
            }

            val aggregated = LinkedHashMap<String, ProviderAnime>()
            for (q in queries) {
                val results = runCatching {
                    val req = runtime.buildSearchRequest(q)
                    val resp = httpClient.get(req.url, req.headers)
                    runtime.parseSearchResponse(resp.body)
                }.getOrDefault(emptyList())
                results.forEach { item ->
                    aggregated.putIfAbsent(item.providerId, item)
                }
            }

            val allResults = aggregated.values.toList()
            val anilistMatches = if (anilistId > 0) {
                allResults.filter { it.anilistId == anilistId }
            } else {
                emptyList()
            }
            val titleMatches = allResults.filter { item ->
                cleanedTitles.any { title ->
                    item.englishTitle.equals(title, ignoreCase = true) ||
                        item.romajiTitle.equals(title, ignoreCase = true) ||
                        item.title.equals(title, ignoreCase = true) ||
                        isUncensoredVariantOf(item, title)
                }
            }
            val candidatePool = (anilistMatches + titleMatches + allResults.take(3)).distinctBy { it.providerId }

            val uncensored = candidatePool.firstOrNull { isExplicitlyUncensored(it) }
            val censored = candidatePool.firstOrNull { !isExplicitlyUncensored(it) }
                ?: explicitProviderId?.let { id ->
                    allResults.firstOrNull { it.providerId == id }
                        ?: ProviderAnime(providerId = id, anilistId = anilistId.takeIf { it > 0 }, title = primaryTitle ?: id)
                }
            val explicitMatch = explicitProviderId?.let { id ->
                allResults.firstOrNull { it.providerId == id }
                    ?: ProviderAnime(providerId = id, anilistId = anilistId.takeIf { it > 0 }, title = primaryTitle ?: id)
            }

            val defaultProvider = uncensored
                ?: explicitMatch
                ?: censored
                ?: candidatePool.firstOrNull()
                ?: throw IOException("Anime not found on streaming provider")

            ResolvedAnimeVersions(
                uncensored = uncensored,
                censored = censored,
                defaultProvider = defaultProvider,
            )
        }
    }

    /** Finds the matching streaming provider entry for an AniList anime using AniList ID and title fallback. */
    suspend fun resolveAnime(anilistId: Int, candidateTitles: List<String>): Result<ProviderAnime> =
        resolveAnimeVersions(anilistId, candidateTitles).map { it.defaultProvider }

    private fun isExplicitlyUncensored(item: ProviderAnime): Boolean {
        val combined = listOfNotNull(item.title, item.englishTitle, item.romajiTitle, item.providerId)
            .joinToString(" ")
            .lowercase()
        return combined.contains("uncensored") ||
            combined.contains("un-censored") ||
            combined.contains("at-x")
    }

    private fun isUncensoredVariantOf(item: ProviderAnime, baseTitle: String): Boolean {
        if (!isExplicitlyUncensored(item)) return false
        val normalizedBase = baseTitle.lowercase().trim()
        return listOfNotNull(item.title, item.englishTitle, item.romajiTitle).any { t ->
            t.lowercase().contains(normalizedBase)
        }
    }

    /** Fetches available episode number strings (e.g. ["1", "2", ...]) for [providerId]. */
    suspend fun fetchEpisodes(providerId: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = runtime.buildEpisodesRequest(providerId)
                val resp = httpClient.get(req.url, req.headers)
                val episodes = runtime.parseEpisodesResponse(resp.body)
                if (episodes.isEmpty()) throw IOException("No episodes found")
                episodes
            }
        }

    /**
     * Resolves playable streams for [episode], using [requestedAudioTrack] (`JAPANESE` or `ENGLISH_DUB`).
     * Automatically falls back to `JAPANESE` if English Dub has no sources, and selects [preferredQuality]
     * (falling back to highest available resolution if [preferredQuality] is missing).
     */
    suspend fun fetchEpisodeStreams(
        providerId: String,
        episode: String,
        requestedAudioTrack: AudioTrack,
        preferredQuality: String? = null,
    ): Result<EpisodeStreamBundle> = withContext(Dispatchers.IO) {
        runCatching {
            val targetQuality = preferredQuality
                ?: preferencesRepository.defaultStreamQuality.firstOrNull()
                ?: "1080p"

            var actualTrack = requestedAudioTrack
            var isDubFallback = false
            var sources = resolveSourcesForMode(providerId, episode, requestedAudioTrack)

            if (sources.isEmpty() && requestedAudioTrack == AudioTrack.ENGLISH_DUB) {
                actualTrack = AudioTrack.JAPANESE
                isDubFallback = true
                sources = resolveSourcesForMode(providerId, episode, AudioTrack.JAPANESE)
            }

            if (sources.isEmpty()) {
                throw IOException("No playable streams found for episode $episode")
            }

            val preferSubs = actualTrack == AudioTrack.JAPANESE
            val sorted = QualitySelector.sortSources(sources, preferSubtitles = preferSubs)
            val selected = QualitySelector.selectSource(sorted, targetQuality, preferSubs)
                ?: sorted.first()

            EpisodeStreamBundle(
                episode = episode,
                requestedAudioTrack = requestedAudioTrack,
                actualAudioTrack = actualTrack,
                isDubFallback = isDubFallback,
                sources = sorted,
                selectedSource = selected,
            )
        }
    }

    private suspend fun resolveSourcesForMode(
        providerId: String,
        episode: String,
        audioTrack: AudioTrack,
    ): List<StreamSource> {
        val req = runtime.buildMediaRequest(providerId, episode, audioTrack.toMode())
        val resp = httpClient.get(req.url, req.headers)
        val parsed = runtime.parseMediaResponse(resp.body, audioTrack.toMode())
        if (parsed.candidates.isEmpty()) return emptyList()

        val (_, config) = scriptManager.getActiveScriptAndConfig()
        val directMp4s = parsed.candidates.filter { it.format != "hls" }.map { cand ->
            StreamSource(
                qualityLabel = cand.quality,
                height = QualitySelector.parseHeight(cand.quality),
                serverName = cand.server,
                url = cand.url,
                referer = cand.referer,
                origin = cand.origin,
                isHls = false,
                audioUrl = null,
                subtitleUrl = cand.subtitleUrl,
            )
        }

        val hlsCandidates = parsed.candidates.filter { it.format == "hls" }.take(MAX_HLS_PLAYLIST_FETCHES)
        val hlsExpanded = coroutineScope {
            hlsCandidates.map { cand ->
                async {
                    expandHlsCandidate(cand, config.userAgent)
                }
            }.awaitAll().flatten()
        }

        return hlsExpanded + directMp4s
    }

    private suspend fun expandHlsCandidate(
        cand: MediaCandidate,
        userAgent: String,
    ): List<StreamSource> = runCatching {
        val headers = buildMap {
            put("User-Agent", userAgent)
            if (cand.referer.isNotBlank()) put("Referer", cand.referer)
            if (cand.origin.isNotBlank()) put("Origin", cand.origin)
        }
        val playlistResp = httpClient.get(cand.url, headers, allowCloudflareBypass = false)
        val m3u8 = runtime.parseM3u8(playlistResp.body, cand.url) ?: return@runCatching emptyList()
        m3u8.variants.map { variant ->
            StreamSource(
                qualityLabel = variant.quality,
                height = variant.height,
                serverName = cand.server,
                url = variant.url,
                referer = cand.referer,
                origin = cand.origin,
                isHls = true,
                audioUrl = m3u8.audioUrl,
                subtitleUrl = cand.subtitleUrl,
            )
        }
    }.getOrDefault(emptyList())
}
