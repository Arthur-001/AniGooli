package com.anigooli.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleParserTest {

    @Test
    fun `parses WebVTT cues with short and long timestamps and strips tags`() {
        val vtt = """
            WEBVTT

            00:01.500 --> 00:04.000 align:start
            <i>Hello</i>, Asta!

            00:01:05.250 --> 00:01:08.000
            Line one
            Line two
        """.trimIndent()

        val cues = SubtitleParser.parse(vtt)
        assertEquals(2, cues.size)
        assertEquals(1_500L, cues[0].startMs)
        assertEquals(4_000L, cues[0].endMs)
        assertEquals("Hello, Asta!", cues[0].text)
        assertEquals(65_250L, cues[1].startMs)
        assertEquals(listOf("Line one\nLine two"), SubtitleParser.activeCueTexts(cues, 66_000L))
    }

    @Test
    fun `parses SRT and ASS dialogue lines`() {
        val srt = """
            1
            00:00:02,000 --> 00:00:05,500
            First SRT line
        """.trimIndent()
        val srtCues = SubtitleParser.parse(srt)
        assertEquals(1, srtCues.size)
        assertEquals("First SRT line", srtCues[0].text)

        val ass = """
            [Events]
            Dialogue: 0,0:00:10.50,0:00:13.00,Default,,0,0,0,,{\an8}Magic Knight\NExam!
        """.trimIndent()
        val assCues = SubtitleParser.parse(ass)
        assertEquals(1, assCues.size)
        assertEquals(10_500L, assCues[0].startMs)
        assertEquals("Magic Knight\nExam!", assCues[0].text)
    }
}
