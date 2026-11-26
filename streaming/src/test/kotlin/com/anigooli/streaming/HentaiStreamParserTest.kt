package com.anigooli.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HentaiStreamParserTest {
    @Test
    fun `parses hentai stream search cards with relative thumbnails`() {
        val html = """
            <div class="vv-grid">
                <article class="vv-card" data-video-id="1400">
                    <a class="vv-thumb" href="/video/itadaki-seieki-episode-1-uncensored-eng/">
                        <img src="/media/thumb/1400/2.jpg" alt="Itadaki! Seieki Episode 1 [Uncensored ENG]" loading="lazy">
                    </a>
                    <div class="vv-card-body">
                        <h3><a href="/video/itadaki-seieki-episode-1-uncensored-eng/">Itadaki! Seieki Episode 1 [Uncensored ENG]</a></h3>
                    </div>
                </article>
            </div>
        """.trimIndent()

        val items = HentaiStreamParser.parseSearchHtml(html)
        assertEquals(1, items.size)
        assertEquals("hentaistream:itadaki-seieki-episode-1-uncensored-eng", items[0].providerId)
        assertEquals("Itadaki! Seieki Episode 1 [Uncensored ENG]", items[0].title)
        assertEquals("https://hentai.stream/media/thumb/1400/2.jpg", items[0].coverImage)
    }

    @Test
    fun `extracts direct mp4 stream url from hentai stream video page`() {
        val videoHtml = """
            <video src="https://hentai.stream/media/video/1400.mp4" controls></video>
        """.trimIndent()

        val parsed = HentaiStreamParser.parseVideoPageCandidates(videoHtml)
        assertEquals(1, parsed.candidates.size)
        assertEquals("https://hentai.stream/media/video/1400.mp4", parsed.candidates[0].url)
        assertEquals("mp4", parsed.candidates[0].format)
        assertEquals("https://hentai.stream/", parsed.candidates[0].referer)
        assertTrue(HentaiStreamParser.isHentaiStreamProviderId("hentaistream:itadaki-seieki-episode-1-uncensored-eng"))
    }
}
