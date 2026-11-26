package com.imashnake.animite.api.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.imashnake.animite.api.preferences.ext.getValue
import com.imashnake.animite.api.preferences.ext.setValue
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.Json

private const val DEFAULT_THEME_KEY = "DEVICE_THEME"
private const val DEFAULT_DENSITY_KEY = "COMFY"
private const val USE_SYSTEM_COLOR_SCHEME = true
private const val IS_AMOLED = false
private const val IS_NSFW_ENABLED = false
private const val DEFAULT_LANGUAGE_KEY = "ENGLISH"
private const val SHOW_USER_DESCRIPTION = true
private const val USE_PROFILE_COLOR = true
private const val USE_EXPRESSIVE_PROGRESS_INDICATOR = true
private const val PROFILE_COLOR = "blue"
private const val SCORE_FORMAT = "POINT_10_DECIMAL"
private const val IS_DEV_OPTIONS_ENABLED = false
const val DEFAULT_MEDIA_LIST_ORDER = "[]"
const val DEFAULT_PERSONAL_LIST_COLUMNS = 3
const val MANGA_SORT_OLDEST_FIRST = "OLDEST_FIRST"
const val MANGA_SORT_NEWEST_FIRST = "NEWEST_FIRST"
const val DEFAULT_STREAM_QUALITY = "1080p"
const val AUDIO_TRACK_JAPANESE = "JAPANESE"
const val AUDIO_TRACK_ENGLISH_DUB = "ENGLISH_DUB"
const val PLAYER_IN_APP = "IN_APP"
const val PLAYER_MPV = "MPV"
const val DEFAULT_PRE_BUFFER_MB = 1024
/** Compact card size preset. */
const val CARD_SIZE_COMPACT = "COMPACT"
/** Medium card size preset. */
const val CARD_SIZE_MEDIUM = "MEDIUM"
/** Large card size preset. */
const val CARD_SIZE_LARGE = "LARGE"
/** Default uncensored anime version preference. */
const val CENSORSHIP_UNCENSORED = "UNCENSORED"
/** Censored anime version preference. */
const val CENSORSHIP_CENSORED = "CENSORED"

/**
 * Stores API-specific state in an on-disk cache, and exposes the values as a Flow.
 */
