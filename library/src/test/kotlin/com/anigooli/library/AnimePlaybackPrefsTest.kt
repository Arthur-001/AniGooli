package com.anigooli.library

import com.imashnake.animite.api.anilist.sanitize.media.Media
import org.junit.Assert.assertEquals
import org.junit.Test

class AnimePlaybackPrefsTest {

    @Test
    fun `offline watch status maps MAL and AniList strings cleanly`() {
        assertEquals(OfflineWatchStatus.WATCHING, OfflineWatchStatus.safeParse("CURRENT"))
        assertEquals(OfflineWatchStatus.PLAN_TO_WATCH, OfflineWatchStatus.safeParse("Plan to Watch"))
        assertEquals(OfflineWatchStatus.ON_HOLD, OfflineWatchStatus.safeParse("On-Hold"))
        assertEquals(OfflineWatchStatus.COMPLETED, OfflineWatchStatus.safeParse("Completed"))
    }

    @Test
    fun `offline anime display title honors English default and language overrides`() {
        val anime = OfflineAnimeEntity(
            localId = "id-1",
            anilistId = 1,
            titleEnglish = "Demon Slayer",
            titleRomaji = "Kimetsu no Yaiba",
            titleNative = "鬼滅の刃",
            createdAt = 1L,
            updatedAt = 1L,
        )
        assertEquals("Demon Slayer", LibraryViewModel.resolveDisplayTitle(anime, Media.Language.ENGLISH))
        assertEquals("鬼滅の刃", LibraryViewModel.resolveDisplayTitle(anime, Media.Language.NATIVE))
        assertEquals("Kimetsu no Yaiba", LibraryViewModel.resolveDisplayTitle(anime, Media.Language.ROMAJI))
    }
}
