package com.imashnake.animite.api.anilist

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.apollographql.cache.normalized.FetchPolicy
import com.apollographql.cache.normalized.fetchPolicy
import com.imashnake.animite.api.anilist.sanitize.explore.FilterStrategy
import com.imashnake.animite.api.anilist.sanitize.media.Info
import com.imashnake.animite.api.anilist.sanitize.media.Media
import com.imashnake.animite.api.anilist.sanitize.media.MediaList
import com.imashnake.animite.api.anilist.sanitize.media.Page
import com.imashnake.animite.api.anilist.type.MediaSort
import com.imashnake.animite.api.anilist.type.MediaType
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter

private const val HENTAI = "Hentai"

/**
 * Repository for fetching [MediaQuery.Media] or a list of [MediaListQuery.Medium].
 *
 * @param apolloClient Default apollo client.
 * [fetchMediaList] - Fetches a list of [MediaListQuery.Medium].
 * [fetchMedia] - Fetches detailed media: [MediaQuery.Media].
 */
@Suppress("LongParameterList")
class AnilistMediaRepository(
    private val apolloClient: ApolloClient,
) {

    fun fetchMediaList(
        useNetwork: Boolean,
        filterStrategy: FilterStrategy
    ): Flow<Result<MediaList>> = with(filterStrategy) {
        apolloClient.query(
            MediaListQuery(
                type = Optional.presentIfNotNull(mediaType),
                page = Optional.presentIfNotNull(page),
                perPage = Optional.presentIfNotNull(perPage),
                sort = Optional.presentIfNotNull(sort),
                season = Optional.presentIfNotNull(season),
                seasonYear = Optional.presentIfNotNull(year),
                isAdult = Optional.presentIfNotNull(if (isNsfwEnabled) null else false)
            )
        )
        .fetchPolicy(
            if (useNetwork) {
                FetchPolicy.NetworkFirst
            } else FetchPolicy.CacheFirst
        )
        .toFlow()
        .filter { it.exception == null }
        .asResult {
            MediaList(
                list = it.page?.media.orEmpty().filterNotNull().map { query ->
                    Media.Small(query.mediaSmall, language)
                }.toImmutableList(),
                filterStrategy = this,
            )
        }
    }

    fun fetchMediaMediumList(
        useNetwork: Boolean,
        filterStrategy: FilterStrategy
    ): Flow<Result<Page<Media.Medium>>> = with(filterStrategy) {
        val effectiveSort = if (!search.isNullOrBlank()) {
            listOf(MediaSort.SEARCH_MATCH) + sort.filterNot { it == MediaSort.SEARCH_MATCH }
        } else {
            sort
        }
        apolloClient.query(
            MediaMediumListQuery(
                type = Optional.presentIfNotNull(mediaType),
                page = Optional.presentIfNotNull(page),
                perPage = Optional.presentIfNotNull(perPage),
                sort = Optional.presentIfNotNull(effectiveSort),
                genre = Optional.presentIfNotNull(genre),
                genreIn = Optional.presentIfNotNull(includedGenres),
                genreNotIn = Optional.presentIfNotNull(excludedGenres),
                search = Optional.presentIfNotNull(search),
                season = Optional.presentIfNotNull(season),
                // Start FuzzyDateInt has to be YYYYMMDD -> YYYY0101
                startDate = Optional.presentIfNotNull(year?.times(10000)?.plus(101)),
                // End FuzzyDateInt has to be YYYYMMDD -> YYYY1231
                endDate = Optional.presentIfNotNull(year?.times(10000)?.plus(1231)),
                formatIn = Optional.presentIfNotNull(includedFormats),
                formatNotIn = Optional.presentIfNotNull(excludedFormats),
                statusIn = Optional.presentIfNotNull(includedStatuses),
                statusNotIn = Optional.presentIfNotNull(excludedStatuses),
                isAdult = Optional.presentIfNotNull(
                    if (isNsfwEnabled) {
                        if (isAdult) true else null
                    } else false
                )
            )
        )
        .fetchPolicy(
            if (useNetwork) {
                FetchPolicy.NetworkOnly
            } else FetchPolicy.CacheFirst
        )
        .toFlow()
        .filter { it.exception == null }
        .asResult { data ->
            Page(
                list = data.page!!.media.orEmpty().filterNotNull().map { query ->
                    Media.Medium(query.mediaMedium, language)
                }.toImmutableList(),
                info = data.page.pageInfo?.let { Info(it, filterStrategy.perPage) }
            )
        }
    }

    /**
     * Fetches the most recently aired anime episodes (ordered newest to oldest) for the Anime home row.
     */
    fun fetchLatestEpisodesList(
        useNetwork: Boolean,
        filterStrategy: FilterStrategy,
    ): Flow<Result<MediaList>> = with(filterStrategy) {
        val nowSeconds = (System.currentTimeMillis() / 1000L).toInt()
        val requestPerPage = (perPage * 2).coerceIn(perPage, 50)
        apolloClient.query(
            LatestEpisodesQuery(
                page = Optional.presentIfNotNull(page.coerceAtLeast(1)),
                perPage = Optional.presentIfNotNull(requestPerPage),
                airingAtLesser = Optional.presentIfNotNull(nowSeconds),
            )
        )
        .fetchPolicy(
            if (useNetwork) FetchPolicy.NetworkOnly else FetchPolicy.NetworkFirst
        )
        .toFlow()
        .filter { it.exception == null }
        .asResult { data ->
            val items = data.page?.airingSchedules
                .orEmpty()
                .mapNotNull { schedule ->
                    val media = schedule?.media ?: return@mapNotNull null
                    if (!isNsfwEnabled && media.isAdult == true) return@mapNotNull null
                    Media.Small(media.mediaSmall, language)
                }
                .distinctBy { it.id }
                .take(perPage)
                .toImmutableList()
            MediaList(
                list = items,
                filterStrategy = this,
            )
        }
    }

    /**
     * Fetches the most recently aired anime episodes (ordered newest to oldest) for the Explore grid.
     */
    fun fetchLatestEpisodesMediumList(
        useNetwork: Boolean,
        filterStrategy: FilterStrategy,
    ): Flow<Result<Page<Media.Medium>>> = with(filterStrategy) {
        val nowSeconds = (System.currentTimeMillis() / 1000L).toInt()
        val requestPerPage = (perPage * 2).coerceIn(perPage, 50)
        apolloClient.query(
            LatestEpisodesMediumQuery(
                page = Optional.presentIfNotNull(page.coerceAtLeast(1)),
                perPage = Optional.presentIfNotNull(requestPerPage),
                airingAtLesser = Optional.presentIfNotNull(nowSeconds),
            )
        )
        .fetchPolicy(
            if (useNetwork) FetchPolicy.NetworkOnly else FetchPolicy.CacheFirst
        )
        .toFlow()
        .filter { it.exception == null }
        .asResult { data ->
            val items = data.page?.airingSchedules
                .orEmpty()
                .mapNotNull { schedule ->
                    val media = schedule?.media ?: return@mapNotNull null
                    if (!isNsfwEnabled && media.isAdult == true) return@mapNotNull null
                    Media.Medium(media.mediaMedium, language)
                }
                .distinctBy { it.id }
                .take(perPage)
                .toImmutableList()
            Page(
                list = items,
                info = data.page?.pageInfo?.let {
                    Info(
                        currentPage = it.currentPage,
                        lastPage = it.lastPage,
                        hasNextPage = it.hasNextPage,
                        perPage = filterStrategy.perPage,
                    )
                },
            )
        }
    }

    fun fetchMedia(
        id: Int?,
        mediaType: MediaType,
        perPage: Int = 10,
        language: Media.Language = Media.Language.ENGLISH,
    ): Flow<Result<Media>> {
        return apolloClient.query(
            MediaQuery(
                id = Optional.presentIfNotNull(id),
                type = Optional.presentIfNotNull(mediaType),
                perPage = Optional.presentIfNotNull(perPage)
            )
        )
        .fetchPolicy(FetchPolicy.CacheFirst)
        .toFlow()
        .filter { it.exception == null }
        .asResult { Media(it.media!!, language) }
    }

    suspend fun fetchMediaGenres(isAdult: Boolean) = apolloClient
        .query(GenresQuery())
        .fetchPolicy(FetchPolicy.CacheFirst)
        .execute()
        .data
        ?.GenreCollection
        ?.filterNotNull()
        ?.filter { genre -> if (!isAdult) genre != HENTAI else true }
        .orEmpty()
}
