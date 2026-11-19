package com.cancerdetection.multimodal.research

import com.cancerdetection.multimodal.research.data.storage.SpecimenIngestionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecimenIngestionManagerTest {

    @Test
    fun testMaxSupportedTilesConstant() {
        assertEquals(100, SpecimenIngestionManager.MAX_SUPPORTED_TILES)
        assertEquals(100, SpecimenIngestionManager.MAX_SINGLE_SELECTION_TILES)
    }

    @Test
    fun testSupportedImageExtensions() {
        assertTrue(SpecimenIngestionManager.SUPPORTED_IMAGE_EXTENSIONS.contains("jpg"))
        assertTrue(SpecimenIngestionManager.SUPPORTED_IMAGE_EXTENSIONS.contains("jpeg"))
        assertTrue(SpecimenIngestionManager.SUPPORTED_IMAGE_EXTENSIONS.contains("png"))
        assertTrue(SpecimenIngestionManager.SUPPORTED_IMAGE_EXTENSIONS.contains("webp"))
        assertFalse(SpecimenIngestionManager.SUPPORTED_IMAGE_EXTENSIONS.contains("svs"))
        assertFalse(SpecimenIngestionManager.SUPPORTED_IMAGE_EXTENSIONS.contains("xml"))
        assertFalse(SpecimenIngestionManager.SUPPORTED_IMAGE_EXTENSIONS.contains("txt"))
    }

    @Test
    fun testNaturalAlphanumericSorting() {
        val filenames = listOf(
            "tile_100.jpg",
            "tile_2.jpg",
            "tile_1.jpg",
            "tile_20.jpg",
            "tile_10.jpg",
            "tile_3.png"
        )
        val sorted = filenames.sortedWith { a, b -> SpecimenIngestionManager.naturalCompare(a, b) }
        val expected = listOf(
            "tile_1.jpg",
            "tile_2.jpg",
            "tile_3.png",
            "tile_10.jpg",
            "tile_20.jpg",
            "tile_100.jpg"
        )
        assertEquals(expected, sorted)
    }

    @Test
    fun testDeterministicOrderWithTcgaPrefix() {
        val filenames = listOf(
            "TCGA-3L-AA1B_patch_9.png",
            "TCGA-3L-AA1B_patch_10.png",
            "TCGA-3L-AA1B_patch_1.png",
            "TCGA-3L-AA1B_patch_2.png"
        )
        val sorted = filenames.sortedWith { a, b -> SpecimenIngestionManager.naturalCompare(a, b) }
        val expected = listOf(
            "TCGA-3L-AA1B_patch_1.png",
            "TCGA-3L-AA1B_patch_2.png",
            "TCGA-3L-AA1B_patch_9.png",
            "TCGA-3L-AA1B_patch_10.png"
        )
        assertEquals(expected, sorted)
    }

    @Test
    fun testProcessRawFilesIngestsMoreThan100TilesWithoutCap() {
        // Generate 250 synthetic tile files: tile_1.jpg to tile_250.jpg
        val syntheticFiles = (1..250).map { i ->
            SpecimenIngestionManager.RawFileInfo(
                name = "tile_$i.jpg",
                isDirectory = false,
                uriString = "content://media/external/images/media/$i",
                mimeType = "image/jpeg",
                length = 150_000L // 150 KB
            )
        }

        val (tiles, report) = SpecimenIngestionManager.processRawFiles(syntheticFiles.asSequence(), maxTiles = null)

        // Verify that all 250 tiles are ingested without any 100-tile truncation
        assertEquals(250, tiles.size)
        assertEquals(250, report.totalScanned)
        assertEquals(250, report.validAccepted)
        assertEquals(0, report.skippedNonImages)
        assertTrue(report.unreadableFiles.isEmpty())

        // Verify deterministic natural alphanumeric sort
        assertEquals("tile_1.jpg", tiles[0].displayName)
        assertEquals("tile_2.jpg", tiles[1].displayName)
        assertEquals("tile_10.jpg", tiles[9].displayName)
        assertEquals("tile_100.jpg", tiles[99].displayName)
        assertEquals("tile_250.jpg", tiles[249].displayName)
    }

    @Test
    fun testProcessRawFilesWithExplicitCap() {
        val syntheticFiles = (1..150).map { i ->
            SpecimenIngestionManager.RawFileInfo(
                name = "patch_$i.png",
                isDirectory = false,
                uriString = "content://media/$i",
                mimeType = "image/png",
                length = 200_000L
            )
        }

        val (tiles, report) = SpecimenIngestionManager.processRawFiles(syntheticFiles.asSequence(), maxTiles = 40)
        assertEquals(40, tiles.size)
        assertEquals(40, report.validAccepted)
        assertEquals(150, report.totalScanned)
    }

    @Test
    fun testProcessRawFilesMixedFormatsAndErrorReporting() {
        val files = mutableListOf<SpecimenIngestionManager.RawFileInfo>()

        // 120 valid JPGs
        for (i in 1..120) {
            files.add(
                SpecimenIngestionManager.RawFileInfo(
                    name = "valid_$i.jpg",
                    isDirectory = false,
                    uriString = "content://images/$i",
                    mimeType = "image/jpeg",
                    length = 100_000L
                )
            )
        }

        // 10 valid PNGs
        for (i in 1..10) {
            files.add(
                SpecimenIngestionManager.RawFileInfo(
                    name = "extra_$i.png",
                    isDirectory = false,
                    uriString = "content://images/png_$i",
                    mimeType = "image/png",
                    length = 120_000L
                )
            )
        }

        // 15 whole-slide images (skipped non-images in tile folder)
        for (i in 1..15) {
            files.add(
                SpecimenIngestionManager.RawFileInfo(
                    name = "slide_$i.svs",
                    isDirectory = false,
                    uriString = "content://wsi/$i",
                    mimeType = "application/octet-stream",
                    length = 500_000_000L
                )
            )
        }

        // 5 text files (skipped non-images)
        for (i in 1..5) {
            files.add(
                SpecimenIngestionManager.RawFileInfo(
                    name = "notes_$i.txt",
                    isDirectory = false,
                    uriString = "content://docs/$i",
                    mimeType = "text/plain",
                    length = 1_000L
                )
            )
        }

        // 3 subdirectories (skipped non-images)
        for (i in 1..3) {
            files.add(
                SpecimenIngestionManager.RawFileInfo(
                    name = "subdir_$i",
                    isDirectory = true,
                    uriString = "content://dirs/$i",
                    mimeType = null,
                    length = 0L
                )
            )
        }

        // 2 empty files (0 bytes -> unreadable)
        files.add(
            SpecimenIngestionManager.RawFileInfo(
                name = "corrupt_zero.jpg",
                isDirectory = false,
                uriString = "content://images/zero",
                mimeType = "image/jpeg",
                length = 0L
            )
        )
        files.add(
            SpecimenIngestionManager.RawFileInfo(
                name = "corrupt_empty.png",
                isDirectory = false,
                uriString = "content://images/empty",
                mimeType = "image/png",
                length = -1L
            )
        )

        // 1 oversized file (> 50MB -> unreadable)
        files.add(
            SpecimenIngestionManager.RawFileInfo(
                name = "giant_tile.jpg",
                isDirectory = false,
                uriString = "content://images/giant",
                mimeType = "image/jpeg",
                length = 55 * 1024 * 1024L
            )
        )

        // 1 file with missing name -> unreadable
        files.add(
            SpecimenIngestionManager.RawFileInfo(
                name = "",
                isDirectory = false,
                uriString = "content://images/noname",
                mimeType = "image/jpeg",
                length = 50_000L
            )
        )

        // 5 duplicate filenames
        for (i in 1..5) {
            files.add(
                SpecimenIngestionManager.RawFileInfo(
                    name = "valid_$i.jpg",
                    isDirectory = false,
                    uriString = "content://images/dup_$i",
                    mimeType = "image/jpeg",
                    length = 100_000L
                )
            )
        }

        // Total scanned files = 120 + 10 + 15 + 5 + 3 + 2 + 1 + 1 + 5 = 162
        val (tiles, report) = SpecimenIngestionManager.processRawFiles(files.asSequence(), maxTiles = null)

        assertEquals(162, report.totalScanned)
        assertEquals(130, report.validAccepted) // 120 JPG + 10 PNG
        assertEquals(130, tiles.size)
        assertEquals(23, report.skippedNonImages) // 15 SVS + 5 TXT + 3 subdirs
        assertEquals(4, report.unreadableFiles.size) // 2 empty + 1 oversized + 1 empty name

        // Check specific unreadable reasons
        val unreadableMap = report.unreadableFiles.associateBy { it.filename }
        assertTrue(unreadableMap.containsKey("corrupt_zero.jpg"))
        assertTrue(unreadableMap["corrupt_zero.jpg"]!!.reason.contains("0 bytes"))
        assertTrue(unreadableMap.containsKey("giant_tile.jpg"))
        assertTrue(unreadableMap["giant_tile.jpg"]!!.reason.contains("exceeds 50MB"))
    }

    @Test
    fun testMemoryFootprintMetadataOnly() {
        // Ensure 500 tile metadata items don't hold any heavy bitmap objects
        val syntheticFiles = (1..500).map { i ->
            SpecimenIngestionManager.RawFileInfo(
                name = "stream_tile_$i.jpg",
                isDirectory = false,
                uriString = "content://stream/$i",
                mimeType = "image/jpeg",
                length = 80_000L
            )
        }

        val (tiles, _) = SpecimenIngestionManager.processRawFiles(syntheticFiles.asSequence())
        assertEquals(500, tiles.size)
        for (tile in tiles) {
            assertNotNull(tile.uriString)
            assertNotNull(tile.displayName)
            assertTrue(tile.fileSizeBytes > 0)
        }
    }
}
