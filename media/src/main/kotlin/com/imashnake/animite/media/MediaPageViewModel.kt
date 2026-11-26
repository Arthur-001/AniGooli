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
import com.anigooli.streaming.EpisodePlayerNavigator
import com.anigooli.streaming.QualitySelector
import com.anigooli.streaming.StreamingRepository
import com.imashnake.animite.api.anilist.AnilistMediaRepository
import com.imashnake.animite.api.anilist.FranchiseResolver
import com.imashnake.animite.api.anilist.OrganizedFranchise
import com.imashnake.animite.api.anilist.sanitize.media.Media
import com.imashnake.animite.api.anilist.type.MediaType
import com.imashnake.animite.api.preferences.MANGA_SORT_NEWEST_FIRST
import com.imashnake.animite.api.preferences.MANGA_SORT_OLDEST_FIRST
import com.imashnake.animite.api.preferences.PLAYER_MPV
import com.imashnake.animite.api.preferences.PreferencesRepository
import com.imashnake.animite.core.resource.Resource
import com.imashnake.animite.core.resource.Resource.Companion.asResource
import com.imashnake.animite.core.ui.Constants
import dagger.hilt.android.lifecycle.HiltViewModel
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
    /** Available episode strings (e.g. ["1", "2", ...]). */
    val episodes: List<String> = emptyList(),
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
    private val downloadRepository: DownloadRepository,
    private val libraryRepository: LibraryRepository,
    private val playerNavigator: EpisodePlayerNavigator,
    private val preferencesRepository: PreferencesRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val navArgs = savedStateHandle.toRoute<MediaPage>()
    private val parsedMediaType = MediaType.safeValueOf(navArgs.mediaType)
    private val refreshTrigger = MutableSharedFlow<Unit>()

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

    /** Active and completed downloads mapped by episode number for quick inline status indicators. */
    val downloadsByEpisode: StateFlow<Map<String, DownloadEpisodeEntity>> =
        downloadRepository.observeEpisodesForAnime(navArgs.id)
            .map { list -> list.associateBy { it.episodeNumber } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    /** Detailed media resource from AniList. */
    val media = combine(
        flow = refreshTrigger.onStart { emit(Unit) },
        flow2 = preferencesRepository.language.filterNotNull(),
        flow3 = preferencesRepository.listSize.filterNotNull(),
        transform = ::Triple,
    ).flatMapLatest { (_, language, listSize) ->
        mediaRepository.fetchMedia(
            id = navArgs.id,
            mediaType = parsedMediaType,
            perPage = listSize,
            language = Media.Language.safeValueOf(language),
        )
    }.asResource().stateIn(
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
                if (loadedMedia != null) {
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

    /** Streams [episode], respecting preferred player (`IN_APP` vs `MPV`) or forcing MPV when [forceMpv] is true. */
    fun watchEpisode(episode: String, forceMpv: Boolean = false) {
        val currentMedia = media.value.data ?: return
        val providerId = _streamingState.value.providerId ?: return
        val allEps = _streamingState.value.episodes
        val existingDownload = downloadsByEpisode.value[episode]
        val localPath = existingDownload?.videoFilePath
        if (localPath != null && existingDownload.status == "COMPLETED") {
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
                        )
                        return@onSuccess
                    }
                    _streamingState.update { it.copy(statusMessage = "MPV not installed; using in-app player") }
                }
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
        _streamingState.update { it.copy(isLoading = true, errorMessage = null) }
        val titles = listOfNotNull(loadedMedia.englishTitle, loadedMedia.romajiTitle, loadedMedia.title)
        val providerRes = streamingRepository.resolveAnime(loadedMedia.id, titles)
        val provider = providerRes.getOrElse { err ->
            _streamingState.update {
                it.copy(isLoading = false, errorMessage = err.message ?: "Unavailable on streaming provider")
            }
            return
        }
        val episodesRes = streamingRepository.fetchEpisodes(provider.providerId)
        episodesRes.onSuccess { eps ->
            _streamingState.update {
                it.copy(isLoading = false, providerId = provider.providerId, episodes = eps)
            }
        }.onFailure { err ->
            _streamingState.update {
                it.copy(isLoading = false, providerId = provider.providerId, errorMessage = err.message)
            }
        }
    }

    /** Builds a DownloadManager request for saving cover/banner images. */
    fun requestDownloadImage(imageUrl: String): DownloadManager.Request =
        DownloadManager.Request(imageUrl.toUri())
            .setMimeType("image/jpeg")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setTitle("image.jpg")
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "image.jpg")
}
