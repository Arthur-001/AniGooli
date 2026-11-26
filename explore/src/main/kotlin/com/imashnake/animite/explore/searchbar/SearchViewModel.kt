package com.imashnake.animite.explore.searchbar

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.imashnake.animite.api.anilist.AnilistSearchRepository
import com.imashnake.animite.api.anilist.sanitize.media.Media
import com.imashnake.animite.api.anilist.sanitize.settings.Prefs
import com.imashnake.animite.api.anilist.type.MediaType
import com.imashnake.animite.api.preferences.PreferencesRepository
import com.imashnake.animite.core.resource.Resource
import com.imashnake.animite.core.ui.Constants
import com.imashnake.animite.core.ui.component.MediaCardSize
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

private const val ANILIST_SEARCH_TIMEOUT_MS = 5_000L

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: AnilistSearchRepository,
    private val providerSearchHelper: ProviderSearchHelper,
    private val savedStateHandle: SavedStateHandle,
    preferencesRepository: PreferencesRepository,
) : ViewModel() {
    private val mediaType = savedStateHandle.getStateFlow<MediaType?>(Constants.MEDIA_TYPE, null)
    private val draftQueryFlow = savedStateHandle.getStateFlow(DRAFT_QUERY, "")
    private val submittedQueryFlow = savedStateHandle.getStateFlow<String?>(SUBMITTED_QUERY, null)
    private val searchTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Current text inside the search input field (preserved across minimize/expand). */
    val draftQuery: StateFlow<String> = draftQueryFlow

    /** Last explicitly submitted query, or null if cleared. */
    val submittedQuery: StateFlow<String?> = submittedQueryFlow

    /** Effective card size for search results matching the Explore/global card size setting. */
    val cardSize: StateFlow<MediaCardSize> = combine(
        preferencesRepository.exploreCardSize,
        preferencesRepository.mediaCardSize,
    ) { pageOverride, globalDefault ->
        MediaCardSize.safeValueOf(pageOverride ?: globalDefault)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(1000), MediaCardSize.COMPACT)

    val prefs = combine(
        flow = preferencesRepository.isNsfwEnabled.filterNotNull(),
        flow2 = preferencesRepository.language.filterNotNull(),
        flow3 = preferencesRepository.listSize.filterNotNull(),
        transform = { isNsfwEnabled, language, listSize ->
            Prefs(isNsfwEnabled, Media.Language.safeValueOf(language), listSize)
        }
    )

    val searchList = combine(
        mediaType.filterNotNull(),
        submittedQueryFlow,
        prefs,
        searchTrigger.onStart { emit(Unit) },
    ) { type, submitted, userPrefs, _ ->
        Triple(type, submitted, userPrefs)
    }.flatMapLatest { (mediaType, query, prefs) ->
        val trimmed = query?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            flowOf(Resource.success(persistentListOf()))
        } else {
            flow {
                val cached = providerSearchHelper.getCachedResults(trimmed)
                if (cached != null) {
                    // Instant response from cache, then refresh in background for new releases/episodes
                    emit(Resource.success(cached))
                } else {
                    emit(Resource.loading())
                }

                val anilistList = coroutineScope {
                    val anilistDeferred = async {
                        withTimeoutOrNull(ANILIST_SEARCH_TIMEOUT_MS) {
                            runCatching {
                                searchRepository.fetchSearch(
                                    type = mediaType,
                                    search = trimmed,
                                    isNsfwEnabled = prefs.isNsfwEnabled,
                                    language = prefs.language,
                                    perPage = prefs.listSize,
                                ).firstOrNull()?.getOrNull()
                            }.getOrNull()
                        }.orEmpty()
                    }
                    anilistDeferred.await()
                }

                val rankedAnilist = providerSearchHelper.rankByRelevance(trimmed, anilistList)
                if (cached == null && rankedAnilist.isNotEmpty()) {
                    emit(Resource.success(rankedAnilist))
                }

                if (mediaType == MediaType.ANIME) {
                    val combined = providerSearchHelper.mergeProviderResults(trimmed, anilistList)
                    emit(Resource.success(combined))
                } else {
                    providerSearchHelper.putCachedResults(trimmed, rankedAnilist)
                    emit(Resource.success(rankedAnilist))
                }
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(1000),
        initialValue = Resource.success(persistentListOf()),
    )

    /** Returns fallback metadata for [mediaId] when resolved via ani-cli or Jikan. */
    fun getFallbackMeta(mediaId: Int): SearchFallbackMeta? = providerSearchHelper.getFallbackMeta(mediaId)

    /** Sets active media type for search queries. */
    fun setMediaType(mediaType: MediaType) {
        savedStateHandle[Constants.MEDIA_TYPE] = mediaType
    }

    /** Updates draft input text without firing network requests. */
    fun updateDraftQuery(text: String) {
        savedStateHandle[DRAFT_QUERY] = text
    }

    /** Submits the current [draftQuery] (or [explicitQuery]) to execute search and refresh cache. */
    fun submitSearch(explicitQuery: String? = null) {
        val target = (explicitQuery ?: draftQueryFlow.value).trim()
        if (target.isEmpty()) {
            clearSearch()
            return
        }
        savedStateHandle[DRAFT_QUERY] = target
        savedStateHandle[SUBMITTED_QUERY] = target
        searchTrigger.tryEmit(Unit)
    }

    /** Clears both draft and submitted search text and resets results. */
    fun clearSearch() {
        savedStateHandle[DRAFT_QUERY] = ""
        savedStateHandle[SUBMITTED_QUERY] = null
    }

    companion object {
        const val DRAFT_QUERY = "draft_query"
        const val SUBMITTED_QUERY = "submitted_query"
    }
}
