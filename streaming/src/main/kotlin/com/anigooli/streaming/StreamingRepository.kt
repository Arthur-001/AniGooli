package com.anigooli.streaming

import com.imashnake.animite.api.preferences.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
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
    private val providerTitlesCache = ConcurrentHashMap<String, List<String>>()

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
                coroutineScope {
                    val miruroDeferred = async {
                        runCatching {
                            val req = runtime.buildSearchRequest(trimmed)
                            val resp = httpClient.get(req.url, req.headers, allowCloudflareBypass = false)
                            runtime.parseSearchResponse(resp.body)
                        }.getOrDefault(emptyList())
                    }
                    val hianimeDeferred = async {
                        runCatching { searchHianime(trimmed) }.getOrDefault(emptyList())
                    }
                    val hentaiStreamDeferred = async {
                        runCatching { searchHentaiStream(trimmed) }.getOrDefault(emptyList())
                    }
                    val hentaimamaDeferred = async {
                        runCatching { searchHentaimama(trimmed) }.getOrDefault(emptyList())
                    }
                    val hanime1Deferred = async {
                        runCatching { searchHanime1(trimmed) }.getOrDefault(emptyList())
                    }
                    (miruroDeferred.await() +
                        hianimeDeferred.await() +
                        hentaiStreamDeferred.await() +
                        hentaimamaDeferred.await() +
                        hanime1Deferred.await())
                        .distinctBy { it.providerId }
                }
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
            val preferHianimeOnly = explicitProviderId != null && HianimeParser.isHianimeProviderId(explicitProviderId)
            if (!preferHianimeOnly) {
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
            }

            fun buildCandidatePool(): List<ProviderAnime> {
                val allResults = aggregated.values.toList()
                val anilistMatches = if (anilistId > 0) {
                    allResults.filter { it.anilistId == anilistId }
                } else {
                    emptyList()
                }
                val titleMatches = allResults.filter { item ->
                    cleanedTitles.any { title ->
                        isExactOrVariantMatch(item, title)
                    }
                }
                return (anilistMatches + titleMatches).distinctBy { it.providerId }
            }

            var candidatePool = buildCandidatePool()
            val preferHentaiStreamOnly =
                explicitProviderId != null && HentaiStreamParser.isHentaiStreamProviderId(explicitProviderId)
            val preferHentaimamaOnly =
                explicitProviderId != null && HentaimamaParser.isHentaimamaProviderId(explicitProviderId)
            val preferHanime1Only =
                explicitProviderId != null && Hanime1Parser.isHanime1ProviderId(explicitProviderId)
            if (!preferHentaiStreamOnly && !preferHentaimamaOnly && !preferHanime1Only &&
                (preferHianimeOnly || candidatePool.isEmpty() || candidatePool.none { isExplicitlyUncensored(it) })
            ) {
                for (q in cleanedTitles.take(2)) {
                    val hianimeResults = runCatching { searchHianime(q) }.getOrDefault(emptyList())
                    hianimeResults.forEach { item ->
                        aggregated.putIfAbsent(item.providerId, item)
                    }
                }
                candidatePool = buildCandidatePool()
            }
            if (preferHentaiStreamOnly || candidatePool.isEmpty()) {
                for (q in buildHentaiStreamQueries(cleanedTitles)) {
                    val hsResults = runCatching { searchHentaiStream(q) }.getOrDefault(emptyList())
                    hsResults.forEach { item ->
                        aggregated.putIfAbsent(item.providerId, item)
                    }
                    candidatePool = buildCandidatePool()
                    if (candidatePool.isNotEmpty()) break
                }
            }
            if (preferHentaimamaOnly || candidatePool.isEmpty()) {
                for (q in buildHentaiStreamQueries(cleanedTitles)) {
                    val hmResults = runCatching { searchHentaimama(q) }.getOrDefault(emptyList())
                    hmResults.forEach { item ->
                        aggregated.putIfAbsent(item.providerId, item)
                    }
                    candidatePool = buildCandidatePool()
                    if (candidatePool.isNotEmpty()) break
                }
            }
            if (preferHanime1Only || candidatePool.isEmpty()) {
                for (q in buildHentaiStreamQueries(cleanedTitles)) {
                    val h1Results = runCatching { searchHanime1(q) }.getOrDefault(emptyList())
                    h1Results.forEach { item ->
                        aggregated.putIfAbsent(item.providerId, item)
                    }
                    candidatePool = buildCandidatePool()
                    if (candidatePool.isNotEmpty()) break
                }
            }
            candidatePool.forEach { item ->
                providerTitlesCache[item.providerId] = cleanedTitles
            }
            val allResults = aggregated.values.toList()

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

    private fun isExactOrVariantMatch(item: ProviderAnime, baseTitle: String): Boolean {
        val normalizedBase = normalizeMatchTitle(baseTitle)
        if (normalizedBase.isEmpty()) return false
        val isLooseAdultProvider = HentaiStreamParser.isHentaiStreamProviderId(item.providerId) ||
            HentaimamaParser.isHentaimamaProviderId(item.providerId) ||
            Hanime1Parser.isHanime1ProviderId(item.providerId)
        val shortBase = normalizeMatchTitle(
            baseTitle.substringBefore(':').substringBefore(" - "),
        )
        val shortBaseCompact = shortBase.replace(" ", "")
        val shortWords = shortBase.split(" ").filter { it.length >= 3 }
        return listOfNotNull(item.title, item.englishTitle, item.romajiTitle).any { raw ->
            val norm = normalizeMatchTitle(raw)
            val normCompact = norm.replace(" ", "")
            norm == normalizedBase ||
                norm == "$normalizedBase uncensored" ||
                norm == "$normalizedBase censored" ||
                norm == "$normalizedBase tv" ||
                norm == "$normalizedBase tv ver" ||
                norm == "$normalizedBase tv version" ||
                (isLooseAdultProvider && (
                    norm.startsWith("$normalizedBase ") ||
                        norm.contains(normalizedBase) ||
                        (shortBase.length >= 5 && norm.contains(shortBase)) ||
                        (shortBaseCompact.length >= 6 && normCompact.contains(shortBaseCompact)) ||
                        (shortWords.size >= 2 && shortWords.take(3).all { norm.contains(it) })
                    ))
        }
    }

    private fun buildHentaiStreamQueries(titles: List<String>): List<String> {
        val queries = LinkedHashSet<String>()
        for (title in titles) {
            val trimmed = title.trim()
            if (trimmed.isEmpty()) continue
            queries.add(trimmed)
            val beforeColon = trimmed.substringBefore(':').substringBefore(" - ").trim()
            if (beforeColon.isNotEmpty()) queries.add(beforeColon)
            val normWords = normalizeMatchTitle(beforeColon.ifEmpty { trimmed })
                .split(" ")
                .filter { it.isNotEmpty() }
            if (normWords.size >= 2) {
                queries.add(normWords.take(2).joinToString(" "))
            } else if (normWords.size == 1) {
                queries.add(normWords.first())
            }
        }
        return queries.take(6)
    }

    private fun normalizeMatchTitle(raw: String): String = raw
        .lowercase()
        .replace(Regex("""[^\p{L}\p{N}\s]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    /** Fetches available episode number strings (e.g. ["1", "2", ...]) for [providerId]. */
    suspend fun fetchEpisodes(providerId: String): Result<List<String>> =
        fetchEpisodesByTrack(providerId).map { it.subEpisodes }

    /**
     * Fetches both Japanese (`subEpisodes`) and English Dub (`dubEpisodes`) episode lists
     * in a single provider request so switching tracks does not re-fetch from the network.
     */
    suspend fun fetchEpisodesByTrack(providerId: String): Result<ProviderEpisodes> =
        withContext(Dispatchers.IO) {
            runCatching {
                val result = when {
                    HianimeParser.isHianimeProviderId(providerId) -> {
                        val eps = fetchHianimeEpisodes(providerId).map { it.episodeNumber }
                        ProviderEpisodes(subEpisodes = eps, dubEpisodes = eps, dubChecked = false)
                    }
                    HentaiStreamParser.isHentaiStreamProviderId(providerId) ->
                        ProviderEpisodes(subEpisodes = listOf("1"), dubEpisodes = emptyList(), dubChecked = true)
                    HentaimamaParser.isHentaimamaProviderId(providerId) -> {
                        val eps = fetchHentaimamaEpisodes(providerId).map { it.episodeNumber }
                        ProviderEpisodes(subEpisodes = eps, dubEpisodes = emptyList(), dubChecked = true)
                    }
                    Hanime1Parser.isHanime1ProviderId(providerId) ->
                        ProviderEpisodes(subEpisodes = listOf("1"), dubEpisodes = emptyList(), dubChecked = true)
                    else -> {
                        val req = runtime.buildEpisodesRequest(providerId)
                        val resp = httpClient.get(req.url, req.headers)
                        val subEps = runtime.parseEpisodesResponse(resp.body)
                        val dubCount = NativeMiruroParser.parseDubEpisodeCount(resp.body)
                        val dubEps = NativeMiruroParser.resolveDubEpisodes(subEps, dubCount)
                        ProviderEpisodes(
                            subEpisodes = subEps,
                            dubEpisodes = dubEps,
                            dubChecked = dubCount != null,
                        )
                    }
                }
                if (result.subEpisodes.isEmpty()) throw IOException("No episodes found")
                result
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

            if (sources.isEmpty() && !HentaiStreamParser.isHentaiStreamProviderId(providerId)) {
                val fallbackTitles = providerTitlesCache[providerId].orEmpty()
                sources = resolveHentaiStreamFallbackByTitles(fallbackTitles)
                if (sources.isEmpty() && !HentaimamaParser.isHentaimamaProviderId(providerId)) {
                    sources = resolveHentaimamaFallbackByTitles(fallbackTitles, episode)
                }
                if (sources.isEmpty() && !Hanime1Parser.isHanime1ProviderId(providerId)) {
                    sources = resolveHanime1FallbackByTitles(fallbackTitles)
                }
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
        val parsed = when {
            HianimeParser.isHianimeProviderId(providerId) ->
                resolveHianimeMediaCandidates(providerId, episode, audioTrack.toMode())
            HentaiStreamParser.isHentaiStreamProviderId(providerId) ->
                resolveHentaiStreamMediaCandidates(providerId)
            HentaimamaParser.isHentaimamaProviderId(providerId) ->
                resolveHentaimamaMediaCandidates(providerId, episode)
            Hanime1Parser.isHanime1ProviderId(providerId) ->
                resolveHanime1MediaCandidates(providerId)
            else -> {
                val req = runtime.buildMediaRequest(providerId, episode, audioTrack.toMode())
                val resp = httpClient.get(req.url, req.headers)
                runtime.parseMediaResponse(resp.body, audioTrack.toMode())
            }
        }
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

    private suspend fun searchHianime(query: String): List<ProviderAnime> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "${HianimeParser.BASE_URL}/search?keyword=$encoded"
        val headers = mapOf("User-Agent" to HianimeParser.USER_AGENT)
        val resp = httpClient.get(url, headers, allowCloudflareBypass = false)
        return HianimeParser.parseSearchHtml(resp.body)
    }

    private suspend fun fetchHianimeEpisodes(providerId: String): List<HianimeEpisode> {
        val slug = HianimeParser.extractSlug(providerId)
        val numericId = HianimeParser.extractNumericId(slug)
        val url = "${HianimeParser.BASE_URL}/api/theme/episode/list/$numericId"
        val headers = mapOf("User-Agent" to HianimeParser.USER_AGENT)
        val resp = httpClient.get(url, headers, allowCloudflareBypass = false)
        return HianimeParser.parseEpisodesHtml(resp.body, slug)
    }

    private suspend fun resolveHianimeMediaCandidates(
        providerId: String,
        episode: String,
        mode: String,
    ): MediaCandidateList {
        val episodes = fetchHianimeEpisodes(providerId)
        val epId = episodes.firstOrNull { it.episodeNumber == episode }?.episodeId
            ?: return MediaCandidateList()
        val headers = mapOf("User-Agent" to HianimeParser.USER_AGENT)
        val serversUrl = "${HianimeParser.BASE_URL}/api/theme/episode/servers?episodeId=$epId"
        val serversResp = httpClient.get(serversUrl, headers, allowCloudflareBypass = false)
        val embedUrl = HianimeParser.parseServerEmbedUrl(serversResp.body, mode)
            ?: return MediaCandidateList()
        val embedResp = httpClient.get(embedUrl, headers, allowCloudflareBypass = false)
        val jsonText = HianimeParser.extractAndDeobfuscateEmbed(embedResp.body)
            ?: return MediaCandidateList()
        return HianimeParser.parseZokoEmbedJson(jsonText, embedUrl)
    }

    private suspend fun searchHentaiStream(query: String): List<ProviderAnime> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "${HentaiStreamParser.BASE_URL}/search?q=$encoded"
        val headers = mapOf("User-Agent" to HentaiStreamParser.USER_AGENT)
        val resp = httpClient.get(url, headers, allowCloudflareBypass = false)
        return HentaiStreamParser.parseSearchHtml(resp.body)
    }

    private suspend fun resolveHentaiStreamMediaCandidates(providerId: String): MediaCandidateList {
        val slug = HentaiStreamParser.extractSlug(providerId)
        val url = "${HentaiStreamParser.BASE_URL}/video/$slug/"
        val headers = mapOf("User-Agent" to HentaiStreamParser.USER_AGENT)
        val resp = httpClient.get(url, headers, allowCloudflareBypass = false)
        return HentaiStreamParser.parseVideoPageCandidates(resp.body)
    }

    private suspend fun resolveHentaiStreamFallbackByTitles(titles: List<String>): List<StreamSource> {
        for (query in buildHentaiStreamQueries(titles)) {
            val results = runCatching { searchHentaiStream(query) }.getOrDefault(emptyList())
            val matched = results.firstOrNull { item ->
                titles.any { title -> isExactOrVariantMatch(item, title) }
            } ?: continue
            val candidates = runCatching {
                resolveHentaiStreamMediaCandidates(matched.providerId).candidates
            }.getOrDefault(emptyList())
            if (candidates.isNotEmpty()) {
                return candidates.map { cand ->
                    StreamSource(
                        qualityLabel = cand.quality,
                        height = QualitySelector.parseHeight(cand.quality),
                        serverName = cand.server,
                        url = cand.url,
                        referer = cand.referer,
                        origin = cand.origin,
                        isHls = cand.format == "hls",
                        audioUrl = null,
                        subtitleUrl = cand.subtitleUrl,
                    )
                }
            }
        }
        return emptyList()
    }

    private suspend fun searchHentaimama(query: String): List<ProviderAnime> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "${HentaimamaParser.BASE_URL}/?s=$encoded"
        val headers = mapOf("User-Agent" to HentaimamaParser.USER_AGENT)
        val resp = httpClient.get(url, headers, allowCloudflareBypass = false)
        return HentaimamaParser.parseSearchHtml(resp.body)
    }

    private suspend fun fetchHentaimamaEpisodes(providerId: String): List<HentaimamaEpisode> {
        val slug = HentaimamaParser.extractSlug(providerId)
        val url = "${HentaimamaParser.BASE_URL}/tvshows/$slug/"
        val headers = mapOf("User-Agent" to HentaimamaParser.USER_AGENT)
        val resp = httpClient.get(url, headers, allowCloudflareBypass = false)
        return HentaimamaParser.parseEpisodesHtml(resp.body)
    }

    private suspend fun resolveHentaimamaMediaCandidates(
        providerId: String,
        episode: String,
    ): MediaCandidateList {
        val episodes = fetchHentaimamaEpisodes(providerId)
        val epUrl = episodes.firstOrNull { it.episodeNumber == episode }?.episodeUrl
            ?: episodes.firstOrNull()?.episodeUrl
            ?: return MediaCandidateList()
        val headers = mapOf("User-Agent" to HentaimamaParser.USER_AGENT)
        val epResp = httpClient.get(epUrl, headers, allowCloudflareBypass = false)
        val postId = HentaimamaParser.parsePostId(epResp.body) ?: return MediaCandidateList()
        val ajaxResp = httpClient.postForm(
            url = "${HentaimamaParser.BASE_URL}/wp-admin/admin-ajax.php",
            formParams = mapOf(
                "action" to "get_player_contents",
                "a" to postId,
                "i" to "1",
            ),
            headers = mapOf(
                "User-Agent" to HentaimamaParser.USER_AGENT,
                "Referer" to epUrl,
                "X-Requested-With" to "XMLHttpRequest",
            ),
        )
        val embedUrl = HentaimamaParser.parseAjaxEmbedUrl(ajaxResp.body) ?: return MediaCandidateList()
        val embedResp = httpClient.get(
            embedUrl,
            mapOf("User-Agent" to HentaimamaParser.USER_AGENT, "Referer" to "${HentaimamaParser.BASE_URL}/"),
            allowCloudflareBypass = false,
        )
        return HentaimamaParser.parseEmbedCandidates(embedResp.body)
    }

    private suspend fun resolveHentaimamaFallbackByTitles(
        titles: List<String>,
        episode: String,
    ): List<StreamSource> {
        for (query in buildHentaiStreamQueries(titles)) {
            val results = runCatching { searchHentaimama(query) }.getOrDefault(emptyList())
            val matched = results.firstOrNull { item ->
                titles.any { title -> isExactOrVariantMatch(item, title) }
            } ?: continue
            val candidates = runCatching {
                resolveHentaimamaMediaCandidates(matched.providerId, episode).candidates
            }.getOrDefault(emptyList())
            if (candidates.isNotEmpty()) {
                return candidates.map { cand ->
                    StreamSource(
                        qualityLabel = cand.quality,
                        height = QualitySelector.parseHeight(cand.quality),
                        serverName = cand.server,
                        url = cand.url,
                        referer = cand.referer,
                        origin = cand.origin,
                        isHls = cand.format == "hls",
                        audioUrl = null,
                        subtitleUrl = cand.subtitleUrl,
                    )
                }
            }
        }
        return emptyList()
    }

    private suspend fun searchHanime1(query: String): List<ProviderAnime> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "${Hanime1Parser.BASE_URL}/search?query=$encoded"
        val headers = mapOf("User-Agent" to Hanime1Parser.USER_AGENT)
        val resp = httpClient.get(url, headers, allowCloudflareBypass = false)
        return Hanime1Parser.parseSearchHtml(resp.body)
    }

    private suspend fun resolveHanime1MediaCandidates(providerId: String): MediaCandidateList {
        val videoId = Hanime1Parser.extractVideoId(providerId)
        val url = "${Hanime1Parser.BASE_URL}/watch?v=$videoId"
        val headers = mapOf(
            "User-Agent" to Hanime1Parser.USER_AGENT,
            "Referer" to "${Hanime1Parser.BASE_URL}/",
        )
        val resp = httpClient.get(url, headers, allowCloudflareBypass = false)
        return Hanime1Parser.parseWatchPageCandidates(resp.body)
    }

    private suspend fun resolveHanime1FallbackByTitles(titles: List<String>): List<StreamSource> {
        for (query in buildHentaiStreamQueries(titles)) {
            val results = runCatching { searchHanime1(query) }.getOrDefault(emptyList())
            val matched = results.firstOrNull { item ->
                titles.any { title -> isExactOrVariantMatch(item, title) }
            } ?: continue
            val candidates = runCatching {
                resolveHanime1MediaCandidates(matched.providerId).candidates
            }.getOrDefault(emptyList())
            if (candidates.isNotEmpty()) {
                return candidates.map { cand ->
                    StreamSource(
                        qualityLabel = cand.quality,
                        height = QualitySelector.parseHeight(cand.quality),
                        serverName = cand.server,
                        url = cand.url,
                        referer = cand.referer,
                        origin = cand.origin,
                        isHls = cand.format == "hls",
                        audioUrl = null,
                        subtitleUrl = cand.subtitleUrl,
                    )
                }
            }
        }
        return emptyList()
    }
}
