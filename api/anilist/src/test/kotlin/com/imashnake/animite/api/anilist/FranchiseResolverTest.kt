package com.imashnake.animite.api.anilist

import com.imashnake.animite.api.anilist.sanitize.media.Media
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FranchiseResolverTest {

    private fun mediaNode(
        id: Int,
        type: Media.Small.Type,
        format: Media.Format,
        title: String,
        year: Int,
        month: Int = 1,
    ) = Media.Small(
        id = id,
        type = type,
        title = title,
        coverImage = null,
        format = format,
        startYear = year,
        startMonth = month,
        startDay = 1,
    )

    @Test
    fun `orders main seasons and movies chronologically while separating OVAs and Mangas`() {
        val s1 = mediaNode(10, Media.Small.Type.ANIME, Media.Format.TV, "Season 1", 2019)
        val movie = mediaNode(20, Media.Small.Type.ANIME, Media.Format.MOVIE, "Mugen Train Movie", 2020)
        val s2 = mediaNode(30, Media.Small.Type.ANIME, Media.Format.TV, "Season 2", 2021)
        val ova = mediaNode(40, Media.Small.Type.ANIME, Media.Format.OVA, "Special OVA", 2020, 6)
        val mangaOld = mediaNode(50, Media.Small.Type.MANGA, Media.Format.MANGA, "Main Manga", 2016)
        val mangaNew = mediaNode(60, Media.Small.Type.MANGA, Media.Format.NOVEL, "Spin-off Novel", 2022)

        val graph = mapOf(
            10 to FranchiseNode(s1, edges = listOf(Media.Relation.SEQUEL to movie, Media.Relation.ADAPTATION to mangaOld)),
            20 to FranchiseNode(movie, edges = listOf(Media.Relation.PREQUEL to s1, Media.Relation.SEQUEL to s2, Media.Relation.SIDE_STORY to ova)),
            30 to FranchiseNode(s2, edges = listOf(Media.Relation.PREQUEL to movie, Media.Relation.ALTERNATIVE to mangaNew)),
            40 to FranchiseNode(ova),
            50 to FranchiseNode(mangaOld),
            60 to FranchiseNode(mangaNew),
        )

        val oldestFirst = FranchiseOrganizer.organize(rootMedia = s2, nodesById = graph, mangaNewestFirst = false)
        assertEquals(listOf(10, 20, 30), oldestFirst.mainSeasonsAndMovies.map { it.media.id })
        assertEquals(listOf(1, 2, 3), oldestFirst.mainSeasonsAndMovies.map { it.orderIndex })
        assertFalse(oldestFirst.mainSeasonsAndMovies[0].isCurrent)
        assertTrue(oldestFirst.mainSeasonsAndMovies[2].isCurrent)

        assertEquals(listOf(40), oldestFirst.ovasAndSpecials.map { it.media.id })
        assertEquals(listOf(50, 60), oldestFirst.mangas.map { it.media.id })

        val newestFirst = FranchiseOrganizer.organize(rootMedia = s2, nodesById = graph, mangaNewestFirst = true)
        assertEquals(listOf(60, 50), newestFirst.mangas.map { it.media.id })
    }
}
