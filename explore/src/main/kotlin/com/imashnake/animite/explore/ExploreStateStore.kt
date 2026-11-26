package com.imashnake.animite.explore

import com.imashnake.animite.api.anilist.sanitize.media.Media
import com.imashnake.animite.api.anilist.type.MediaType
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-scoped state store preserving Explore search query, sort order, and active filters
 * across tab switches and back navigation until explicitly cleared or reset by the user.
 */
@Singleton
class ExploreStateStore @Inject constructor() {
    /** Last active search query in Explore, cleared only when the user taps X or resets. */
    val searchQuery = MutableStateFlow<String?>(null)

    /** Currently selected sort criterion. */
    val selectedSort = MutableStateFlow(Media.Sort.POPULARITY)

    /** Whether sort direction is descending. */
    val isDescending = MutableStateFlow(true)

    /** Currently selected media type name (ANIME or MANGA). */
    val mediaType = MutableStateFlow(MediaType.ANIME.name)

    /** All available genres fetched from AniList. */
    val baseAllGenres = MutableStateFlow<Set<String>?>(null)

    /** Unselected genres shown in the "All" bucket. */
    val allGenres = MutableStateFlow<Set<String>?>(null)

    /** Genres included in the filter. */
    val includedGenres = MutableStateFlow<Set<String>>(emptySet())

    /** Genres excluded from the filter. */
    val excludedGenres = MutableStateFlow<Set<String>>(emptySet())

    /** All available formats for the active media type. */
    val baseAllFormats = MutableStateFlow<Set<String>?>(null)

    /** Unselected formats shown in the "All" bucket. */
    val allFormats = MutableStateFlow<Set<String>?>(null)

    /** Formats included in the filter. */
    val includedFormats = MutableStateFlow<Set<String>>(emptySet())

    /** Formats excluded from the filter. */
    val excludedFormats = MutableStateFlow<Set<String>>(emptySet())

    /** All available statuses. */
    val baseAllStatuses = MutableStateFlow<Set<String>?>(null)

    /** Unselected statuses shown in the "All" bucket. */
    val allStatuses = MutableStateFlow<Set<String>?>(null)

    /** Statuses included in the filter. */
    val includedStatuses = MutableStateFlow<Set<String>>(emptySet())

    /** Statuses excluded from the filter. */
    val excludedStatuses = MutableStateFlow<Set<String>>(emptySet())

    /** Selected release season name, or null. */
    val selectedSeason = MutableStateFlow<String?>(null)

    /** Selected release year, or null. */
    val selectedYear = MutableStateFlow<Int?>(null)

    /** Whether adult (18+) filter is toggled on. */
    val isAdult = MutableStateFlow(false)

    /** Whether Explore is displaying the "Latest Episodes" airing feed. */
    val isLatestEpisodes = MutableStateFlow(false)

    /** Active pagination page number. */
    val page = MutableStateFlow(1)

    /** Last applied explicit route signature so config changes do not overwrite user edits. */
    var lastAppliedRouteSignature: String? = null
}
