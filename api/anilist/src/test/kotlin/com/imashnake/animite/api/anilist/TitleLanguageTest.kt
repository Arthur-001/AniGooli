package com.imashnake.animite.api.anilist

import com.imashnake.animite.api.anilist.sanitize.media.Media
import org.junit.Assert.assertEquals
import org.junit.Test

class TitleLanguageTest {

    @Test
    fun `safeValueOf maps legacy DEFAULT and unknown values to ENGLISH`() {
        assertEquals(Media.Language.ENGLISH, Media.Language.safeValueOf("DEFAULT"))
        assertEquals(Media.Language.ENGLISH, Media.Language.safeValueOf(null))
        assertEquals(Media.Language.ENGLISH, Media.Language.safeValueOf("UNKNOWN_LANG"))
        assertEquals(Media.Language.NATIVE, Media.Language.safeValueOf("NATIVE"))
        assertEquals(Media.Language.ROMAJI, Media.Language.safeValueOf("ROMAJI"))
    }

    @Test
    fun `resolveTitle defaults to English with deterministic fallback`() {
        val english = Media.resolveTitle(
            english = "Attack on Titan",
            nativeTitle = "進撃の巨人",
            romaji = "Shingeki no Kyojin",
            userPreferred = "Shingeki no Kyojin",
            language = Media.Language.ENGLISH,
        )
        assertEquals("Attack on Titan", english)

        val fallbackToRomaji = Media.resolveTitle(
            english = null,
            nativeTitle = "進撃の巨人",
            romaji = "Shingeki no Kyojin",
            userPreferred = "Shingeki no Kyojin",
            language = Media.Language.ENGLISH,
        )
        assertEquals("Shingeki no Kyojin", fallbackToRomaji)

        val japanese = Media.resolveTitle(
            english = "Attack on Titan",
            nativeTitle = "進撃の巨人",
            romaji = "Shingeki no Kyojin",
            userPreferred = "Shingeki no Kyojin",
            language = Media.Language.NATIVE,
        )
        assertEquals("進撃の巨人", japanese)

        val japaneseEnglish = Media.resolveTitle(
            english = "Attack on Titan",
            nativeTitle = "進撃の巨人",
            romaji = "Shingeki no Kyojin",
            userPreferred = "Attack on Titan",
            language = Media.Language.ROMAJI,
        )
        assertEquals("Shingeki no Kyojin", japaneseEnglish)
    }
}
