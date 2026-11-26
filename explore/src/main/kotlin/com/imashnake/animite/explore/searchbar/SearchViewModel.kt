package com.imashnake.animite.explore.searchbar

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anigooli.streaming.ProviderAnime
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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: AnilistSearchRepository,
    private val providerSearchHelper: ProviderSearchHelper,
    private val savedStateHandle: SavedStateHandle,
    preferencesRepository: PreferencesRepository,
) : ViewModel() {
    private val mediaType = savedStateHandle.getStateFlow<MediaType?>(Constants.MEDIA_TYPE, null)
    private val query = savedStateHandle.getStateFlow<String?>(QUERY, null)

    /** Observable search text preserved across search bar minimize/expand actions. */
    val queryState: StateFlow<String?> = query

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

    @OptIn(FlowPreview::class)
    val searchList = combine(
        flow = mediaType.filterNotNull(),
        flow2 = query,
        flow3 = prefs,
        transform = ::Triple
    ).debounce(300L.milliseconds).flatMapLatest { (mediaType, query, prefs) ->
        if (query.isNullOrBlank()) {
            flowOf(Resource.success(persistentListOf()))
        } else {
            flow {
                emit(Resource.loading())
                val anilistList = runCatching {
                    searchRepository.fetchSearch(
                        type = mediaType,
                        search = query,
                        isNsfwEnabled = prefs.isNsfwEnabled,
                        language = prefs.language,
                        perPage = prefs.listSize,
                    ).firstOrNull()?.getOrNull()
                }.getOrNull().orEmpty()

                val combined = if (mediaType == MediaType.ANIME) {
                    providerSearchHelper.mergeProviderResults(query, anilistList)
                } else {
                    persistentListOf<Media.Medium>().addAll(anilistList)
                }
                emit(Resource.success(combined))
            }
        }
    }
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(1000),
        initialValue = Resource.loading()
    )

    /** Returns provider metadata for [mediaId] when resolved via ani-cli fallback. */
    fun getProviderEntry(mediaId: Int): ProviderAnime? = providerSearchHelper.getProviderEntry(mediaId)

    fun setMediaType(mediaType: MediaType) {
        savedStateHandle[Constants.MEDIA_TYPE] = mediaType
    }

    fun setQuery(query: String?) {
        savedStateHandle[QUERY] = query
    }

    companion object {
        const val QUERY = "query"
    }
}
