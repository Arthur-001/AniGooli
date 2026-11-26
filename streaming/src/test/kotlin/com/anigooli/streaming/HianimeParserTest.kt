package com.anigooli.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class HianimeParserTest {
    @Test
    fun `parses overflow tv and uncensored variants from hianime search html`() {
        val html = """
            <div class="flw-item flw-item-big">
                <div class="film-poster">
                    <div class="tick-item tick-eps">8</div>
                    <img src="https://cdn.anipixcdn.co/thumbnail/tv.jpg" class="film-poster-img">
                </div>
                <div class="film-detail">
                    <h3 class="film-name">
                        <a href="https://hianime.fm/overflow-1434" title="Overflow (TV ver.)" data-jname="Overflow">
                            Overflow (TV ver.)
                        </a>
                    </h3>
                </div>
            </div>
            <div class="flw-item flw-item-big">
                <div class="film-poster">
                    <div class="tick-item tick-eps">8</div>
                    <img src="https://cdn.anipixcdn.co/thumbnail/uncensored.jpg" class="film-poster-img">
                </div>
                <div class="film-detail">
                    <h3 class="film-name">
                        <a href="https://hianime.fm/overflow-uncensored-9744" title="Overflow (Uncensored)" data-jname="Overflow (Uncensored)">
                            Overflow (Uncensored)
                        </a>
                    </h3>
                </div>
            </div>
            <div id="main-sidebar"></div>
        """.trimIndent()

        val items = HianimeParser.parseSearchHtml(html)
        assertEquals(2, items.size)
        assertEquals("hianime:overflow-1434", items[0].providerId)
        assertEquals("Overflow (TV ver.)", items[0].title)
        assertEquals("https://cdn.anipixcdn.co/thumbnail/tv.jpg", items[0].coverImage)
        assertEquals(8, items[0].episodeCount)
        assertEquals("hianime:overflow-uncensored-9744", items[1].providerId)
        assertEquals("Overflow (Uncensored)", items[1].title)
    }

    @Test
    fun `parses episodes server embed and xor deobfuscates zoko payload`() {
        val episodesJson = """
            {"html":"<a class=\"ep-item\" data-number=\"2\" data-id=\"140522\" href=\"/watch/overflow-uncensored-9744?ep=140522\">2</a><a class=\"ep-item\" data-number=\"1\" data-id=\"140521\" href=\"/watch/overflow-uncensored-9744?ep=140521\">1</a>"}
        """.trimIndent()
        val episodes = HianimeParser.parseEpisodesHtml(episodesJson, "overflow-uncensored-9744")
        assertEquals(listOf("1", "2"), episodes.map { it.episodeNumber })
        assertEquals("140521", episodes.first().episodeId)

        val embedUrl = "https://zokoanime.video/stream/anime/103956/sub"
        val hash = Base64.getEncoder().encodeToString(embedUrl.toByteArray(Charsets.UTF_8))
        val serversJson = """
            {"html":"<div class=\"server-item\" data-type=\"sub\" data-server-name=\"ZokoAnime\" data-hash=\"$hash\"></div>"}
        """.trimIndent()
        assertEquals(embedUrl, HianimeParser.parseServerEmbedUrl(serversJson, "sub"))

        val rawPayload = """{"src":"https://hls.example.com/master.m3u8","subtitles":[{"src":"https://subs.example.com/en.vtt","label":"English","default":true}]}"""
        val key = "otaku-embed-v1".toByteArray(Charsets.UTF_8)
        val rawBytes = rawPayload.toByteArray(Charsets.UTF_8)
        val xorBytes = ByteArray(rawBytes.size) { i -> (rawBytes[i].toInt() xor key[i % key.size].toInt()).toByte() }
        val blob = Base64.getEncoder().encodeToString(xorBytes)
        val embedHtml = """<script>window.__P="$blob";</script>"""

        val decoded = HianimeParser.extractAndDeobfuscateEmbed(embedHtml)
        assertNotNull(decoded)
        val parsed = HianimeParser.parseZokoEmbedJson(decoded!!, embedUrl)
        assertEquals(1, parsed.candidates.size)
        val cand = parsed.candidates.first()
        assertEquals("https://hls.example.com/master.m3u8", cand.url)
        assertEquals("https://zokoanime.video/", cand.referer)
        assertEquals("https://subs.example.com/en.vtt", cand.subtitleUrl)
        assertTrue(cand.format == "hls")
    }
}
