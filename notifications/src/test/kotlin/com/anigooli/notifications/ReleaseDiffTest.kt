package com.anigooli.notifications

import com.imashnake.animite.api.anilist.sanitize.media.Media
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseDiffTest {

    @Test
    fun `initial baseline seed records state without firing spam notifications`() {
        val snapshot = RemoteAnimeSnapshot(
            anilistId = 1,
            title = "Frieren",
            airedEpisodeCount = 28,
            relations = listOf(
                TrackedRelationItem(2, Media.Small.Type.MANGA, Media.Format.MANGA, "Frieren Manga"),
            ),
        )
        val result = ReleaseDiffEngine.diff(previous = null, remote = snapshot, nowMs = 1000L)
        assertTrue(result.events.isEmpty())
        assertEquals(28, result.updatedTracker.lastKnownAiredEpisode)
    }

    @Test
    fun `detects new episode new season new OVA and new Manga without duplicates`() {
        val initial = ReleaseDiffEngine.diff(
            previous = null,
            remote = RemoteAnimeSnapshot(
                anilistId = 100,
                title = "Demon Slayer",
                airedEpisodeCount = 10,
                relations = listOf(
                    TrackedRelationItem(101, Media.Small.Type.ANIME, Media.Format.TV, "Season 1"),
                ),
            ),
        ).updatedTracker

        val secondSnapshot = RemoteAnimeSnapshot(
            anilistId = 100,
            title = "Demon Slayer",
            airedEpisodeCount = 11,
            relations = listOf(
                TrackedRelationItem(101, Media.Small.Type.ANIME, Media.Format.TV, "Season 1"),
                TrackedRelationItem(102, Media.Small.Type.ANIME, Media.Format.MOVIE, "Infinity Castle Movie"),
                TrackedRelationItem(103, Media.Small.Type.ANIME, Media.Format.OVA, "Hashira OVA"),
                TrackedRelationItem(104, Media.Small.Type.MANGA, Media.Format.MANGA, "Side Story Manga"),
            ),
        )

        val diff2 = ReleaseDiffEngine.diff(previous = initial, remote = secondSnapshot)
        assertEquals(4, diff2.events.size)
        assertTrue(diff2.events[0] is ReleaseEvent.NewEpisode)
        assertTrue(diff2.events[1] is ReleaseEvent.NewSeasonOrMovie)
        assertTrue(diff2.events[2] is ReleaseEvent.NewOva)
        assertTrue(diff2.events[3] is ReleaseEvent.NewManga)

        // Running again with the same snapshot emits zero duplicate notifications
        val diff3 = ReleaseDiffEngine.diff(previous = diff2.updatedTracker, remote = secondSnapshot)
        assertTrue(diff3.events.isEmpty())
    }
}
