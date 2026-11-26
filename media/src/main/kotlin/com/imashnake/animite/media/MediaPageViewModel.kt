package com.imashnake.animite.media

import android.app.DownloadManager
import android.os.Environment
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.anigooli.downloads.DownloadEpisodeEntity
import com.anigooli.downloads.DownloadRepository
import com.anigooli.library.LibraryRepository
import com.anigooli.streaming.AudioTrack
import com.anigooli.streaming.CensorshipVersion
import com.anigooli.streaming.EpisodePlayerNavigator
import com.anigooli.streaming.JikanAnimeInfo
import com.anigooli.streaming.JikanMetadataClient
import com.anigooli.streaming.QualitySelector
import com.anigooli.streaming.ResolvedAnimeVersions
import com.anigooli.streaming.StreamingRepository
import com.imashnake.animite.api.anilist.AnilistMediaRepository
import com.imashnake.animite.api.anilist.FranchiseResolver
import com.imashnake.animite.api.anilist.OrganizedFranchise
import com.imashnake.animite.api.anilist.sanitize.media.Media
import com.imashnake.animite.api.anilist.type.MediaType
import com.imashnake.animite.api.preferences.CENSORSHIP_UNCENSORED
import com.imashnake.animite.api.preferences.MANGA_SORT_NEWEST_FIRST
import com.imashnake.animite.api.preferences.MANGA_SORT_OLDEST_FIRST
import com.imashnake.animite.api.preferences.PLAYER_MPV
import com.imashnake.animite.api.preferences.PreferencesRepository
import com.imashnake.animite.core.resource.Resource
import com.imashnake.animite.core.resource.Resource.Companion.asResource
import com.imashnake.animite.core.ui.Constants
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val STOP_TIMEOUT_MS = 5_000L

/** Streaming episode list and active download state for an anime page. */
data class StreamingEpisodesUiState(
    /** True while resolving the provider show ID and episode numbers. */
    val isLoading: Boolean = false,
    /** Provider show ID if matched. */
    val providerId: String? = null,
    /** Available uncensored/censored provider entries when resolved. */
    val availableVersions: ResolvedAnimeVersions? = null,
    /** Available episode strings (e.g. ["1", "2", ...]). */
    val episodes: List<String> = emptyList(),
    /** Optional episode title map keyed by episode number string. */
    val episodeTitles: Map<String, String> = emptyMap(),
    /** Episode currently being resolved to launch playback/download. */
    val busyEpisode: String? = null,
    /** Non-null error message when stream or episode lookup fails. */
    val errorMessage: String? = null,
    /** Transient status message (e.g. download started or MPV missing fallback). */
    val statusMessage: String? = null,
)

