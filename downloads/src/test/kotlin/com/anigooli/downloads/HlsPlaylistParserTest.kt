package com.anigooli.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistParserTest {

    @Test
    fun `parses media playlist with init map and relative urls`() {
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4.0,
            seg_001.m4s
            #EXTINF:4.0,
            /cdn/seg_002.m4s
            #EXTINF:4.0,
            //other.cdn.example/seg_003.m4s
        """.trimIndent()

        val parsed = HlsPlaylistParser.parseMediaPlaylist(playlist, "https://hls.example/video/1080p/index.m3u8?auth=abc")
        assertFalse(parsed.isMasterPlaylist)
        assertEquals("https://hls.example/video/1080p/init.mp4", parsed.initSegmentUrl)
        assertEquals(
            listOf(
                "https://hls.example/video/1080p/seg_001.m4s",
                "https://hls.example/cdn/seg_002.m4s",
                "https://other.cdn.example/seg_003.m4s",
            ),
            parsed.segmentUrls,
        )
    }

    @Test
    fun `detects master playlist cleanly`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080
            1080/prog.m3u8
        """.trimIndent()
        val parsed = HlsPlaylistParser.parseMediaPlaylist(master, "https://hls.example/master.m3u8")
        assertTrue(parsed.isMasterPlaylist)
        assertEquals(listOf("https://hls.example/1080/prog.m3u8"), parsed.segmentUrls)
    }
}
