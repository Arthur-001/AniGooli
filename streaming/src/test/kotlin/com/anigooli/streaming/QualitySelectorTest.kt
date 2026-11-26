package com.anigooli.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QualitySelectorTest {

    private val sampleSources = listOf(
        StreamSource(
            qualityLabel = "360p",
            height = 360,
            serverName = "VidHide",
            url = "https://cdn.example/360.m3u8",
            isHls = true,
            subtitleUrl = "https://cdn.example/en.vtt",
        ),
        StreamSource(
            qualityLabel = "720p",
            height = 720,
            serverName = "VidHide",
            url = "https://cdn.example/720.m3u8",
            isHls = true,
            subtitleUrl = null,
        ),
        StreamSource(
            qualityLabel = "720p",
            height = 720,
            serverName = "Kiwi",
            url = "https://cdn.example/720_sub.m3u8",
            isHls = true,
            subtitleUrl = "https://cdn.example/en.vtt",
        ),
        StreamSource(
            qualityLabel = "1080p",
            height = 1080,
            serverName = "VidHide",
            url = "https://cdn.example/1080.m3u8",
            isHls = true,
            subtitleUrl = "https://cdn.example/en.vtt",
        ),
    )

    @Test
    fun `default 1080p selects 1080p source when available`() {
        val picked = QualitySelector.selectSource(sampleSources, preferredQuality = "1080p")
        assertNotNull(picked)
        assertEquals(1080, picked!!.height)
    }

    @Test
    fun `720p selects 720p preferring source with English subtitle track`() {
        val picked = QualitySelector.selectSource(
            sampleSources,
            preferredQuality = "720p",
            preferSubtitles = true,
        )
        assertNotNull(picked)
        assertEquals(720, picked!!.height)
        assertEquals("Kiwi", picked.serverName)
    }

    @Test
    fun `360p selects exact 360p source`() {
        val picked = QualitySelector.selectSource(sampleSources, preferredQuality = "360p")
        assertEquals(360, picked?.height)
    }

    @Test
    fun `unavailable 1080p falls back to highest available resolution`() {
        val onlyLower = sampleSources.filter { it.height <= 720 }
        val picked = QualitySelector.selectSource(onlyLower, preferredQuality = "1080p")
        assertNotNull(picked)
        assertEquals(720, picked!!.height)
    }

    @Test
    fun `empty source list returns null`() {
        assertNull(QualitySelector.selectSource(emptyList(), "1080p"))
    }
}
