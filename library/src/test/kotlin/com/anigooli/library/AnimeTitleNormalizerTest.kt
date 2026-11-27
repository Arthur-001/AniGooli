package com.anigooli.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeTitleNormalizerTest {

    @Test
    fun `displayWholeTitle strips season, part, roman, and media suffixes`() {
        assertEquals("Attack on Titan", AnimeTitleNormalizer.displayWholeTitle("Attack on Titan Season 2"))
        assertEquals("Attack on Titan", AnimeTitleNormalizer.displayWholeTitle("Attack on Titan Final Season Part 2"))
        assertEquals("Demon Slayer", AnimeTitleNormalizer.displayWholeTitle("Demon Slayer 3rd Season"))
        assertEquals("Mob Psycho 100", AnimeTitleNormalizer.displayWholeTitle("Mob Psycho 100 II"))
        assertEquals("Overlord", AnimeTitleNormalizer.displayWholeTitle("Overlord IV"))
        assertEquals("Attack on Titan", AnimeTitleNormalizer.displayWholeTitle("Attack on Titan OVA"))
        assertEquals("Attack on Titan", AnimeTitleNormalizer.displayWholeTitle("Attack on Titan: The Movie"))
        assertEquals("86", AnimeTitleNormalizer.displayWholeTitle("86"))
    }

    @Test
    fun `titlesMatch detects duplicates across seasons, OVAs, movies, and punctuation`() {
        assertTrue(
            AnimeTitleNormalizer.titlesMatch(
                listOf("Attack on Titan"),
                listOf("Attack on Titan Season 3"),
            )
        )
        assertTrue(
            AnimeTitleNormalizer.titlesMatch(
                listOf("Mob Psycho 100"),
                listOf("Mob Psycho 100 II"),
            )
        )
        assertFalse(
            AnimeTitleNormalizer.titlesMatch(
                listOf("Attack on Titan"),
                listOf("Jujutsu Kaisen"),
            )
        )
    }
}
