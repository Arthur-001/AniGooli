package com.anigooli.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ExtractorScriptParserTest {

    @Test
    fun `upstream ani-cli parser extracts version miruro_base and agent`() {
        val raw = """
            #!/bin/sh
            version_number="5.2.0"
            miruro_base="https://miruro.cx"
            agent="Mozilla/5.0 CustomAgent"
        """.trimIndent()
        val parsed = UpstreamAniCliParser.parse(raw)
        assertNotNull(parsed)
        assertEquals("5.2.0", parsed!!.versionNumber)
        assertEquals("https://miruro.cx", parsed.miruroBase)
        assertEquals("Mozilla/5.0 CustomAgent", parsed.userAgent)
    }

    @Test
    fun `upstream parser returns null when miruro_base missing`() {
        assertNull(UpstreamAniCliParser.parse("version_number=\"5.2.0\""))
    }

    @Test
    fun `native miruro parser extracts cover url episode count season year and format`() {
        val searchHtml = """
            <script>items:[{id:"frieren-1",external_ids:{mal:["52991"],anilist:["154587"]},title:{english:"Frieren",romaji:"Sousou no Frieren"},cover_url:"https://example.com/frieren.jpg",episode_count:28,season_year:2023,format:"TV"}],entityItems:[]</script>
        """.trimIndent()
        val searchResults = NativeMiruroParser.parseSearchResponse(searchHtml)
        assertEquals(1, searchResults.size)
        assertEquals("https://example.com/frieren.jpg", searchResults[0].coverImage)
        assertEquals(28, searchResults[0].episodeCount)
        assertEquals(2023, searchResults[0].seasonYear)
        assertEquals("TV", searchResults[0].format)
    }

    @Test
    fun `native miruro parser extracts search episodes sub dub and m3u8`() {
        val searchHtml = """
            <script>items:[{id:"frieren-1",external_ids:{mal:["52991"],anilist:["154587"]},title:{english:"Frieren",romaji:"Sousou no Frieren"},format:"TV"}],entityItems:[]</script>
        """.trimIndent()
        val searchResults = NativeMiruroParser.parseSearchResponse(searchHtml)
        assertEquals(1, searchResults.size)
        assertEquals(154587, searchResults[0].anilistId)
        assertEquals("frieren-1", searchResults[0].providerId)

        val epHtml = """episodes:[{number:3},{number:1},{number:2.5}];y.number=12"""
        assertEquals(listOf("1", "2.5", "3", "12"), NativeMiruroParser.parseEpisodesResponse(epHtml))

        val mediaHtml = """
            var c=[];c[0]={language:"English",file:"https://cdn.example/en.vtt",default:true};
            tracks:[
              {track:"ssub",providers:[{provider:"kiwi",subtitles:c,servers:[{server:"VidHide",headers:{Referer:"https://ref.example/1"},streams:[{url:"https://hls.example/master.m3u8",format:"hls",quality:"auto"}]}]}]},
              {track:"dub",providers:[{provider:"zoro",servers:[{server:"Mega",Referer:"https://mega.example/",streams:[{url:"https://mp4.example/1080.mp4",format:"mp4",quality:"1080p"}]}]}]}
            ],config:{}
        """.trimIndent()

        val subRes = NativeMiruroParser.parseMediaResponse(mediaHtml, "sub")
        assertEquals(1, subRes.candidates.size)
        assertEquals("https://cdn.example/en.vtt", subRes.candidates[0].subtitleUrl)
        assertEquals("https://ref.example", subRes.candidates[0].origin)

        val dubRes = NativeMiruroParser.parseMediaResponse(mediaHtml, "dub")
        assertEquals(1, dubRes.candidates.size)
        assertEquals("1080p", dubRes.candidates[0].quality)

        val m3u8Text = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,DEFAULT=YES,URI="audio/jp.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720
            720/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1920x1080
            /1080/index.m3u8
        """.trimIndent()
        val parsedM3u8 = NativeMiruroParser.parseM3u8(m3u8Text, "https://hls.example/sub/master.m3u8")
        assertNotNull(parsedM3u8)
        assertEquals("https://hls.example/sub/audio/jp.m3u8", parsedM3u8!!.audioUrl)
        assertEquals(1080, parsedM3u8.variants[0].height)
        assertEquals("https://hls.example/1080/index.m3u8", parsedM3u8.variants[0].url)
    }
}