/** ViewModel backing [MediaPage], franchise resolution, offline list toggle, and episode streaming/downloads. */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions", "LongParameterList")
class MediaPageViewModel @Inject constructor(
    private val mediaRepository: AnilistMediaRepository,
    private val franchiseResolver: FranchiseResolver,
    private val streamingRepository: StreamingRepository,
    private val jikanMetadataClient: JikanMetadataClient,
    private val downloadRepository: DownloadRepository,
    private val libraryRepository: LibraryRepository,
    private val playerNavigator: EpisodePlayerNavigator,
    private val preferencesRepository: PreferencesRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val navArgs = savedStateHandle.toRoute<MediaPage>()
    private val parsedMediaType = MediaType.safeValueOf(navArgs.mediaType)
    private val refreshTrigger = MutableSharedFlow<Unit>()
    private val loadedJikanEpisodePages = HashSet<Int>()
    private var resolvedMalId: Int? = navArgs.malId?.takeIf { it > 0 }

    /** Navigation source tag for shared element transitions. */
    val source = savedStateHandle.getStateFlow(Constants.SOURCE, navArgs.source)
    /** Active AniList media ID. */
    val id = savedStateHandle.getStateFlow(Constants.ID, navArgs.id)
    /** True if this page displays an anime (streamable). */
    val isAnime: Boolean = parsedMediaType == MediaType.ANIME

    /** Character/staff carousel list size preference. */
    val listSize = preferencesRepository.listSize.filterNotNull().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = 10,
    )

    /** Persisted user preference for sorting the Mangas relation row newest-first vs oldest-first. */
    val mangaNewestFirst: StateFlow<Boolean> = preferencesRepository.mangaRelationsSortOrder
        .map { it == MANGA_SORT_NEWEST_FIRST }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /** True when this entry is currently saved in the user's Offline Personal Anime List. */
    val isInOfflineList: StateFlow<Boolean> = libraryRepository.observeIsInList(navArgs.id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /** Remembered audio track for this specific anime (falls back to Global Settings default: Japanese). */
    val audioTrack: StateFlow<AudioTrack> = combine(
        libraryRepository.observePlaybackPrefs(navArgs.id),
        preferencesRepository.defaultAudioTrack,
    ) { perAnime, globalDefault ->
        AudioTrack.safeValueOf(perAnime?.audioTrack ?: globalDefault)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AudioTrack.JAPANESE)

    /** Remembered English subtitles toggle for this specific anime (falls back to Global default: ON). */
    val subtitlesEnabled: StateFlow<Boolean> = combine(
        libraryRepository.observePlaybackPrefs(navArgs.id),
        preferencesRepository.defaultSubtitlesEnabled,
    ) { perAnime, globalDefault ->
        perAnime?.subtitlesEnabled ?: globalDefault ?: true
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), true)

    /** Persisted preference controlling whether episode names appear alongside episode numbers. */
    val showEpisodeNames: StateFlow<Boolean> = preferencesRepository.showEpisodeNames
        .map { it ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /** Remembered censorship version for this anime, defaulting to [CensorshipVersion.UNCENSORED]. */
    val censorshipVersion: StateFlow<CensorshipVersion> = combine(
        libraryRepository.observePlaybackPrefs(navArgs.id),
        preferencesRepository.defaultCensorshipVersion,
    ) { perAnime, globalDefault ->
        CensorshipVersion.safeValueOf(perAnime?.censorshipVersion ?: globalDefault ?: CENSORSHIP_UNCENSORED)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CensorshipVersion.UNCENSORED)

    /** Last watched episode string for this anime if recorded. */
    val lastWatchedEpisode: StateFlow<String?> = libraryRepository.observePlaybackPrefs(navArgs.id)
        .map { it?.lastEpisode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Active and completed downloads mapped by episode number for quick inline status indicators. */
    val downloadsByEpisode: StateFlow<Map<String, DownloadEpisodeEntity>> =
        downloadRepository.observeEpisodesForAnime(navArgs.id)
            .map { list -> list.associateBy { it.episodeNumber } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    /** Detailed media resource from AniList, or Jikan fallback for provider-only entries. */
    val media = combine(
        flow = refreshTrigger.onStart { emit(Unit) },
        flow2 = preferencesRepository.language.filterNotNull(),
        flow3 = preferencesRepository.listSize.filterNotNull(),
        transform = ::Triple,
    ).flatMapLatest { (_, language, listSize) ->
        if (navArgs.id > 0) {
            mediaRepository.fetchMedia(
                id = navArgs.id,
                mediaType = parsedMediaType,
                perPage = listSize,
                language = Media.Language.safeValueOf(language),
            ).asResource()
        } else {
            flow {
                emit(Resource.loading())
                val jikanInfo = navArgs.malId?.takeIf { it > 0 }?.let { jikanMetadataClient.fetchByMalId(it) }
                    ?: navArgs.fallbackTitle?.let { q -> jikanMetadataClient.searchAnime(q, limit = 1).firstOrNull() }
                jikanInfo?.malId?.takeIf { it > 0 }?.let { resolvedMalId = it }
                emit(Resource.success(buildFallbackMedia(jikanInfo)))
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(1000),
        initialValue = Resource.loading(),
    )

    private val _franchise = MutableStateFlow<OrganizedFranchise?>(null)
    /** Multi-hop organized franchise sections (Main Seasons & Movies, OVAs, Mangas). */
    val franchise: StateFlow<OrganizedFranchise?> = _franchise.asStateFlow()

    private val _streamingState = MutableStateFlow(StreamingEpisodesUiState())
    /** Resolved streaming episode list and busy/error state. */
    val streamingState: StateFlow<StreamingEpisodesUiState> = _streamingState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                media,
                preferencesRepository.language.filterNotNull(),
                mangaNewestFirst,
            ) { mediaRes, lang, newestFirst ->
                Triple(mediaRes.data, Media.Language.safeValueOf(lang), newestFirst)
            }.collect { (loadedMedia, lang, newestFirst) ->
                if (loadedMedia != null && loadedMedia.id > 0) {
                    _franchise.value = franchiseResolver.resolveFranchise(
                        rootMedia = loadedMedia,
                        language = lang,
                        mangaNewestFirst = newestFirst,
                    )
                }
            }
        }

        if (isAnime) {
            viewModelScope.launch {
                val loadedMedia = media.filterNotNull().map { it.data }.firstOrNull { it != null }
                if (loadedMedia != null) {
                    loadStreamingEpisodes(loadedMedia)
                }
            }
        }
    }

    /** Retries resolving streaming episodes for the active anime. */
    fun retryStreamingLookup() {
        val loaded = media.value.data ?: return
        viewModelScope.launch {
            loadStreamingEpisodes(loaded)
        }
    }

    /** Toggles and persists the Oldest ↔ Newest sort order for the Mangas relation row. */
    fun toggleMangaSortOrder() = viewModelScope.launch {
        val next = if (mangaNewestFirst.value) MANGA_SORT_OLDEST_FIRST else MANGA_SORT_NEWEST_FIRST
        preferencesRepository.setMangaRelationsSortOrder(next)
    }

    /** Adds or removes this entry from the Offline Personal Anime List. */
    fun toggleOfflineList() {
        val currentMedia = media.value.data ?: return
        viewModelScope.launch {
            if (isInOfflineList.value) {
                libraryRepository.removeByAnilistId(currentMedia.id)
            } else {
                libraryRepository.addOrUpdateFromAnilist(
                    anilistId = currentMedia.id,
                    titleEnglish = currentMedia.englishTitle ?: currentMedia.title,
                    titleRomaji = currentMedia.romajiTitle,
                    titleNative = currentMedia.nativeTitle,
                    coverUrl = currentMedia.coverImage,
                    totalEpisodes = currentMedia.totalEpisodes,
                    genreNames = currentMedia.genres,
                )
            }
        }
    }

    /** Updates and saves the audio track (`JAPANESE` or `ENGLISH_DUB`) specifically for this anime. */
    fun setAnimeAudioTrack(track: AudioTrack) = viewModelScope.launch {
        libraryRepository.savePlaybackPrefs(
            anilistId = navArgs.id,
            audioTrack = track.name,
            subtitlesEnabled = subtitlesEnabled.value,
        )
    }

    /** Updates and saves the English subtitles toggle specifically for this anime. */
    fun setAnimeSubtitlesEnabled(enabled: Boolean) = viewModelScope.launch {
        libraryRepository.savePlaybackPrefs(
            anilistId = navArgs.id,
            audioTrack = audioTrack.value.name,
            subtitlesEnabled = enabled,
        )
    }

    /** Toggles whether episode names are displayed alongside episode numbers and persists the choice. */
    fun toggleShowEpisodeNames() = viewModelScope.launch {
        val next = !showEpisodeNames.value
        preferencesRepository.setShowEpisodeNames(next)
        if (next) {
            loadEpisodeNamesForEpisode(1)
        }
    }

    /** Updates and persists the censored vs uncensored version choice for this anime and reloads episodes. */
    fun setCensorshipVersion(version: CensorshipVersion) = viewModelScope.launch {
        libraryRepository.savePlaybackPrefs(
            anilistId = navArgs.id,
            audioTrack = audioTrack.value.name,
            subtitlesEnabled = subtitlesEnabled.value,
            censorshipVersion = version.name,
        )
        val versions = _streamingState.value.availableVersions ?: return@launch
        val targetProvider = versions.providerFor(version)
        if (targetProvider.providerId != _streamingState.value.providerId) {
            _streamingState.update { it.copy(isLoading = true, errorMessage = null) }
            val episodesRes = streamingRepository.fetchEpisodes(targetProvider.providerId)
            episodesRes.onSuccess { eps ->
                _streamingState.update {
                    it.copy(
                        isLoading = false,
                        providerId = targetProvider.providerId,
                        episodes = eps,
                    )
                }
            }.onFailure { err ->
                _streamingState.update {
                    it.copy(
                        isLoading = false,
                        providerId = targetProvider.providerId,
                        errorMessage = err.message,
                    )
                }
            }
        }
    }

    /** Loads Jikan episode titles for the page containing [episodeNumber] when available. */
    fun loadEpisodeNamesForEpisode(episodeNumber: Int) {
        val malId = resolvedMalId ?: return
        val page = ((episodeNumber - 1).coerceAtLeast(0) / 100) + 1
        if (!loadedJikanEpisodePages.add(page)) return
        viewModelScope.launch {
            val fetched = jikanMetadataClient.fetchEpisodeTitles(malId, page)
            if (fetched.isNotEmpty()) {
                _streamingState.update { state ->
                    val merged = state.episodeTitles.toMutableMap()
                    fetched.forEach { (ep, title) ->
                        merged.putIfAbsent(ep, title)
                    }
                    state.copy(episodeTitles = merged)
                }
            }
        }
    }

    /** Streams [episode], respecting preferred player (`IN_APP` vs `MPV`) or forcing MPV when [forceMpv] is true. */
    fun watchEpisode(episode: String, forceMpv: Boolean = false) {
        val currentMedia = media.value.data ?: return
        val providerId = _streamingState.value.providerId ?: return
        val allEps = _streamingState.value.episodes
        val existingDownload = downloadsByEpisode.value[episode]
        val localPath = existingDownload?.videoFilePath
        if (localPath != null && existingDownload.status == "COMPLETED") {
            viewModelScope.launch {
                libraryRepository.recordWatchHistory(
                    anilistId = currentMedia.id,
                    title = currentMedia.title ?: "Anime",
                    coverUrl = currentMedia.coverImage,
                    episodeWatched = episode,
                    providerId = providerId,
                    malId = resolvedMalId,
                )
            }
            if (forceMpv) {
                val ok = playerNavigator.openInExternalMpv(
                    title = "${currentMedia.title ?: "Anime"} - Episode $episode",
                    urlOrPath = localPath,
                    isLocalFile = true,
                    subtitleUrl = existingDownload.subtitleFilePath,
                )
                if (!ok) {
                    _streamingState.update { it.copy(statusMessage = "MPV not installed; opening in-app player") }
                    playerNavigator.openLocalFile(
                        anilistId = currentMedia.id,
                        animeTitle = currentMedia.title ?: "Anime",
                        episode = episode,
                        videoFilePath = localPath,
                        subtitleFilePath = existingDownload.subtitleFilePath,
                    )
                }
            } else {
                playerNavigator.openLocalFile(
                    anilistId = currentMedia.id,
                    animeTitle = currentMedia.title ?: "Anime",
                    episode = episode,
                    videoFilePath = localPath,
                    subtitleFilePath = existingDownload.subtitleFilePath,
                )
            }
            return
        }

        viewModelScope.launch {
            _streamingState.update { it.copy(busyEpisode = episode, errorMessage = null) }
            val prefs = libraryRepository.getEffectivePlaybackPrefs(currentMedia.id)
            val quality = prefs.preferredQualityOverride
                ?: preferencesRepository.defaultStreamQuality.firstOrNull()
                ?: QualitySelector.standardQualities[3]

            val res = streamingRepository.fetchEpisodeStreams(
                providerId = providerId,
                episode = episode,
                requestedAudioTrack = audioTrack.value,
                preferredQuality = quality,
            )
            res.onSuccess { bundle ->
                _streamingState.update { it.copy(busyEpisode = null) }
                val prefPlayer = preferencesRepository.preferredPlayer.firstOrNull()
                val useMpv = forceMpv || prefPlayer == PLAYER_MPV
                if (useMpv) {
                    val launched = playerNavigator.openInExternalMpv(
                        title = "${currentMedia.title ?: "Anime"} - Episode $episode",
                        urlOrPath = bundle.selectedSource.url,
                        isLocalFile = false,
                        referer = bundle.selectedSource.referer,
                        origin = bundle.selectedSource.origin,
                        audioUrl = bundle.selectedSource.audioUrl,
                        subtitleUrl = if (subtitlesEnabled.value) bundle.selectedSource.subtitleUrl else null,
                    )
                    if (launched) {
                        libraryRepository.recordWatchHistory(
                            anilistId = currentMedia.id,
                            title = currentMedia.title ?: "Anime",
                            coverUrl = currentMedia.coverImage,
                            episodeWatched = episode,
                            providerId = providerId,
                            malId = resolvedMalId,
                        )
                        libraryRepository.savePlaybackPrefs(
                            anilistId = currentMedia.id,
                            audioTrack = audioTrack.value.name,
                            subtitlesEnabled = subtitlesEnabled.value,
                            lastEpisode = episode,
                        )
                        return@onSuccess
                    }
                    _streamingState.update { it.copy(statusMessage = "MPV not installed; using in-app player") }
                }
                libraryRepository.recordWatchHistory(
                    anilistId = currentMedia.id,
                    title = currentMedia.title ?: "Anime",
                    coverUrl = currentMedia.coverImage,
                    episodeWatched = episode,
                    providerId = providerId,
                    malId = resolvedMalId,
                )
                playerNavigator.openOnlineStream(
                    anilistId = currentMedia.id,
                    animeTitle = currentMedia.title ?: "Anime",
                    coverUrl = currentMedia.coverImage,
                    providerId = providerId,
                    episode = episode,
                    allEpisodes = allEps,
                    bundle = bundle,
                )
            }.onFailure { err ->
                _streamingState.update {
                    it.copy(busyEpisode = null, errorMessage = err.message ?: "Unable to resolve episode $episode")
                }
            }
        }
    }

    /** Enqueues a full local download of [episode] for offline playback. */
    fun downloadEpisode(episode: String) {
        val currentMedia = media.value.data ?: return
        val providerId = _streamingState.value.providerId ?: return
        viewModelScope.launch {
            _streamingState.update { it.copy(busyEpisode = episode, errorMessage = null) }
            val prefs = libraryRepository.getEffectivePlaybackPrefs(currentMedia.id)
            val quality = prefs.preferredQualityOverride
                ?: preferencesRepository.defaultStreamQuality.firstOrNull()
                ?: QualitySelector.standardQualities[3]
            val res = streamingRepository.fetchEpisodeStreams(
                providerId = providerId,
                episode = episode,
                requestedAudioTrack = audioTrack.value,
                preferredQuality = quality,
            )
            res.onSuccess { bundle ->
                downloadRepository.enqueueDownload(
                    anilistId = currentMedia.id,
                    animeTitle = currentMedia.title ?: "Anime",
                    coverUrl = currentMedia.coverImage,
                    episodeNumber = episode,
                    audioTrack = bundle.actualAudioTrack,
                    source = bundle.selectedSource,
                )
                _streamingState.update {
                    it.copy(busyEpisode = null, statusMessage = "Downloading Episode $episode")
                }
            }.onFailure { err ->
                _streamingState.update {
                    it.copy(busyEpisode = null, errorMessage = err.message ?: "Failed to queue download")
                }
            }
        }
    }

    /** Pauses an active episode download. */
    fun pauseDownload(episodeId: String) = viewModelScope.launch {
        downloadRepository.pauseDownload(episodeId)
    }

    /** Resumes a paused or failed episode download. */
    fun resumeDownload(episodeId: String) = viewModelScope.launch {
        downloadRepository.resumeDownload(episodeId)
    }

    /** Clears transient status banner. */
    fun clearStatusMessage() {
        _streamingState.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    private suspend fun loadStreamingEpisodes(loadedMedia: Media) {
        val initialTitles = buildMap {
            loadedMedia.streamingEpisodes.forEach { ep ->
                val num = ep.number?.trim()
                val epTitle = (ep.displayTitle ?: ep.title)?.trim()
                if (!num.isNullOrEmpty() && !epTitle.isNullOrEmpty()) {
                    put(num, epTitle)
                }
            }
        }
        _streamingState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                episodeTitles = initialTitles,
            )
        }
        val titles = listOfNotNull(
            loadedMedia.englishTitle,
            loadedMedia.romajiTitle,
            loadedMedia.title,
            navArgs.fallbackTitle,
        )
        val versionsRes = streamingRepository.resolveAnimeVersions(
            anilistId = loadedMedia.id,
            candidateTitles = titles,
            explicitProviderId = navArgs.providerId,
        )
        val versions = versionsRes.getOrElse { err ->
            _streamingState.update {
                it.copy(isLoading = false, errorMessage = err.message ?: "Unavailable on streaming provider")
            }
            return
        }
        if (resolvedMalId == null) {
            resolvedMalId = versions.defaultProvider.malId?.takeIf { it > 0 }
        }
        val effectivePrefs = libraryRepository.getEffectivePlaybackPrefs(loadedMedia.id)
        val preferredVersion = CensorshipVersion.safeValueOf(effectivePrefs.censorshipVersion)
        val selectedProvider = versions.providerFor(preferredVersion)

        val episodesRes = streamingRepository.fetchEpisodes(selectedProvider.providerId)
        episodesRes.onSuccess { eps ->
            _streamingState.update {
                it.copy(
                    isLoading = false,
                    providerId = selectedProvider.providerId,
                    availableVersions = versions,
                    episodes = eps,
                )
            }
            if (showEpisodeNames.value) {
                loadEpisodeNamesForEpisode(1)
            }
        }.onFailure { err ->
            _streamingState.update {
                it.copy(
                    isLoading = false,
                    providerId = selectedProvider.providerId,
                    availableVersions = versions,
                    errorMessage = err.message,
                )
            }
        }
    }

    private fun buildFallbackMedia(jikan: JikanAnimeInfo?): Media {
        val displayTitle = jikan?.titleEnglish ?: jikan?.title ?: navArgs.fallbackTitle ?: "Anime"
        val format = jikan?.type?.let { Media.Format.safeValueOf(it.uppercase()) }
        val infoList = buildList {
            format?.let { add(Media.Info.Format(it)) }
            jikan?.episodes?.let { add(Media.Info.Item(Media.InfoItem.EPISODES, it.toString())) }
        }.toImmutableList()
        return Media(
            id = navArgs.id,
            bannerImage = jikan?.coverImageUrl,
            coverImage = jikan?.coverImageUrl,
            color = "#00000000",
            title = displayTitle,
            englishTitle = jikan?.titleEnglish ?: navArgs.fallbackTitle,
            romajiTitle = jikan?.title ?: navArgs.fallbackTitle,
            nativeTitle = jikan?.titleJapanese,
            mediaFormat = format,
            totalEpisodes = jikan?.episodes,
            startYear = jikan?.year,
            startMonth = null,
            startDay = null,
            otherTitles = listOfNotNull(jikan?.title, jikan?.titleEnglish, jikan?.titleJapanese)
                .distinct()
                .toImmutableList(),
            description = jikan?.synopsis.orEmpty(),
            nextAiring = null,
            info = infoList,
            year = jikan?.year?.toString(),
            season = null,
            rankings = persistentListOf(),
            genres = jikan?.genres.orEmpty().toImmutableList(),
            characters = persistentListOf(),
            staff = persistentListOf(),
            trailer = null,
            streamingEpisodes = persistentListOf(),
            relations = persistentListOf(),
            recommendations = persistentListOf(),
        )
    }

    /** Builds a DownloadManager request for saving cover/banner images. */
    fun requestDownloadImage(imageUrl: String): DownloadManager.Request =
        DownloadManager.Request(imageUrl.toUri())
            .setMimeType("image/jpeg")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setTitle("image.jpg")
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "image.jpg")
}
