package com.imashnake.animite.explore

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.imashnake.animite.api.anilist.AnilistMediaRepository
import com.imashnake.animite.api.anilist.sanitize.explore.FilterStrategy
import com.imashnake.animite.api.anilist.sanitize.media.Media
import com.imashnake.animite.api.anilist.sanitize.media.Page
import com.imashnake.animite.api.anilist.sanitize.settings.Prefs
import com.imashnake.animite.api.anilist.type.MediaFormat
import com.imashnake.animite.api.anilist.type.MediaSeason
import com.imashnake.animite.api.anilist.type.MediaStatus
import com.imashnake.animite.api.anilist.type.MediaType
import com.imashnake.animite.api.preferences.PreferencesRepository
import com.imashnake.animite.core.resource.Resource
import com.imashnake.animite.core.resource.Resource.Companion.asResource
import com.imashnake.animite.core.ui.Constants
import com.imashnake.animite.core.ui.component.MediaCardSize
import com.imashnake.animite.explore.searchbar.ProviderSearchHelper
import com.imashnake.animite.explore.searchbar.SearchFallbackMeta
import com.imashnake.animite.navigation.ExploreRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ExploreViewModel @Inject constructor(
    private val mediaListRepository: AnilistMediaRepository,
    private val providerSearchHelper: ProviderSearchHelper,
    savedStateHandle: SavedStateHandle,
    private val preferencesRepository: PreferencesRepository,
    private val stateStore: ExploreStateStore,
) : ViewModel() {
    /** Effective card size for the Explore tab (per-page override falling back to global preference). */
    val cardSize = combine(
        preferencesRepository.exploreCardSize,
        preferencesRepository.mediaCardSize,
    ) { pageOverride, globalDefault ->
        MediaCardSize.safeValueOf(pageOverride ?: globalDefault)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(1000), MediaCardSize.COMPACT)

    /** Updates and saves the card size preference for the Explore tab. */
    fun setCardSize(size: MediaCardSize) = viewModelScope.launch {
        preferencesRepository.setExploreCardSize(size.name)
    }

    /** Saved results-per-page count for Explore (25, 50, 75, or 100). */
    val explorePerPage: StateFlow<Int> = preferencesRepository.explorePerPage
        .filterNotNull()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(1000),
            initialValue = PreferencesRepository.DEFAULT_EXPLORE_PER_PAGE,
        )

    /** Persists the user's chosen results-per-page count (25, 50, 75, or 100). */
    fun setExplorePerPage(perPage: Int) = viewModelScope.launch {
        stateStore.page.value = 1
        preferencesRepository.setExplorePerPage(perPage)
    }

    private val navArgs = savedStateHandle.toRoute<ExploreRoute>()

    private val refreshTrigger = MutableSharedFlow<Unit>()

    var useNetwork = false

    val selectedSort = stateStore.selectedSort
    val isDescending = stateStore.isDescending
    val isLatestEpisodes = stateStore.isLatestEpisodes

    val searchQuery = stateStore.searchQuery

    val mediaType = stateStore.mediaType

    private val _allGenres = stateStore.baseAllGenres
    private val allGenres = stateStore.allGenres
    private val includedGenres = stateStore.includedGenres
    private val excludedGenres = stateStore.excludedGenres
    private val genreSegregation = combine(
        flow = includedGenres,
        flow2 = excludedGenres,
        transform = ::Pair
    ).map { ChipFilterSegregation(it.first, it.second) }

    val chipGenreGroup = combine(allGenres, includedGenres, excludedGenres) { (all, included, excluded) ->
        ChipFilterGroup(
            type = ChipFilterType.GENRE,
            allFilters = all.orEmpty().sorted().toImmutableList(),
            includedFilters = included.orEmpty().sorted().toImmutableList(),
            excludedFilters = excluded.orEmpty().sorted().toImmutableList()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ChipFilterGroup(type = ChipFilterType.GENRE, persistentListOf(), persistentListOf(), persistentListOf())
    )

    val selectedSeason = stateStore.selectedSeason
    val selectedYear = stateStore.selectedYear
    val seasonYear = selectedSeason.combine(selectedYear) { season, year ->
        SeasonYear(season, year)
    }

    private val _allFormats = stateStore.baseAllFormats
    private val allFormats = stateStore.allFormats
    private val includedFormats = stateStore.includedFormats
    private val excludedFormats = stateStore.excludedFormats
    private val formatSegregation = combine(
        flow = includedFormats,
        flow2 = excludedFormats,
        transform = ::Pair
    ).map { ChipFilterSegregation(it.first, it.second) }

    val chipFormatGroup = combine(allFormats, includedFormats, excludedFormats) { (all, included, excluded) ->
        ChipFilterGroup(
            type = ChipFilterType.FORMAT,
            allFilters = all.orEmpty().sorted().toImmutableList(),
            includedFilters = included.orEmpty().sorted().toImmutableList(),
            excludedFilters = excluded.orEmpty().sorted().toImmutableList()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ChipFilterGroup(type = ChipFilterType.GENRE, persistentListOf(), persistentListOf(), persistentListOf())
    )

    private val _allStatuses = stateStore.baseAllStatuses
    private val allStatuses = stateStore.allStatuses
    private val includedStatuses = stateStore.includedStatuses
    private val excludedStatuses = stateStore.excludedStatuses
    private val statusSegregation = combine(
        flow = includedStatuses,
        flow2 = excludedStatuses,
        transform = ::Pair
    ).map { ChipFilterSegregation(it.first, it.second) }

    private val page = stateStore.page

    val chipStatusGroup = combine(allStatuses, includedStatuses, excludedStatuses) { (all, included, excluded) ->
        ChipFilterGroup(
            type = ChipFilterType.STATUS,
            allFilters = all.orEmpty().sorted().toImmutableList(),
            includedFilters = included.orEmpty().sorted().toImmutableList(),
            excludedFilters = excluded.orEmpty().sorted().toImmutableList()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ChipFilterGroup(type = ChipFilterType.GENRE, persistentListOf(), persistentListOf(), persistentListOf())
    )

    val mediaSort = selectedSort
        .combine(isDescending, ::Pair)
        .map { (sort, isDescending) -> Media.Sort.pollute(sort, isDescending) }

    val isAdult = stateStore.isAdult

    init {
        val hasExplicitRouteArgs = navArgs.sortName != null ||
            navArgs.season != null ||
            navArgs.year != null ||
            navArgs.genre != null ||
            navArgs.isLatestEpisodes
        val routeSignature = listOf(
            navArgs.mediaType,
            navArgs.sortName,
            navArgs.isDescending,
            navArgs.season,
            navArgs.year,
            navArgs.genre,
            navArgs.isLatestEpisodes,
        ).joinToString("|")

        if (hasExplicitRouteArgs && routeSignature != stateStore.lastAppliedRouteSignature) {
            stateStore.lastAppliedRouteSignature = routeSignature
            stateStore.searchQuery.value = null
            stateStore.mediaType.value = navArgs.mediaType ?: MediaType.ANIME.name
            stateStore.selectedSort.value = navArgs.sortName?.let { Media.Sort.valueOf(it) } ?: Media.Sort.POPULARITY
            stateStore.isDescending.value = navArgs.isDescending ?: true
            stateStore.selectedSeason.value = navArgs.season
            stateStore.selectedYear.value = navArgs.year
            stateStore.isLatestEpisodes.value = navArgs.isLatestEpisodes
            stateStore.includedGenres.value = setOfNotNull(navArgs.genre)
            stateStore.excludedGenres.value = emptySet()
            stateStore.includedFormats.value = emptySet()
            stateStore.excludedFormats.value = emptySet()
            stateStore.includedStatuses.value = emptySet()
            stateStore.excludedStatuses.value = emptySet()
            stateStore.isAdult.value = navArgs.genre == "Hentai"
            stateStore.page.value = 1
        }

        viewModelScope.launch(Dispatchers.IO) {
            if (_allGenres.value == null) {
                setAllFilters(
                    filterType = ChipFilterType.GENRE,
                    allFilters = mediaListRepository.fetchMediaGenres(isAdult = true).toSet(),
                )
            }
            if (_allFormats.value == null) {
                val activeType = MediaType.valueOf(stateStore.mediaType.value)
                val formats = if (activeType == MediaType.ANIME) {
                    Media.Format.animeFormats()
                } else {
                    Media.Format.mangaFormats()
                }.map { it.name }.toSet()
                setAllFilters(filterType = ChipFilterType.FORMAT, allFilters = formats)
            }
            if (_allStatuses.value == null) {
                setAllFilters(
                    filterType = ChipFilterType.STATUS,
                    allFilters = Media.Status.entries.map { it.name }.toSet(),
                )
            }
            navArgs.genre?.let { genre ->
                if (hasExplicitRouteArgs && genre !in includedGenres.value) {
                    includeFilter(filterType = ChipFilterType.GENRE, filter = genre)
                }
            }
        }
    }

    val filterSheetOptions = combine(
        flow = genreSegregation,
        flow2 = seasonYear,
        flow3 = formatSegregation,
        flow4 = statusSegregation,
        flow5 = isAdult,
        flow6 = mediaType.onEach {
            val mediaType = MediaType.valueOf(it)
            if (mediaType == MediaType.MANGA) {
                setMediaSeason(null)
            }
            setAllFilters(
                filterType = ChipFilterType.FORMAT,
                allFilters = when (MediaType.valueOf(it)) {
                    MediaType.ANIME -> Media.Format.animeFormats()
                    else -> Media.Format.mangaFormats()
                }.map { format -> format.name }.toSet()
            )
        },
        transform = { genres, seasonYear, format, status, isAdult, type ->
            FilterSheetOptions(
                genres = genres,
                seasonYear = seasonYear,
                format = format,
                status = status,
                isAdult = isAdult,
                mediaType = type,
            )
        }
    )

    val isNsfwEnabled = preferencesRepository.isNsfwEnabled.filterNotNull()

    val prefs = combine(
        flow = preferencesRepository.isNsfwEnabled.filterNotNull(),
        flow2 = preferencesRepository.language.filterNotNull(),
        flow3 = preferencesRepository.explorePerPage.filterNotNull(),
        transform = { isNsfwEnabled, language, explorePerPage ->
            Prefs(isNsfwEnabled, Media.Language.safeValueOf(language), explorePerPage)
        }
    )

    val filterStrategy = combine(
        flow = combine(mediaSort, isLatestEpisodes, ::Pair),
        flow2 = searchQuery,
        flow3 = filterSheetOptions,
        flow4 = page,
        flow5 = prefs,
        transform = { (sort, latestEpisodes), searchQuery, filterSheetOptions, page, prefs ->
            val hasHentaiGenre = "Hentai" in filterSheetOptions.genres.included
            FilterStrategy(
                mediaType = MediaType.valueOf(filterSheetOptions.mediaType),
                sort = listOf(sort),
                page = page,
                search = searchQuery,
                includedGenres = filterSheetOptions.genres.included.toList().ifEmpty { null },
                excludedGenres = filterSheetOptions.genres.excluded.toList().ifEmpty { null },
                season = filterSheetOptions.seasonYear.season?.let { MediaSeason.safeValueOf(it) },
                year = filterSheetOptions.seasonYear.year,
                includedFormats = filterSheetOptions.format.included.map { MediaFormat.valueOf(it) }.ifEmpty { null },
                excludedFormats = filterSheetOptions.format.excluded.map { MediaFormat.valueOf(it) }.ifEmpty { null },
                includedStatuses = filterSheetOptions.status.included.map { MediaStatus.valueOf(it) }.ifEmpty { null },
                excludedStatuses = filterSheetOptions.status.excluded.map { MediaStatus.valueOf(it) }.ifEmpty { null },
                isAdult = filterSheetOptions.isAdult || hasHentaiGenre,
                isNsfwEnabled = prefs.isNsfwEnabled || !searchQuery.isNullOrBlank() || hasHentaiGenre,
                language = prefs.language,
                perPage = prefs.listSize,
                isLatestEpisodes = latestEpisodes,
            )
        }
    )

    var shouldDebounce = false

    @OptIn(FlowPreview::class)
    val explorePage = combine(
        flow = filterStrategy,
        flow2 = refreshTrigger.onStart { emit(Unit) },
        transform = ::Pair,
    ).debounce {
        if (shouldDebounce) {
            500L
        } else {
            shouldDebounce = true
            0L
        }
    }.flatMapLatest { (filterStrategy, _) ->
        flow {
            if (filterStrategy.isLatestEpisodes) {
                emit(Resource.loading())
                val latest = mediaListRepository
                    .fetchLatestEpisodesMediumList(useNetwork, filterStrategy)
                    .asResource()
                    .firstOrNull()
                emit(latest)
                return@flow
            }

            val query = filterStrategy.search?.trim()
            val hasExtraFilters = filterStrategy.includedGenres != null ||
                filterStrategy.excludedGenres != null ||
                filterStrategy.season != null ||
                filterStrategy.year != null ||
                filterStrategy.includedFormats != null ||
                filterStrategy.excludedFormats != null ||
                filterStrategy.includedStatuses != null ||
                filterStrategy.excludedStatuses != null

            if (!query.isNullOrEmpty() &&
                filterStrategy.mediaType == MediaType.ANIME &&
                filterStrategy.page == 1
            ) {
                if (!useNetwork && !hasExtraFilters) {
                    val cached = providerSearchHelper.getCachedResults(query)
                    if (cached != null) {
                        emit(Resource.success(Page(list = cached, info = null)))
                        return@flow
                    }
                }
                emit(Resource.loading())
                val (anilistResource, extraResults) = coroutineScope {
                    val anilistDeferred = async {
                        mediaListRepository
                            .fetchMediaMediumList(useNetwork, filterStrategy)
                            .asResource()
                            .firstOrNull()
                    }
                    val providerDeferred = async {
                        providerSearchHelper.fetchProviderAndJikan(query)
                    }
                    anilistDeferred.await() to providerDeferred.await()
                }
                val baseList = anilistResource?.data?.list.orEmpty()
                val merged = providerSearchHelper.mergeAndCachePrefetched(
                    query = query,
                    anilistResults = baseList,
                    providerItems = extraResults.first,
                    jikanItems = extraResults.second,
                    cacheResult = !hasExtraFilters,
                )
                val mergedPage = anilistResource?.data?.copy(list = merged) ?: Page(list = merged, info = null)
                emit(Resource.success(mergedPage))
            } else {
                emit(Resource.loading())
                val anilistResource = mediaListRepository
                    .fetchMediaMediumList(useNetwork, filterStrategy)
                    .asResource()
                    .firstOrNull()
                emit(anilistResource)
            }
        }
    }
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(1000),
        initialValue = Resource.loading()
    )

    /** Returns fallback metadata for [mediaId] when resolved via ani-cli or Jikan. */
    fun getFallbackMeta(mediaId: Int): SearchFallbackMeta? = providerSearchHelper.getFallbackMeta(mediaId)

    fun setMediaSort(sort: Media.Sort) {
        stateStore.isLatestEpisodes.value = false
        stateStore.selectedSort.value = sort
    }

    fun setIsDescending(isDescending: Boolean) {
        stateStore.isLatestEpisodes.value = false
        stateStore.isDescending.value = isDescending
    }

    fun setSearchQuery(searchQuery: String?) {
        stateStore.isLatestEpisodes.value = false
        stateStore.searchQuery.value = searchQuery
    }

    fun setMediaType(type: String) {
        if (stateStore.mediaType.value != type) {
            stateStore.isLatestEpisodes.value = false
            stateStore.mediaType.value = type
            resetFilter(ChipFilterType.FORMAT)
        }
    }

    private fun setAllFilters(filterType: ChipFilterType, allFilters: Set<String>?) {
        when (filterType) {
            ChipFilterType.GENRE -> {
                _allGenres.value = allFilters
                allGenres.value = allFilters
            }
            ChipFilterType.FORMAT -> {
                _allFormats.value = allFilters
                allFormats.value = allFilters
            }
            ChipFilterType.STATUS -> {
                _allStatuses.value = allFilters
                allStatuses.value = allStatuses.value ?: allFilters
            }
        }
    }

    fun includeFilter(filterType: ChipFilterType, filter: String?) {
        if (filter == null) return
        stateStore.isLatestEpisodes.value = false
        when (filterType) {
            ChipFilterType.GENRE -> {
                includedGenres.value = includedGenres.value + filter
                allGenres.value = allGenres.value?.minus(filter)
            }
            ChipFilterType.FORMAT -> {
                includedFormats.value = includedFormats.value + filter
                allFormats.value = allFormats.value?.minus(filter)
            }
            ChipFilterType.STATUS -> {
                includedStatuses.value = includedStatuses.value + filter
                allStatuses.value = allStatuses.value?.minus(filter)
            }
        }
    }

    fun excludeFilter(filterType: ChipFilterType, filter: String?) {
        if (filter == null) return
        stateStore.isLatestEpisodes.value = false
        when (filterType) {
            ChipFilterType.GENRE -> {
                excludedGenres.value = excludedGenres.value + filter
                includedGenres.value = includedGenres.value - filter
            }
            ChipFilterType.FORMAT -> {
                excludedFormats.value = excludedFormats.value + filter
                includedFormats.value = includedFormats.value - filter
            }
            ChipFilterType.STATUS -> {
                excludedStatuses.value = excludedStatuses.value + filter
                includedStatuses.value = includedStatuses.value - filter
            }
        }
    }

    fun clearFilter(filterType: ChipFilterType, filter: String?) {
        if (filter == null) return
        stateStore.isLatestEpisodes.value = false
        when (filterType) {
            ChipFilterType.GENRE -> {
                allGenres.value = allGenres.value?.plus(filter)
                excludedGenres.value = excludedGenres.value - filter
            }
            ChipFilterType.FORMAT -> {
                allFormats.value = allFormats.value?.plus(filter)
                excludedFormats.value = excludedFormats.value - filter
            }
            ChipFilterType.STATUS -> {
                allStatuses.value = allStatuses.value?.plus(filter)
                excludedStatuses.value = excludedStatuses.value - filter
            }
        }
    }

    fun setMediaSeason(season: String?) {
        stateStore.isLatestEpisodes.value = false
        stateStore.selectedSeason.value = season
    }

    fun setMediaYear(year: Int?) {
        stateStore.isLatestEpisodes.value = false
        stateStore.selectedYear.value = year
    }

    fun setPage(page: Int) {
        stateStore.page.value = page
    }

    fun setIsAdult(isAdult: Boolean) {
        stateStore.isLatestEpisodes.value = false
        stateStore.isAdult.value = isAdult
    }

    fun resetFilter(filterType: ChipFilterType) {
        stateStore.isLatestEpisodes.value = false
        when (filterType) {
            ChipFilterType.GENRE -> {
                allGenres.value = _allGenres.value
                includedGenres.value = emptySet()
                excludedGenres.value = emptySet()
            }
            ChipFilterType.FORMAT -> {
                allFormats.value = _allFormats.value
                includedFormats.value = emptySet()
                excludedFormats.value = emptySet()
            }
            ChipFilterType.STATUS -> {
                allStatuses.value = _allStatuses.value
                includedStatuses.value = emptySet()
                excludedStatuses.value = emptySet()
            }
        }
    }

    fun reset() {
        stateStore.searchQuery.value = null
        stateStore.selectedSort.value = Media.Sort.POPULARITY
        stateStore.isDescending.value = true
        stateStore.selectedSeason.value = null
        stateStore.selectedYear.value = null
        stateStore.isAdult.value = false
        stateStore.isLatestEpisodes.value = false
        ChipFilterType.entries.forEach {
            resetFilter(it)
        }
        stateStore.page.value = 1
        viewModelScope.launch {
            preferencesRepository.setExplorePerPage(PreferencesRepository.DEFAULT_EXPLORE_PER_PAGE)
        }
    }

    fun refresh(setIsRefreshing: (Boolean) -> Unit) = viewModelScope.launch {
        setIsRefreshing(true)
        useNetwork = true
        refreshTrigger.emit(Unit)
        delay(1500.milliseconds)
        useNetwork = false
        setIsRefreshing(false)
    }

    val yearRange = getYears()

    private fun getYears(): IntRange {
        val now = Clock.System.now()
        val timeZone = TimeZone.currentSystemDefault()
        val currentYear = now.toLocalDateTime(timeZone).year
        return EARLIEST_YEAR..currentYear + 1
    }

    companion object {
        private const val SEARCH_QUERY = "searchQuery"
        private const val EARLIEST_YEAR = 1940
    }

    enum class ChipFilterType(val tag: String) {
        GENRE(Constants.GENRES),
        FORMAT(Constants.FORMATS),
        STATUS(Constants.STATUSES)
    }

    data class ChipFilterGroup(
        val type: ChipFilterType,
        val allFilters: ImmutableList<String>,
        val includedFilters: ImmutableList<String>,
        val excludedFilters: ImmutableList<String>,
    )

    data class ChipFilterSegregation(
        val included: Set<String>,
        val excluded: Set<String>,
    )

    data class SeasonYear(
        val season: String?,
        val year: Int?,
    )

    data class FilterSheetOptions(
        val genres: ChipFilterSegregation,
        val seasonYear: SeasonYear,
        val format: ChipFilterSegregation,
        val status: ChipFilterSegregation,
        val isAdult: Boolean,
        val mediaType: String,
    )
}

inline fun <
        T1,
        T2,
        T3,
        T4,
        T5,
        T6,
        R
        > combine(
    flow: Flow<T1>,
    flow2: Flow<T2>,
    flow3: Flow<T3>,
    flow4: Flow<T4>,
    flow5: Flow<T5>,
    flow6: Flow<T6>,
    crossinline transform: suspend (
        T1,
        T2,
        T3,
        T4,
        T5,
        T6,
    ) -> R,
): Flow<R> = combine(
    flow,
    flow2,
    flow3,
    flow4,
    flow5,
    flow6,
) { args: Array<*> ->
    @Suppress("UNCHECKED_CAST")
    transform(
        args[0] as T1,
        args[1] as T2,
        args[2] as T3,
        args[3] as T4,
        args[4] as T5,
        args[5] as T6,
    )
}
