package com.anigooli.downloads

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.file.Files

class PreBufferManagerTest {

    @Test
    fun `supported prebuffer options span 500 MB to 4 GB and clamp safely to free memory`() {
        assertEquals(listOf(500, 1024, 1536, 2048, 3072, 4096), PreBufferPolicy.supportedOptionsMb)
        assertEquals("1 GB", PreBufferPolicy.formatMbLabel(1024))
        assertEquals("1.5 GB", PreBufferPolicy.formatMbLabel(1536))
        assertEquals("4 GB", PreBufferPolicy.formatMbLabel(4096))

        val hugeRam = 50L * 1024 * 1024 * 1024
        val cap4Gb = PreBufferPolicy.computeEffectiveCapBytes(4096, hugeRam)
        assertEquals(4096L * 1024 * 1024, cap4Gb)

        val smallRam = 1200L * 1024 * 1024
        val clamped = PreBufferPolicy.computeEffectiveCapBytes(4096, smallRam)
        assertEquals(700L * 1024 * 1024, clamped)

        assertTrue(PreBufferPolicy.computeSafeRamBufferMb(128) in 32..96)
        assertEquals(96, PreBufferPolicy.computeSafeRamBufferMb(2048))
    }

    @Test
    fun `single-slot eviction removes previous video cache from RAM and exports reusable segments to download`() {
        val dlTarget = Files.createTempDirectory("anigooli_dl_test").toFile()
        try {
            val store = InMemoryPreBufferStore()
            val slot1 = "101_1_1080p_JAPANESE"
            val segUrl1 = "https://cdn.example/ep1/seg1.ts"
            store.putSegment(slot1, segUrl1, ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)))
            store.writeMeta(
                slot1,
                PreBufferSlotMeta(
                    streamUrl = "https://cdn.example/ep1/master.m3u8",
                    isHls = true,
                    segmentUrls = listOf(segUrl1, "https://cdn.example/ep1/seg2.ts"),
                    completedSegmentUrls = setOf(segUrl1),
                    isComplete = false,
                ),
            )

            // Switching to episode 2 evicts episode 1 from RAM
            val slot2 = "101_2_1080p_JAPANESE"
            store.evictAllExcept(slot2)
            assertFalse(store.containsSlot(slot1))

            val segUrl2 = "https://cdn.example/ep2/seg1.ts"
            val payload = byteArrayOf(5, 6, 7, 8, 9)
            val direct = store.allocateDirectSafely(slot2, payload, 1024L * 1024L)!!
            store.putSegment(slot2, segUrl2, direct)
            store.writeMeta(
                slot2,
                PreBufferSlotMeta(
                    streamUrl = "https://cdn.example/ep2/master.m3u8",
                    isHls = true,
                    segmentUrls = listOf(segUrl2),
                    completedSegmentUrls = setOf(segUrl2),
                    isComplete = true,
                ),
            )

            val export = store.exportTo(slot2, "https://cdn.example/ep2/master.m3u8", dlTarget)
            assertTrue(export.hasReusableData)
            assertTrue(export.isFullyPreBuffered)
            assertArrayEquals(payload, export.exportedSegmentsByUrl[segUrl2]?.readBytes())
        } finally {
            dlTarget.deleteRecursively()
        }
    }

    @Test
    fun `LRU budget eviction keeps previous slot in RAM while within cap and deletes it once exceeded`() {
        val store = InMemoryPreBufferStore()
        val capBytes = 1000L

        val slot1 = "101_1_1080p_JAPANESE"
        val chunk1 = store.allocateDirectSafely(slot1, ByteArray(800), capBytes)!!
        store.appendMp4Chunk(slot1, chunk1)
        store.touchSlot(slot1)

        // Ensure slot2 is newer by advancing at least 1ms
        Thread.sleep(2)
        val slot2 = "101_2_1080p_JAPANESE"
        val chunk2 = store.allocateDirectSafely(slot2, ByteArray(150), capBytes)!!
        store.appendMp4Chunk(slot2, chunk2)
        assertTrue(store.containsSlot(slot1))

        // Video 2 needs 200 more bytes (950 + 200 = 1150 > 1000): slot1 is evicted from RAM
        val chunk3 = store.allocateDirectSafely(slot2, ByteArray(200), capBytes)!!
        store.appendMp4Chunk(slot2, chunk3)
        assertFalse(store.containsSlot(slot1))
        assertTrue(store.containsSlot(slot2))
        assertEquals(350L, store.slotSizeBytes(slot2))
    }

    @Test
    fun `parseMediaPlaylist extracts EXT-X-KEY URLs and duration and trimToCachedPlaylist resolves keys`() {
        val mediaPlaylist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXT-X-KEY:METHOD=AES-128,URI="keys/enc.key"
            #EXTINF:6.0,
            seg0.ts
            #EXTINF:6.0,
            seg1.ts
            #EXTINF:6.0,
            seg2.ts
        """.trimIndent()
        val baseUrl = "https://cdn.example.com/hls/index.m3u8"
        val parsed = HlsPlaylistParser.parseMediaPlaylist(mediaPlaylist, baseUrl)
        assertEquals(listOf("https://cdn.example.com/hls/keys/enc.key"), parsed.keyUrls)
        assertEquals(18_000L, parsed.totalDurationMs)

        val cached = setOf("https://cdn.example.com/hls/seg0.ts", "https://cdn.example.com/hls/seg1.ts")
        val trimmed = HlsPlaylistParser.trimToCachedPlaylist(
            playlistText = mediaPlaylist,
            baseUrl = baseUrl,
            isVariantCached = { false },
            isSegmentCached = { it in cached },
        )
        assertTrue(trimmed.contains("URI=\"https://cdn.example.com/hls/keys/enc.key\""))
        assertTrue(trimmed.contains("https://cdn.example.com/hls/seg0.ts"))
        assertTrue(trimmed.contains("https://cdn.example.com/hls/seg1.ts"))
        assertFalse(trimmed.contains("seg2.ts"))
        assertTrue(trimmed.trimEnd().endsWith("#EXT-X-ENDLIST"))

        val noneCached = HlsPlaylistParser.trimToCachedPlaylist(
            playlistText = mediaPlaylist,
            baseUrl = baseUrl,
            isVariantCached = { false },
            isSegmentCached = { false },
        )
        assertEquals("", noneCached)
    }

    @Test
    fun `isSameStream matches URLs with rotating query tokens`() {
        assertTrue(
            PreBufferPolicy.isSameStream(
                "https://cdn.example.com/video/master.m3u8?token=abc&exp=1",
                "https://cdn.example.com/video/master.m3u8?token=xyz&exp=2",
            )
        )
        assertFalse(
            PreBufferPolicy.isSameStream(
                "https://cdn.example.com/video/ep1.mp4",
                "https://cdn.example.com/video/ep2.mp4",
            )
        )
    }

    @Test
    fun `hasVerifiedCompleteCache requires actual buffers in RAM`() {
        val store = InMemoryPreBufferStore()
        val slot = "202_1_1080p_JAPANESE"
        val streamUrl = "https://cdn.example.com/ep1.mp4"
        store.writeMeta(
            slot,
            PreBufferSlotMeta(
                streamUrl = streamUrl,
                isHls = false,
                mp4BytesBuffered = 100L,
                mp4TotalBytes = 100L,
                isComplete = true,
            ),
        )
        assertEquals(null, store.hasVerifiedCompleteCache(slot))

        store.appendMp4Chunk(slot, ByteBuffer.wrap(ByteArray(100)))
        assertTrue(store.hasVerifiedCompleteCache(slot) != null)
    }
}
