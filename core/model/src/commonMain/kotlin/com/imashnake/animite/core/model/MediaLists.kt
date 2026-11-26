package com.imashnake.animite.core.model

import anigooli.core.model.generated.resources.Res
import anigooli.core.model.generated.resources.all_time_popular
import anigooli.core.model.generated.resources.latest_episodes
import anigooli.core.model.generated.resources.newly_added
import anigooli.core.model.generated.resources.popular_this_season
import anigooli.core.model.generated.resources.trending_now
import anigooli.core.model.generated.resources.upcoming_next_season
import org.jetbrains.compose.resources.StringResource

public enum class AnimeList(public val res: StringResource) {
    LATEST_EPISODES(Res.string.latest_episodes),
    TRENDING_NOW(Res.string.trending_now),
    POPULAR_THIS_SEASON(Res.string.popular_this_season),
    UPCOMING_NEXT_SEASON(Res.string.upcoming_next_season),
    ALL_TIME_POPULAR(Res.string.all_time_popular),
    NEWLY_ADDED(Res.string.newly_added),
}

public enum class MangaList(public val res: StringResource) {
    TRENDING_NOW(Res.string.trending_now),
    ALL_TIME_POPULAR(Res.string.all_time_popular),
    NEWLY_ADDED(Res.string.newly_added),
}