class PreferencesRepository internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    // region anime
    private val animeListsIndicesKey = byteArrayPreferencesKey("anime_lists_indices")
    val animeListsIndices = dataStore.getValue(animeListsIndicesKey, byteArrayOf(1, 2, 3, 4, 5))
    suspend fun setAnimeListsIndices(from: Int, to: Int) {
        dataStore.setValue(
            animeListsIndicesKey,
            animeListsIndices.firstOrNull()?.toMutableList()?.apply {
                add(to, removeAt(from))
            }?.toByteArray()
        )
    }
    suspend fun toggleAnimeList(index: Int) {
        dataStore.setValue(
            animeListsIndicesKey,
            animeListsIndices.firstOrNull()?.toMutableList()?.apply {
                set(index, (get(index) * -1).toByte())
            }?.toByteArray()
        )
    }
    suspend fun resetAnimeLists() {
        dataStore.setValue(animeListsIndicesKey, byteArrayOf(1, 2, 3, 4, 5))
    }
    // endregion

    // region manga
    private val mangaListsIndicesKey = byteArrayPreferencesKey("manga_lists_indices")
    val mangaListsIndices = dataStore.getValue(mangaListsIndicesKey, byteArrayOf(1, 2, 3))
    suspend fun setMangaListsIndices(from: Int, to: Int) {
        dataStore.setValue(
            mangaListsIndicesKey,
            mangaListsIndices.firstOrNull()?.toMutableList()?.apply {
                add(to, removeAt(from))
            }?.toByteArray()
        )
    }
    suspend fun toggleMangaList(index: Int) {
        dataStore.setValue(
            mangaListsIndicesKey,
            mangaListsIndices.firstOrNull()?.toMutableList()?.apply {
                set(index, (get(index) * -1).toByte())
            }?.toByteArray()
        )
    }
    suspend fun resetMangaLists() {
        dataStore.setValue(mangaListsIndicesKey, byteArrayOf(1, 2, 3))
    }
    // endregion

    // region profile
    private val accessTokenKey = stringPreferencesKey("access_token")
    val accessToken = dataStore.getValue(accessTokenKey, null)
    suspend fun setAccessToken(accessToken: String?) {
        dataStore.setValue(accessTokenKey, accessToken)
    }

    private val viewerIdKey = stringPreferencesKey("viewer_id")
    val viewerId = dataStore.getValue(viewerIdKey, null)
    suspend fun setViewerId(viewerId: Int?) {
        dataStore.setValue(viewerIdKey, viewerId.toString())
    }

    private val animeListOrderKey = stringPreferencesKey("anime_list_order")
    val animeListOrder = dataStore.getValue(animeListOrderKey, DEFAULT_MEDIA_LIST_ORDER)
    suspend fun setAnimeListOrder(animeListOrder: List<String>?) {
        dataStore.setValue(animeListOrderKey, Json.encodeToString(animeListOrder))
    }

    private val mangaListOrderKey = stringPreferencesKey("manga_list_order")
    val mangaListOrder = dataStore.getValue(mangaListOrderKey, DEFAULT_MEDIA_LIST_ORDER)
    suspend fun setMangaListOrder(mangaListOrder: List<String>?) {
        dataStore.setValue(mangaListOrderKey, Json.encodeToString(mangaListOrder))
    }

    private val viewerAvatarKey = stringPreferencesKey("viewer_avatar")
    val viewerAvatar = dataStore.getValue(viewerAvatarKey, null)
    suspend fun setViewerAvatar(avatarUrl: String?) {
        dataStore.setValue(viewerAvatarKey, avatarUrl)
    }
    // endregion

    // region settings

    // TODO: Expose and set enum value.
    private val themeKey = stringPreferencesKey("theme")
    val theme = dataStore.getValue(themeKey, DEFAULT_THEME_KEY)
    suspend fun setTheme(theme: String) {
        dataStore.setValue(themeKey, theme)
    }

    private val useSystemColorSchemeKey = booleanPreferencesKey("system_color_scheme")
    val useSystemColorScheme = dataStore.getValue(useSystemColorSchemeKey, USE_SYSTEM_COLOR_SCHEME)
    suspend fun setUseSystemColorScheme(useSystemColorScheme: Boolean) {
        dataStore.setValue(useSystemColorSchemeKey, useSystemColorScheme)
    }

    private val isAmoledKey = booleanPreferencesKey("is_amoled")
    val isAmoled = dataStore.getValue(isAmoledKey, IS_AMOLED)
    suspend fun setIsAmoled(isAmoled: Boolean) {
        dataStore.setValue(isAmoledKey, isAmoled)
    }

    // TODO: Expose and set enum value.
    private val densityKey = stringPreferencesKey("density")
    val density = dataStore.getValue(densityKey, DEFAULT_DENSITY_KEY)
    suspend fun setDensity(density: String) {
        dataStore.setValue(densityKey, density)
    }

    private val isNsfwEnabledKey = booleanPreferencesKey("is_nsfw_enabled")
    val isNsfwEnabled = dataStore.getValue(isNsfwEnabledKey, IS_NSFW_ENABLED)
    suspend fun setIsNsfwEnabled(isNsfwEnabled: Boolean) {
        dataStore.setValue(isNsfwEnabledKey, isNsfwEnabled)
    }

    // TODO: Expose and set enum value.
    private val languageKey = stringPreferencesKey("language")
    val language = dataStore.getValue(languageKey, DEFAULT_LANGUAGE_KEY)
    suspend fun setLanguage(language: String) {
        dataStore.setValue(languageKey, language)
    }

    private val listSizeKey = intPreferencesKey("list_size")
    val listSize = dataStore.getValue(listSizeKey, 10)
    suspend fun setListSize(listSize: Int) {
        dataStore.setValue(listSizeKey, listSize)
    }

    private val showUserDescriptionKey = booleanPreferencesKey("show_user_description")
    val showUserDescription = dataStore.getValue(showUserDescriptionKey, SHOW_USER_DESCRIPTION)
    suspend fun setShowUserDescription(showUserDescription: Boolean) {
        dataStore.setValue(showUserDescriptionKey, showUserDescription)
    }

    private val useProfileColorKey = booleanPreferencesKey("use_profile_color")
    val useProfileColor = dataStore.getValue(useProfileColorKey, USE_PROFILE_COLOR)
    suspend fun setUseProfileColor(useProfileColor: Boolean) {
        dataStore.setValue(useProfileColorKey, useProfileColor)
    }

    private val useExpressiveProgressIndicatorKey = booleanPreferencesKey("use_expressive_progress_indicator")
    val useExpressiveProgressIndicator = dataStore.getValue(useExpressiveProgressIndicatorKey, USE_EXPRESSIVE_PROGRESS_INDICATOR)
    suspend fun setUseExpressiveProgressIndicator(useExpressiveProgressIndicator: Boolean) {
        dataStore.setValue(useExpressiveProgressIndicatorKey, useExpressiveProgressIndicator)
    }

    private val profileColorKey = stringPreferencesKey("profile_color")
    val profileColor = dataStore.getValue(profileColorKey, PROFILE_COLOR)
    suspend fun setProfileColor(profileColor: String?) {
        dataStore.setValue(profileColorKey, profileColor)
    }

    // TODO: Expose and set enum value.
    private val scoreFormatKey = stringPreferencesKey("score_format")
    val scoreFormat = dataStore.getValue(scoreFormatKey, SCORE_FORMAT)
    suspend fun setScoreFormat(scoreFormat: String?) {
        dataStore.setValue(scoreFormatKey, scoreFormat)
    }

    // region developer options
    private val isDevOptionsEnabledKey = booleanPreferencesKey("dev_options_enabled")
    val isDevOptionsEnabled = dataStore.getValue(isDevOptionsEnabledKey, IS_DEV_OPTIONS_ENABLED)
    suspend fun setDevOptionsEnabled(isEnabled: Boolean) {
        if (!isEnabled) {
            dataStore.setValue(dayHourKey, null)
        }
        dataStore.setValue(isDevOptionsEnabledKey, isEnabled)
    }

    private val dayHourKey = floatPreferencesKey("day_hour")
    val dayHour = dataStore.getValue(dayHourKey, null)
    suspend fun setDayHour(dayHour: Float?) {
        dataStore.setValue(dayHourKey, dayHour)
    }
    // endregion

    // region anigooli
    private val personalListColumnsKey = intPreferencesKey("personal_list_columns")
    val personalListColumns = dataStore.getValue(personalListColumnsKey, DEFAULT_PERSONAL_LIST_COLUMNS)
    suspend fun setPersonalListColumns(columns: Int) {
        dataStore.setValue(personalListColumnsKey, columns.coerceIn(2, 4))
    }

    private val mangaRelationsSortOrderKey = stringPreferencesKey("manga_relations_sort_order")
    val mangaRelationsSortOrder = dataStore.getValue(mangaRelationsSortOrderKey, MANGA_SORT_OLDEST_FIRST)
    suspend fun setMangaRelationsSortOrder(order: String) {
        dataStore.setValue(mangaRelationsSortOrderKey, order)
    }

    private val defaultStreamQualityKey = stringPreferencesKey("default_stream_quality")
    val defaultStreamQuality = dataStore.getValue(defaultStreamQualityKey, DEFAULT_STREAM_QUALITY)
    suspend fun setDefaultStreamQuality(quality: String) {
        dataStore.setValue(defaultStreamQualityKey, quality)
    }

    private val defaultAudioTrackKey = stringPreferencesKey("default_audio_track")
    val defaultAudioTrack = dataStore.getValue(defaultAudioTrackKey, AUDIO_TRACK_JAPANESE)
    suspend fun setDefaultAudioTrack(track: String) {
        dataStore.setValue(defaultAudioTrackKey, track)
    }

    private val defaultSubtitlesEnabledKey = booleanPreferencesKey("default_subtitles_enabled")
    val defaultSubtitlesEnabled = dataStore.getValue(defaultSubtitlesEnabledKey, true)
    suspend fun setDefaultSubtitlesEnabled(enabled: Boolean) {
        dataStore.setValue(defaultSubtitlesEnabledKey, enabled)
    }

    private val preferredPlayerKey = stringPreferencesKey("preferred_player")
    val preferredPlayer = dataStore.getValue(preferredPlayerKey, PLAYER_IN_APP)
    suspend fun setPreferredPlayer(player: String) {
        dataStore.setValue(preferredPlayerKey, player)
    }

    private val preBufferMaxMbKey = intPreferencesKey("pre_buffer_max_mb")
    val preBufferMaxMb = dataStore.getValue(preBufferMaxMbKey, DEFAULT_PRE_BUFFER_MB)
    suspend fun setPreBufferMaxMb(maxMb: Int) {
        dataStore.setValue(preBufferMaxMbKey, maxMb.coerceIn(500, 4096))
    }

    private val notificationsEnabledKey = booleanPreferencesKey("notifications_enabled")
    val notificationsEnabled = dataStore.getValue(notificationsEnabledKey, true)
    suspend fun setNotificationsEnabled(enabled: Boolean) {
        dataStore.setValue(notificationsEnabledKey, enabled)
    }

    private val hasRequestedNotificationPermissionKey = booleanPreferencesKey("has_requested_notification_permission")
    val hasRequestedNotificationPermission = dataStore.getValue(hasRequestedNotificationPermissionKey, false)
    suspend fun setHasRequestedNotificationPermission(requested: Boolean) {
        dataStore.setValue(hasRequestedNotificationPermissionKey, requested)
    }

    private val notifyEpisodesKey = booleanPreferencesKey("notify_episodes")
    val notifyEpisodes = dataStore.getValue(notifyEpisodesKey, true)
    suspend fun setNotifyEpisodes(enabled: Boolean) {
        dataStore.setValue(notifyEpisodesKey, enabled)
    }

    private val notifySeasonsMoviesKey = booleanPreferencesKey("notify_seasons_movies")
    val notifySeasonsMovies = dataStore.getValue(notifySeasonsMoviesKey, true)
    suspend fun setNotifySeasonsMovies(enabled: Boolean) {
        dataStore.setValue(notifySeasonsMoviesKey, enabled)
    }

    private val notifyOvasKey = booleanPreferencesKey("notify_ovas")
    val notifyOvas = dataStore.getValue(notifyOvasKey, true)
    suspend fun setNotifyOvas(enabled: Boolean) {
        dataStore.setValue(notifyOvasKey, enabled)
    }

    private val notifyMangasKey = booleanPreferencesKey("notify_mangas")
    val notifyMangas = dataStore.getValue(notifyMangasKey, true)
    suspend fun setNotifyMangas(enabled: Boolean) {
        dataStore.setValue(notifyMangasKey, enabled)
    }

    private val extractorAutoUpdateEnabledKey = booleanPreferencesKey("extractor_auto_update_enabled")
    val extractorAutoUpdateEnabled = dataStore.getValue(extractorAutoUpdateEnabledKey, true)
    suspend fun setExtractorAutoUpdateEnabled(enabled: Boolean) {
        dataStore.setValue(extractorAutoUpdateEnabledKey, enabled)
    }

    private val extractorCachedVersionKey = stringPreferencesKey("extractor_cached_version")
    val extractorCachedVersion = dataStore.getValue(extractorCachedVersionKey, null)
    suspend fun setExtractorCachedVersion(version: String?) {
        dataStore.setValue(extractorCachedVersionKey, version)
    }

    private val upstreamAniCliVersionKey = stringPreferencesKey("upstream_ani_cli_version")
    val upstreamAniCliVersion = dataStore.getValue(upstreamAniCliVersionKey, null)
    suspend fun setUpstreamAniCliVersion(version: String?) {
        dataStore.setValue(upstreamAniCliVersionKey, version)
    }

    private val extractorLastCheckedMsKey = longPreferencesKey("extractor_last_checked_ms")
    val extractorLastCheckedMs = dataStore.getValue(extractorLastCheckedMsKey, 0L)
    suspend fun setExtractorLastCheckedMs(timestampMs: Long) {
        dataStore.setValue(extractorLastCheckedMsKey, timestampMs)
    }

    private val mediaCardSizeKey = stringPreferencesKey("media_card_size")
    /** Global card size preference across Anime, Manga, and Explore pages. */
    val mediaCardSize = dataStore.getValue(mediaCardSizeKey, CARD_SIZE_COMPACT)
    /** Updates global card size and resets per-page overrides so all pages reflect the setting. */
    suspend fun setMediaCardSize(size: String) {
        dataStore.setValue(mediaCardSizeKey, size)
        dataStore.setValue(animeCardSizeKey, null)
        dataStore.setValue(mangaCardSizeKey, null)
        dataStore.setValue(exploreCardSizeKey, null)
    }

    private val animeCardSizeKey = stringPreferencesKey("anime_card_size")
    /** Optional per-page card size override for the Anime tab. */
    val animeCardSize = dataStore.getValue(animeCardSizeKey, null)
    /** Persists per-page card size for the Anime tab. */
    suspend fun setAnimeCardSize(size: String?) {
        dataStore.setValue(animeCardSizeKey, size)
    }

    private val mangaCardSizeKey = stringPreferencesKey("manga_card_size")
    /** Optional per-page card size override for the Manga tab. */
    val mangaCardSize = dataStore.getValue(mangaCardSizeKey, null)
    /** Persists per-page card size for the Manga tab. */
    suspend fun setMangaCardSize(size: String?) {
        dataStore.setValue(mangaCardSizeKey, size)
    }

    private val exploreCardSizeKey = stringPreferencesKey("explore_card_size")
    /** Optional per-page card size override for the Explore tab. */
    val exploreCardSize = dataStore.getValue(exploreCardSizeKey, null)
    /** Persists per-page card size for the Explore tab. */
    suspend fun setExploreCardSize(size: String?) {
        dataStore.setValue(exploreCardSizeKey, size)
    }

    private val showEpisodeNamesKey = booleanPreferencesKey("show_episode_names")
    /** Whether episode lists display episode names in addition to numbers. */
    val showEpisodeNames = dataStore.getValue(showEpisodeNamesKey, false)
    /** Persists whether episode names should be shown in the episode selector. */
    suspend fun setShowEpisodeNames(show: Boolean) {
        dataStore.setValue(showEpisodeNamesKey, show)
    }

    private val defaultCensorshipVersionKey = stringPreferencesKey("default_censorship_version")
    /** Global default anime version preference (Uncensored vs Censored). */
    val defaultCensorshipVersion = dataStore.getValue(defaultCensorshipVersionKey, CENSORSHIP_UNCENSORED)
    /** Persists global default anime version preference. */
    suspend fun setDefaultCensorshipVersion(version: String) {
        dataStore.setValue(defaultCensorshipVersionKey, version)
    }
    // endregion

    // endregion

    companion object
}
