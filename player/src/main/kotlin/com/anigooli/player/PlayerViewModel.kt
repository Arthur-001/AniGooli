package com.anigooli.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.ui.AspectRatioFrameLayout
import com.anigooli.downloads.DownloadRepository
import com.anigooli.downloads.PreBufferManager
import com.anigooli.downloads.PreBufferPolicy
import com.anigooli.downloads.PreBufferState
import com.anigooli.library.LibraryRepository
import com.anigooli.streaming.AudioTrack
import com.anigooli.streaming.EpisodeStreamBundle
import com.anigooli.streaming.ExtractorConfig
import com.anigooli.streaming.QualitySelector
import com.anigooli.streaming.StreamSource
import com.anigooli.streaming.StreamingRepository
import com.imashnake.animite.api.preferences.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val DEFAULT_SPEED = 1.0f

/** Resize/aspect-ratio modes cycled inside [PlayerScreen]. */
enum class VideoAspectMode(val label: String, val media3ResizeMode: Int) {
    FIT("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FILL("Fill", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    ZOOM("Zoom", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
}

/** Reactive UI state for the in-app Media3 gesture player. */
data class PlayerUiState(
    /** AniList ID if linked, or null for local non-AniList file. */
    val anilistId: Int? = null,
    /** Anime title displayed in the HUD. */
    val animeTitle: String = "",
    /** Cover URL for watch history tracking. */
    val coverUrl: String? = null,
    /** Upstream provider ID for episode/audio switching. */
    val providerId: String? = null,
    /** Current episode identifier. */
    val episode: String = "1",
    /** Ordered available episodes for Next/Prev navigation. */
    val allEpisodes: List<String> = emptyList(),
    /** True when playing a downloaded file offline. */
    val isLocalFile: Boolean = false,
    /** Local video file path when offline. */
    val localVideoPath: String? = null,
    /** Local .vtt subtitle path when offline. */
    val localSubtitlePath: String? = null,
    /** Available stream qualities/servers for current episode. */
    val sources: List<StreamSource> = emptyList(),
    /** Currently active stream source. */
    val selectedSource: StreamSource? = null,
    /** Active audio track (Japanese or English Dub), remembered per anime. */
    val audioTrack: AudioTrack = AudioTrack.JAPANESE,
    /** Whether English subtitles are active, remembered per anime. */
    val subtitlesEnabled: Boolean = true,
    /** True when English Dub was requested but fell back to Japanese. */
    val isDubFallback: Boolean = false,
    /** Current playback speed multiplier. */
    val playbackSpeed: Float = DEFAULT_SPEED,
    /** Active video scaling mode. */
    val aspectMode: VideoAspectMode = VideoAspectMode.FIT,
    /** Resume position in ms to seek to when source prepares. */
    val targetSeekPositionMs: Long = 0L,
    /** Active HTTP User-Agent used for stream requests. */
    val userAgent: String = ExtractorConfig.DEFAULT_USER_AGENT,
    /** True while fetching a new episode or switching audio track. */
    val isLoading: Boolean = false,
    /** Non-null when an error occurred loading an episode/track. */
    val errorMessage: String? = null,
    /** Non-null transient toast/banner message inside player (e.g. download started). */
    val statusBanner: String? = null,
    /** Live single-slot pre-buffer state. */
    val preBufferState: PreBufferState = PreBufferState(),
)

/** ViewModel orchestrating per-anime audio/sub memory, single-slot pre-buffering, quality switching, and downloads. */
@HiltViewModel
@Suppress("TooManyFunctions")
class PlayerViewModel @Inject constructor(
    private val launchStore: PlayerLaunchStore,
    private val streamingRepository: StreamingRepository,
    private val downloadRepository: DownloadRepository,
    private val libraryRepository: LibraryRepository,
    val preBufferManager: PreBufferManager,
    private val externalPlayerLauncher: ExternalPlayerLauncher,
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    /** Observable UI state for [PlayerScreen]. */
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            preBufferManager.state.collect { pb ->
                _uiState.update { it.copy(preBufferState = pb) }
            }
        }
        viewModelScope.launch {
            launchStore.pendingLaunch.collect { payload ->
                if (payload != null) {
                    initializeFromPayload(payload)
                }
            }
        }
    }

    private suspend fun initializeFromPayload(payload: PlayerLaunchPayload) {
        val prefs = payload.anilistId?.let { libraryRepository.getEffectivePlaybackPrefs(it) }
        val resolvedAudio = prefs?.let { AudioTrack.safeValueOf(it.audioTrack) }
            ?: payload.streamBundle?.actualAudioTrack
            ?: AudioTrack.JAPANESE
        val resolvedSubs = prefs?.subtitlesEnabled ?: true
        val resumePos = if (prefs != null && prefs.lastEpisode == payload.episode) {
            prefs.lastPositionMs
        } else {
            0L
        }

        val bundle = payload.streamBundle
        val activeSource = bundle?.selectedSource
        val sources = bundle?.sources.orEmpty()

        _uiState.value = PlayerUiState(
            anilistId = payload.anilistId,
            animeTitle = payload.animeTitle,
            coverUrl = payload.coverUrl,
            providerId = payload.providerId,
            episode = payload.episode,
            allEpisodes = payload.allEpisodes,
            isLocalFile = payload.isLocalFile,
            localVideoPath = payload.localVideoPath,
            localSubtitlePath = payload.localSubtitlePath,
            sources = sources,
            selectedSource = activeSource,
            audioTrack = bundle?.actualAudioTrack ?: resolvedAudio,
            subtitlesEnabled = resolvedSubs,
            isDubFallback = bundle?.isDubFallback == true,
            targetSeekPositionMs = resumePos,
        )

        if (!payload.isLocalFile && activeSource != null && payload.anilistId != null) {
            startPreBufferFor(payload.anilistId, payload.episode, activeSource, resolvedAudio)
        }
        if (payload.anilistId != null) {
            libraryRepository.recordWatchHistory(
                anilistId = payload.anilistId,
                title = payload.animeTitle,
                coverUrl = payload.coverUrl,
                episodeWatched = payload.episode,
            )
        }
    }

    /** Switches to another quality/server source while preserving exact [currentPositionMs]. */
    fun selectQuality(source: StreamSource, currentPositionMs: Long) {
        val current = _uiState.value
        _uiState.update {
            it.copy(
                selectedSource = source,
                targetSeekPositionMs = currentPositionMs,
                statusBanner = "Quality: ${source.qualityLabel}",
            )
        }
        val anilistId = current.anilistId ?: return
        viewModelScope.launch {
            libraryRepository.savePlaybackPrefs(
                anilistId = anilistId,
                audioTrack = current.audioTrack.name,
                subtitlesEnabled = current.subtitlesEnabled,
                preferredQualityOverride = source.qualityLabel,
                lastEpisode = current.episode,
                lastPositionMs = currentPositionMs,
            )
            startPreBufferFor(anilistId, current.episode, source, current.audioTrack)
        }
    }

    /** Switches audio track (Japanese ↔ English Dub), persists choice for this anime, and reloads stream. */
    fun switchAudioTrack(track: AudioTrack, currentPositionMs: Long) {
        val current = _uiState.value
        if (current.isLocalFile || current.providerId.isNullOrBlank()) return
        val anilistId = current.anilistId ?: return
        viewModelScope.launch {
            libraryRepository.savePlaybackPrefs(
                anilistId = anilistId,
                audioTrack = track.name,
                subtitlesEnabled = current.subtitlesEnabled,
                lastEpisode = current.episode,
                lastPositionMs = currentPositionMs,
            )
            loadOnlineEpisode(
                providerId = current.providerId,
                anilistId = anilistId,
                episode = current.episode,
                audioTrack = track,
                preservePositionMs = currentPositionMs,
            )
        }
    }

    /** Toggles English subtitles ON/OFF and immediately persists the choice for this specific anime. */
    fun setSubtitlesEnabled(enabled: Boolean) {
        val current = _uiState.value
        _uiState.update {
            it.copy(
                subtitlesEnabled = enabled,
                statusBanner = if (enabled) "English Subtitles ON" else "Subtitles OFF",
            )
        }
        val anilistId = current.anilistId ?: return
        viewModelScope.launch {
            libraryRepository.savePlaybackPrefs(
                anilistId = anilistId,
                audioTrack = current.audioTrack.name,
                subtitlesEnabled = enabled,
            )
        }
    }

    /** Loads the next or previous episode in [PlayerUiState.allEpisodes]. */
    fun navigateEpisode(targetEpisode: String) {
        val current = _uiState.value
        val providerId = current.providerId ?: return
        val anilistId = current.anilistId ?: return
        viewModelScope.launch {
            loadOnlineEpisode(
                providerId = providerId,
                anilistId = anilistId,
                episode = targetEpisode,
                audioTrack = current.audioTrack,
                preservePositionMs = 0L,
            )
            libraryRepository.recordWatchHistory(
                anilistId = anilistId,
                title = current.animeTitle,
                coverUrl = current.coverUrl,
                episodeWatched = targetEpisode,
            )
        }
    }

    /** Updates playback speed multiplier. */
    fun setPlaybackSpeed(speed: Float) {
        _uiState.update { it.copy(playbackSpeed = speed) }
    }

    /** Cycles video aspect ratio / zoom scaling mode. */
    fun cycleAspectMode() {
        val modes = VideoAspectMode.entries
        _uiState.update { state ->
            val next = modes[(modes.indexOf(state.aspectMode) + 1) % modes.size]
            state.copy(aspectMode = next, statusBanner = "Aspect: ${next.label}")
        }
    }

    /** Persists resume playback position for the active anime & episode. */
    fun savePlaybackProgress(positionMs: Long) {
        val current = _uiState.value
        val anilistId = current.anilistId ?: return
        viewModelScope.launch {
            libraryRepository.savePlaybackPrefs(
                anilistId = anilistId,
                audioTrack = current.audioTrack.name,
                subtitlesEnabled = current.subtitlesEnabled,
                lastEpisode = current.episode,
                lastPositionMs = positionMs,
            )
        }
    }

    /** Enqueues a full local download of the active episode, reusing any pre-buffered data. */
    fun downloadCurrentEpisode() {
        val current = _uiState.value
        val source = current.selectedSource ?: return
        val anilistId = current.anilistId ?: return
        viewModelScope.launch {
            downloadRepository.enqueueDownload(
                anilistId = anilistId,
                animeTitle = current.animeTitle,
                coverUrl = current.coverUrl,
                episodeNumber = current.episode,
                audioTrack = current.audioTrack,
                source = source,
            )
            _uiState.update {
                it.copy(statusBanner = "Download started (reusing pre-buffer)")
            }
        }
    }

    /** Opens the currently playing video (online or local) in external MPV (`is.xyz.mpv`). */
    fun openInMpv(): Boolean {
        val current = _uiState.value
        return if (current.isLocalFile) {
            val path = current.localVideoPath ?: return false
            externalPlayerLauncher.launchMpv(
                title = "${current.animeTitle} - Episode ${current.episode}",
                urlOrPath = path,
                isLocalFile = true,
                subtitleUrl = current.localSubtitlePath,
            )
        } else {
            val source = current.selectedSource ?: return false
            externalPlayerLauncher.launchMpv(
                title = "${current.animeTitle} - Episode ${current.episode}",
                urlOrPath = source.url,
                isLocalFile = false,
                referer = source.referer,
                origin = source.origin,
                audioUrl = source.audioUrl,
                subtitleUrl = if (current.subtitlesEnabled) source.subtitleUrl else null,
            )
        }
    }

    /** Clears transient status banner message. */
    fun clearStatusBanner() {
        _uiState.update { it.copy(statusBanner = null) }
    }

    private suspend fun loadOnlineEpisode(
        providerId: String,
        anilistId: Int,
        episode: String,
        audioTrack: AudioTrack,
        preservePositionMs: Long,
    ) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        val prefs = libraryRepository.getEffectivePlaybackPrefs(anilistId)
        val preferredQuality = prefs.preferredQualityOverride
            ?: preferencesRepository.defaultStreamQuality.firstOrNull()
            ?: QualitySelector.standardQualities[3]

        val result = streamingRepository.fetchEpisodeStreams(
            providerId = providerId,
            episode = episode,
            requestedAudioTrack = audioTrack,
            preferredQuality = preferredQuality,
        )
        result.onSuccess { bundle: EpisodeStreamBundle ->
            _uiState.update {
                it.copy(
                    episode = episode,
                    sources = bundle.sources,
                    selectedSource = bundle.selectedSource,
                    audioTrack = bundle.actualAudioTrack,
                    isDubFallback = bundle.isDubFallback,
                    targetSeekPositionMs = preservePositionMs,
                    isLoading = false,
                    statusBanner = if (bundle.isDubFallback) {
                        "English Dub unavailable — playing Japanese"
                    } else null,
                )
            }
            startPreBufferFor(anilistId, episode, bundle.selectedSource, bundle.actualAudioTrack)
        }.onFailure { err ->
            _uiState.update {
                it.copy(isLoading = false, errorMessage = err.message ?: "Failed to load episode $episode")
            }
        }
    }

    private fun startPreBufferFor(
        anilistId: Int,
        episode: String,
        source: StreamSource,
        audioTrack: AudioTrack,
    ) {
        val slotKey = PreBufferPolicy.slotKey(anilistId, episode, source.qualityLabel, audioTrack.name)
        preBufferManager.startPreBuffer(slotKey, source)
    }
}
