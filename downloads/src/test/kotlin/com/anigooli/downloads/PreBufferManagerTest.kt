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
}
