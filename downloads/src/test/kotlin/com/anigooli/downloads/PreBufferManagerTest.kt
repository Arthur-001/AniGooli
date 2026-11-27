package com.anigooli.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PreBufferManagerTest {

    @Test
    fun `supported prebuffer options span 500 MB to 4 GB and clamp safely to free disk`() {
        assertEquals(listOf(500, 1024, 1536, 2048, 3072, 4096), PreBufferPolicy.supportedOptionsMb)
        assertEquals("1 GB", PreBufferPolicy.formatMbLabel(1024))
        assertEquals("1.5 GB", PreBufferPolicy.formatMbLabel(1536))
        assertEquals("4 GB", PreBufferPolicy.formatMbLabel(4096))

        val hugeDisk = 50L * 1024 * 1024 * 1024
        val cap4Gb = PreBufferPolicy.computeEffectiveCapBytes(4096, hugeDisk)
        assertEquals(4096L * 1024 * 1024, cap4Gb)

        val smallDisk = 1200L * 1024 * 1024
        val clamped = PreBufferPolicy.computeEffectiveCapBytes(4096, smallDisk)
        assertEquals(700L * 1024 * 1024, clamped)

        assertTrue(PreBufferPolicy.computeSafeRamBufferMb(128) in 32..96)
        assertEquals(96, PreBufferPolicy.computeSafeRamBufferMb(2048))
    }

    @Test
    fun `single-slot eviction removes previous video cache and exports reusable segments to download`() {
        val root = Files.createTempDirectory("anigooli_pb_test").toFile()
        val dlTarget = Files.createTempDirectory("anigooli_dl_test").toFile()
        try {
            val store = PreBufferSlotStore(root)
            val slot1 = "101_1_1080p_JAPANESE"
            val segUrl1 = "https://cdn.example/ep1/seg1.ts"
            store.segmentFile(slot1, segUrl1).writeBytes(byteArrayOf(1, 2, 3, 4))
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

            // Switching to episode 2 evicts episode 1 so at most 1 video occupies pre-buffer
            val slot2 = "101_2_1080p_JAPANESE"
            store.evictAllExcept(slot2)
            assertFalse(File(root, slot1).exists())

            val segUrl2 = "https://cdn.example/ep2/seg1.ts"
            store.segmentFile(slot2, segUrl2).writeBytes(byteArrayOf(5, 6, 7, 8, 9))
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
            assertEquals(5L, export.exportedSegmentsByUrl[segUrl2]?.length())
        } finally {
            root.deleteRecursively()
            dlTarget.deleteRecursively()
        }
    }

    @Test
    fun `LRU budget eviction keeps previous slot while within cap and deletes it once exceeded`() {
        val root = Files.createTempDirectory("anigooli_pb_lru").toFile()
        try {
            val store = PreBufferSlotStore(root)
            val capBytes = 1000L

            // Video 1 uses 800 bytes
            val slot1 = "101_1_1080p_JAPANESE"
            store.mp4PartialFile(slot1).writeBytes(ByteArray(800))
            store.touchSlot(slot1)

            // Video 2 starts with 150 bytes (total = 950 <= 1000): slot1 stays cached
            val slot2 = "101_2_1080p_JAPANESE"
            assertTrue(store.ensureBudgetForIncoming(slot2, 150L, capBytes))
            store.mp4PartialFile(slot2).writeBytes(ByteArray(150))
            assertTrue(File(root, slot1).exists())

            // Video 2 needs 200 more bytes (950 + 200 = 1150 > 1000): slot1 is evicted so slot2 can continue
            assertTrue(store.ensureBudgetForIncoming(slot2, 200L, capBytes))
            assertFalse(File(root, slot1).exists())
            assertTrue(File(root, slot2).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `trimToCachedPlaylist stops at first uncached segment and appends ENDLIST`() {
        val mediaPlaylist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            seg0.ts
            #EXTINF:6.0,
            seg1.ts
            #EXTINF:6.0,
            seg2.ts
        """.trimIndent()
        val cached = setOf("https://cdn.example.com/hls/seg0.ts", "https://cdn.example.com/hls/seg1.ts")
        val trimmed = HlsPlaylistParser.trimToCachedPlaylist(
            playlistText = mediaPlaylist,
            baseUrl = "https://cdn.example.com/hls/index.m3u8",
            isVariantCached = { false },
            isSegmentCached = { it in cached },
        )
        assertTrue(trimmed.contains("https://cdn.example.com/hls/seg0.ts"))
        assertTrue(trimmed.contains("https://cdn.example.com/hls/seg1.ts"))
        assertFalse(trimmed.contains("seg2.ts"))
        assertTrue(trimmed.trimEnd().endsWith("#EXT-X-ENDLIST"))
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
    fun `hasVerifiedCompleteCache requires actual files on disk`() {
        val root = Files.createTempDirectory("anigooli_pb_verify").toFile()
        try {
            val store = PreBufferSlotStore(root)
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
                )
            )
            // Missing mp4_partial.bin on disk -> not verified
            assertEquals(null, store.hasVerifiedCompleteCache(slot))

            store.mp4PartialFile(slot).writeBytes(ByteArray(100))
            assertTrue(store.hasVerifiedCompleteCache(slot) != null)
        } finally {
            root.deleteRecursively()
        }
    }
}
