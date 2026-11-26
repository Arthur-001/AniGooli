package com.imashnake.animite.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anigooli.downloads.PreBufferManager
import com.anigooli.notifications.ReleaseScheduler
import com.anigooli.streaming.ExtractorStatus
import com.anigooli.streaming.StreamingRepository
import com.imashnake.animite.api.anilist.AnilistUserRepository
import com.imashnake.animite.api.anilist.sanitize.media.Media
import com.imashnake.animite.api.anilist.type.ScoreFormat
import com.imashnake.animite.api.preferences.AUDIO_TRACK_JAPANESE
import com.imashnake.animite.api.preferences.CARD_SIZE_COMPACT
import com.imashnake.animite.api.preferences.CENSORSHIP_UNCENSORED
import com.imashnake.animite.api.preferences.DEFAULT_PRE_BUFFER_MB
import com.imashnake.animite.api.preferences.DEFAULT_STREAM_QUALITY
import com.imashnake.animite.api.preferences.PLAYER_IN_APP
import com.imashnake.animite.api.preferences.PreferencesRepository
import com.imashnake.animite.core.model.AnimeList
import com.imashnake.animite.core.model.MangaList
import com.imashnake.animite.core.ui.Density
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
@Suppress("TooManyFunctions")
class SettingsViewModel @Inject constructor(
    private val userRepository: AnilistUserRepository,
    private val preferencesRepository: PreferencesRepository,
    private val streamingRepository: StreamingRepository,
    private val preBufferManager: PreBufferManager,
    private val releaseScheduler: ReleaseScheduler,
) : ViewModel() {

    val isLoggedIn = preferencesRepository
        .accessToken
        .map { !it.isNullOrEmpty() }

    val theme = preferencesRepository.theme.filterNotNull()
    val useSystemColorScheme = preferencesRepository.useSystemColorScheme.filterNotNull()
    val isAmoled = preferencesRepository.isAmoled.filterNotNull()
    val density = preferencesRepository.density.filterNotNull()
    val isNsfwEnabled = preferencesRepository.isNsfwEnabled.filterNotNull()
    val language = preferencesRepository.language.filterNotNull()
    val listSize = preferencesRepository.listSize.filterNotNull()
    val isDevOptionsEnabled = preferencesRepository.isDevOptionsEnabled.filterNotNull()
    val dayHour = preferencesRepository.dayHour
    val animeList = preferencesRepository.animeListsIndices.map { indices ->
        indices?.map {
            // each int from +-1 to +-5 maps to a AnimeList
            when (it.toInt()) {
                1, -1 -> AnimeList.TRENDING_NOW
                2, -2 -> AnimeList.POPULAR_THIS_SEASON
                3, -3 -> AnimeList.UPCOMING_NEXT_SEASON
                4, -4 -> AnimeList.ALL_TIME_POPULAR
                5, -5 -> AnimeList.NEWLY_ADDED
                else -> null
            }
        }.orEmpty().filterNotNull().toImmutableList()
    }
    val animeListsIndices = preferencesRepository.animeListsIndices.filterNotNull()
    val mangaList = preferencesRepository.mangaListsIndices.map { indices ->
        indices?.map {
            // each int from +-1 to +-3 maps to a MangaList
            when (it.toInt()) {
                1, -1 -> MangaList.TRENDING_NOW
                2, -2 -> MangaList.ALL_TIME_POPULAR
                3, -3 -> MangaList.NEWLY_ADDED
                else -> null
            }
        }.orEmpty().filterNotNull().toImmutableList()
    }
    val mangaListsIndices = preferencesRepository.mangaListsIndices.filterNotNull()
    val showUserDescription = preferencesRepository.showUserDescription.filterNotNull()
    val useProfileColor = preferencesRepository.useProfileColor.filterNotNull()
    val useExpressiveProgressIndicator = preferencesRepository.useExpressiveProgressIndicator.filterNotNull()
    val profileColor = preferencesRepository.profileColor.filterNotNull()
    val scoreFormat = preferencesRepository.scoreFormat.filterNotNull()

    val defaultStreamQuality = preferencesRepository.defaultStreamQuality.map { it ?: DEFAULT_STREAM_QUALITY }
    val defaultAudioTrack = preferencesRepository.defaultAudioTrack.map { it ?: AUDIO_TRACK_JAPANESE }
    val defaultSubtitlesEnabled = preferencesRepository.defaultSubtitlesEnabled.map { it ?: true }
    val preferredPlayer = preferencesRepository.preferredPlayer.map { it ?: PLAYER_IN_APP }
    val preBufferMaxMb = preferencesRepository.preBufferMaxMb.map { it ?: DEFAULT_PRE_BUFFER_MB }
    val extractorAutoUpdateEnabled = preferencesRepository.extractorAutoUpdateEnabled.map { it ?: true }
    val notificationsEnabled = preferencesRepository.notificationsEnabled.map { it ?: true }
    val hasRequestedNotificationPermission =
        preferencesRepository.hasRequestedNotificationPermission.map { it ?: false }
    val notifyEpisodes = preferencesRepository.notifyEpisodes.map { it ?: true }
    val notifySeasonsMovies = preferencesRepository.notifySeasonsMovies.map { it ?: true }
    val notifyOvas = preferencesRepository.notifyOvas.map { it ?: true }
    val notifyMangas = preferencesRepository.notifyMangas.map { it ?: true }
    /** Global card size preference across Anime, Manga, and Explore pages. */
    val mediaCardSize = preferencesRepository.mediaCardSize.map { it ?: CARD_SIZE_COMPACT }
    /** Global default anime version preference (Uncensored vs Censored). */
    val defaultCensorshipVersion =
        preferencesRepository.defaultCensorshipVersion.map { it ?: CENSORSHIP_UNCENSORED }

    private val _extractorStatus = MutableStateFlow<ExtractorStatus?>(null)
    val extractorStatus = _extractorStatus.asStateFlow()

    private val _isUpdatingExtractor = MutableStateFlow(false)
    val isUpdatingExtractor = _isUpdatingExtractor.asStateFlow()

    private val _preBufferCacheBytes = MutableStateFlow(0L)
    val preBufferCacheBytes = _preBufferCacheBytes.asStateFlow()

    init {
        refreshExtractorStatus()
        refreshCacheSize()
    }

    fun updateProfileColor(profileColor: String) = viewModelScope.launch(Dispatchers.IO) {
        userRepository.updateUser(profileColor = profileColor).collect {
            preferencesRepository.setProfileColor(it.getOrNull()?.options?.profileColor)
        }
    }

    fun updateScoreFormat(scoreFormat: ScoreFormat) = viewModelScope.launch(Dispatchers.IO) {
        userRepository.updateUser(scoreFormat = scoreFormat).collect {
            preferencesRepository.setScoreFormat(it.getOrNull()?.mediaListOptions?.scoreFormat?.name)
        }
    }

    fun setTheme(theme: Theme) = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.setTheme(theme.name)
    }
    fun setUseSystemColorScheme(useSystemColorScheme: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.setUseSystemColorScheme(useSystemColorScheme)
    }

    fun setIsAmoled(isAmoled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.setIsAmoled(isAmoled)
    }

    fun setDensity(density: Density) = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.setDensity(density.name)
    }

    fun setIsNsfwEnabled(isNsfwEnabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.setIsNsfwEnabled(isNsfwEnabled)
    }

    fun setLanguage(language: Media.Language) = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.setLanguage(language.name)
    }

    fun setListSize(listSize: Int) = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.setListSize(listSize)
    }

    fun setShowUserDescription(showUserDescription: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.setShowUserDescription(showUserDescription)
    }

    fun setUseProfileColor(useProfileColor: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.setUseProfileColor(useProfileColor)
    }

    fun setUseExpressiveProgressIndicator(useExpressiveProgressIndicator: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.setUseExpressiveProgressIndicator(useExpressiveProgressIndicator)
    }

    fun setDevOptions(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.setDevOptionsEnabled(enabled)
    }

    fun setDayHour(dayHour: Float?) = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.setDayHour(dayHour?.coerceIn(0f, 23f))
    }

    fun setAnimeListsIndices(from: Int, to: Int) = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.setAnimeListsIndices(from, to)
    }

    fun toggleAnimeList(index: Int) = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.toggleAnimeList(index)
    }

    fun resetAnimeLists() = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.resetAnimeLists()
    }

    fun setMangaListsIndices(from: Int, to: Int) = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.setMangaListsIndices(from, to)
    }

    fun toggleMangaList(index: Int) = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.toggleMangaList(index)
    }

    fun resetMangaLists() = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.resetMangaLists()
    }

    fun setDefaultStreamQuality(quality: String) = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.setDefaultStreamQuality(quality)
    }

    fun setDefaultAudioTrack(track: String) = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.setDefaultAudioTrack(track)
    }

    fun setDefaultSubtitlesEnabled(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.setDefaultSubtitlesEnabled(enabled)
    }

    fun setPreferredPlayer(player: String) = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.setPreferredPlayer(player)
    }

    fun setPreBufferMaxMb(mb: Int) = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.setPreBufferMaxMb(mb)
    }

    fun clearPreBufferCache() = viewModelScope.launch(Dispatchers.IO) {
        preBufferManager.clearCache()
        _preBufferCacheBytes.value = 0L
    }

    fun refreshCacheSize() = viewModelScope.launch(Dispatchers.IO) {
        _preBufferCacheBytes.value = preBufferManager.getCacheSizeBytes()
    }

    fun setExtractorAutoUpdateEnabled(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.setExtractorAutoUpdateEnabled(enabled)
    }

    fun refreshExtractorStatus() = viewModelScope.launch(Dispatchers.IO) {
        _extractorStatus.value = streamingRepository.getExtractorStatus()
    }

    fun updateExtractorNow() = viewModelScope.launch(Dispatchers.IO) {
        _isUpdatingExtractor.value = true
        runCatching {
            _extractorStatus.value = streamingRepository.refreshExtractor(force = true)
        }
        _isUpdatingExtractor.value = false
    }

    fun setNotificationsEnabled(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.setNotificationsEnabled(enabled)
        releaseScheduler.syncSchedule()
    }

    fun markNotificationPermissionRequested() = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.setHasRequestedNotificationPermission(true)
    }

    fun setNotifyEpisodes(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.setNotifyEpisodes(enabled)
    }

    fun setNotifySeasonsMovies(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.setNotifySeasonsMovies(enabled)
    }

    fun setNotifyOvas(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.setNotifyOvas(enabled)
    }

    fun setNotifyMangas(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.setNotifyMangas(enabled)
    }

    /** Updates the global media card size preference across Anime, Manga, and Explore. */
    fun setMediaCardSize(size: String) = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.setMediaCardSize(size)
    }

    /** Updates the global default anime censorship version preference. */
    fun setDefaultCensorshipVersion(version: String) = viewModelScope.launch(Dispatchers.IO) {
        preferencesRepository.setDefaultCensorshipVersion(version)
    }
}
