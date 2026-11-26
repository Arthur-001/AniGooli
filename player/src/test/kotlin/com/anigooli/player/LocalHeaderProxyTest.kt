package com.anigooli.player

import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class LocalHeaderProxyTest {

    @Test
    fun `registerCombinedHlsStream serves master playlist with audio and video tracks`() {
        val proxy = LocalHeaderProxy(OkHttpClient())
        val masterUrl = proxy.registerCombinedHlsStream(
            videoUrl = "https://cdn.example.com/video/1080p.m3u8",
            audioUrl = "https://cdn.example.com/audio/jpn.m3u8",
            referer = "https://miruro.cx/",
            origin = "https://miruro.cx",
        )

        val conn = URL(masterUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        assertTrue(masterUrl.endsWith(".m3u8"))
        assertTrue(body.contains("#EXTM3U"))
        assertTrue(body.contains("#EXT-X-MEDIA:TYPE=AUDIO"))
        assertTrue(body.contains("DEFAULT=YES"))
        assertTrue(body.contains("#EXT-X-STREAM-INF:"))
        assertTrue(body.contains("/stream/"))
    }
}
