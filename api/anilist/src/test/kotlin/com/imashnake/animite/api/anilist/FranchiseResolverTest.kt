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

    @Test
    fun `excludes OTHER, CHARACTER, CONTAINS, and MUSIC bridge edges from franchise sections`() {
        val apothecary = mediaNode(195516, Media.Small.Type.ANIME, Media.Format.TV, "Apothecary Diaries S3", 2026)
        val chainsawMan = mediaNode(127230, Media.Small.Type.ANIME, Media.Format.TV, "Chainsaw Man", 2022)
        val cameoAnime = mediaNode(99901, Media.Small.Type.ANIME, Media.Format.TV, "Cameo Show", 2024)
        val anthologyShort = mediaNode(99902, Media.Small.Type.ANIME, Media.Format.MOVIE, "Anthology Short", 2023)
        val musicVideo = mediaNode(218387, Media.Small.Type.ANIME, Media.Format.MUSIC, "AIYOU", 2026)

        val graph = mapOf(
            195516 to FranchiseNode(
                apothecary,
                edges = listOf(
                    Media.Relation.OTHER to musicVideo,
                    Media.Relation.OTHER to chainsawMan,
                    Media.Relation.CHARACTER to cameoAnime,
                    Media.Relation.CONTAINS to anthologyShort,
                ),
            ),
        )

        val organized = FranchiseOrganizer.organize(rootMedia = apothecary, nodesById = graph, mangaNewestFirst = false)
        assertEquals(listOf(195516), organized.mainSeasonsAndMovies.map { it.media.id })
        assertTrue(organized.ovasAndSpecials.isEmpty())
        assertTrue(organized.mangas.isEmpty())
    }

    @Test
    fun `includes title-matched OTHER ONA sequels like Cyberpunk Edgerunners 1 and 2 in main seasons`() {
        val cp1 = mediaNode(120377, Media.Small.Type.ANIME, Media.Format.ONA, "Cyberpunk: Edgerunners", 2022, 9)
        val cp2 = mediaNode(195539, Media.Small.Type.ANIME, Media.Format.ONA, "Cyberpunk: Edgerunners 2", 2026, 10)
        val music = mediaNode(154591, Media.Small.Type.ANIME, Media.Format.MUSIC, "Let You Down", 2022, 9)
        val manga = mediaNode(185521, Media.Small.Type.MANGA, Media.Format.MANGA, "Cyberpunk: Edgerunners MADNESS", 2025, 1)

        val graph = mapOf(
            120377 to FranchiseNode(
                cp1,
                edges = listOf(
                    Media.Relation.OTHER to cp2,
                    Media.Relation.CHARACTER to music,
                    Media.Relation.PREQUEL to manga,
                ),
            ),
            195539 to FranchiseNode(
                cp2,
                edges = listOf(Media.Relation.OTHER to cp1),
            ),
        )

        val organized = FranchiseOrganizer.organize(rootMedia = cp1, nodesById = graph, mangaNewestFirst = false)
        assertEquals(listOf(120377, 195539), organized.mainSeasonsAndMovies.map { it.media.id })
        assertEquals(listOf(1, 2), organized.mainSeasonsAndMovies.map { it.orderIndex })
        assertTrue(organized.ovasAndSpecials.isEmpty())
        assertEquals(listOf(185521), organized.mangas.map { it.media.id })
    }

    @Test
    fun `keeps SIDE_STORY ONAs in OVAs & Specials when main TV seasons exist`() {
        val tv1 = mediaNode(161645, Media.Small.Type.ANIME, Media.Format.TV, "The Apothecary Diaries", 2023, 10)
        val ona1 = mediaNode(170508, Media.Small.Type.ANIME, Media.Format.ONA, "Maomao no Hitorigoto", 2023, 10)
        val usj = mediaNode(206349, Media.Small.Type.ANIME, Media.Format.SPECIAL, "Maomao no Hitorigoto: USJ wo Chousa Seyo!", 2025, 7)

        val graph = mapOf(
            161645 to FranchiseNode(
                tv1,
                edges = listOf(Media.Relation.SIDE_STORY to ona1),
            ),
            170508 to FranchiseNode(
                ona1,
                relationType = Media.Relation.SIDE_STORY,
                edges = listOf(Media.Relation.OTHER to usj),
            ),
        )

        val organized = FranchiseOrganizer.organize(rootMedia = tv1, nodesById = graph, mangaNewestFirst = false)
        assertEquals(listOf(161645), organized.mainSeasonsAndMovies.map { it.media.id })
        assertEquals(listOf(170508, 206349), organized.ovasAndSpecials.map { it.media.id })
    }

    @Test
    fun `extracts sequence numbers across Arabic, Roman, and Season formats`() {
        assertEquals(2, FranchiseTitleMatcher.extractSequenceNumber("Cyberpunk: Edgerunners 2"))
        assertEquals(2, FranchiseTitleMatcher.extractSequenceNumber("The Apothecary Diaries Season 2"))
        assertEquals(3, FranchiseTitleMatcher.extractSequenceNumber("Maomao no Hitorigoto 3rd Season"))
        assertEquals(4, FranchiseTitleMatcher.extractSequenceNumber("Overlord IV"))
        assertEquals(2, FranchiseTitleMatcher.extractSequenceNumber("Mob Psycho 100 II"))
        assertEquals(null, FranchiseTitleMatcher.extractSequenceNumber("86"))
        assertTrue(
            FranchiseTitleMatcher.areRelated(
                mediaNode(1, Media.Small.Type.ANIME, Media.Format.ONA, "Cyberpunk: Edgerunners", 2022),
                mediaNode(2, Media.Small.Type.ANIME, Media.Format.ONA, "Cyberpunk: Edgerunners 2", 2026),
            )
        )
        assertFalse(
            FranchiseTitleMatcher.areRelated(
                mediaNode(1, Media.Small.Type.ANIME, Media.Format.TV, "The Apothecary Diaries", 2023),
                mediaNode(2, Media.Small.Type.ANIME, Media.Format.TV, "Chainsaw Man", 2022),
            )
        )
    }
}
