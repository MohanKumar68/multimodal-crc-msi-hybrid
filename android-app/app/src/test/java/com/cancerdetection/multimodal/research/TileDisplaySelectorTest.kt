package com.cancerdetection.multimodal.research

import com.cancerdetection.multimodal.research.domain.model.SpecimenCategory
import com.cancerdetection.multimodal.research.domain.model.SpecimenImage
import com.cancerdetection.multimodal.research.ui.screens.findings.AttentionViewFilter
import com.cancerdetection.multimodal.research.ui.screens.findings.TileAttentionItem
import com.cancerdetection.multimodal.research.ui.screens.findings.filterTileAttentionItems
import com.cancerdetection.multimodal.research.ui.screens.findings.shouldShowHeatmapButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TileDisplaySelectorTest {

    private fun generateTestTiles(count: Int): List<TileAttentionItem> {
        // Generate weights where index 0 has highest weight, decreasing monotonically
        val rawWeights = (0 until count).map { i -> 1.0f / (i + 1) }
        val sum = rawWeights.sum()
        val normalizedWeights = rawWeights.map { it / sum }

        return normalizedWeights.mapIndexed { idx, w ->
            TileAttentionItem(
                originalIndex = idx,
                image = SpecimenImage(
                    id = "img_$idx",
                    uriString = "content://media/tile_$idx.png",
                    displayName = "tile_$idx.png",
                    category = SpecimenCategory.ORDINARY_IMAGE,
                    fileSizeBytes = 50000L,
                    mimeType = "image/png"
                ),
                weight = w,
                rank = idx + 1
            )
        }
    }

    @Test
    fun testSmallCohort8Tiles_Top5FilterReturnsExactly5Tiles() {
        val tiles = generateTestTiles(8)
        assertEquals(8, tiles.size)

        val filtered = filterTileAttentionItems(tiles, AttentionViewFilter.TOP_5)
        assertEquals(5, filtered.size)
        assertEquals(listOf(1, 2, 3, 4, 5), filtered.map { it.rank })
    }

    @Test
    fun testSmallCohort8Tiles_Top10FilterReturnsAll8TilesSafely() {
        val tiles = generateTestTiles(8)
        val filtered = filterTileAttentionItems(tiles, AttentionViewFilter.TOP_10)

        // When cohort has 8 tiles, TOP_10 gracefully takes up to 8 tiles without error
        assertEquals(8, filtered.size)
        assertEquals((1..8).toList(), filtered.map { it.rank })
    }

    @Test
    fun testSmallCohort8Tiles_AllFilterReturnsAll8Tiles() {
        val tiles = generateTestTiles(8)
        val filtered = filterTileAttentionItems(tiles, AttentionViewFilter.ALL)

        assertEquals(8, filtered.size)
        assertEquals((1..8).toList(), filtered.map { it.rank })
    }

    @Test
    fun testSmallCohort8Tiles_HeatmapButtonAlwaysVisible() {
        // For small cohorts (<= 10 tiles), ViT heatmap buttons are visible for all tiles
        // regardless of filter and weight to avoid unnecessary user clicks
        val tileCount = 8
        val lowWeight = 0.002f // 0.2% attention

        assertTrue(
            "Heatmap button should be visible in TOP_5 for 8-tile cohort",
            shouldShowHeatmapButton(AttentionViewFilter.TOP_5, tileCount, lowWeight, showAllHeatmaps = false)
        )
        assertTrue(
            "Heatmap button should be visible in TOP_10 for 8-tile cohort",
            shouldShowHeatmapButton(AttentionViewFilter.TOP_10, tileCount, lowWeight, showAllHeatmaps = false)
        )
        assertTrue(
            "Heatmap button should be visible in ALL for 8-tile cohort",
            shouldShowHeatmapButton(AttentionViewFilter.ALL, tileCount, lowWeight, showAllHeatmaps = false)
        )
    }

    @Test
    fun testLargeCohort269Tiles_Top5Returns5_Top10Returns10_AllReturns269() {
        val tiles = generateTestTiles(269)
        assertEquals(269, tiles.size)

        val top5 = filterTileAttentionItems(tiles, AttentionViewFilter.TOP_5)
        assertEquals(5, top5.size)
        assertEquals(listOf(1, 2, 3, 4, 5), top5.map { it.rank })

        val top10 = filterTileAttentionItems(tiles, AttentionViewFilter.TOP_10)
        assertEquals(10, top10.size)
        assertEquals((1..10).toList(), top10.map { it.rank })

        val all = filterTileAttentionItems(tiles, AttentionViewFilter.ALL)
        assertEquals(269, all.size)
    }

    @Test
    fun testLargeCohort269Tiles_HeatmapClutterFilteringAndToggle() {
        val tileCount = 269
        val highWeight = 0.045f // 4.5%
        val lowWeight = 0.0015f // 0.15%

        // In Top 5 and Top 10, heatmap buttons are always visible for the focused tiles
        assertTrue(shouldShowHeatmapButton(AttentionViewFilter.TOP_5, tileCount, highWeight, false))
        assertTrue(shouldShowHeatmapButton(AttentionViewFilter.TOP_5, tileCount, lowWeight, false))
        assertTrue(shouldShowHeatmapButton(AttentionViewFilter.TOP_10, tileCount, highWeight, false))
        assertTrue(shouldShowHeatmapButton(AttentionViewFilter.TOP_10, tileCount, lowWeight, false))

        // In ALL view:
        // High attention tile has button visible by default
        assertTrue(shouldShowHeatmapButton(AttentionViewFilter.ALL, tileCount, highWeight, false))
        // Low attention tile has button hidden by default to prevent clutter
        assertFalse(shouldShowHeatmapButton(AttentionViewFilter.ALL, tileCount, lowWeight, false))
        // When user toggles 'showAllHeatmaps = true', low attention tile button becomes visible
        assertTrue(shouldShowHeatmapButton(AttentionViewFilter.ALL, tileCount, lowWeight, true))
    }

    @Test
    fun testVerySmallCohort3Tiles_GracefulHandling() {
        val tiles = generateTestTiles(3)

        val top5 = filterTileAttentionItems(tiles, AttentionViewFilter.TOP_5)
        assertEquals(3, top5.size)

        val top10 = filterTileAttentionItems(tiles, AttentionViewFilter.TOP_10)
        assertEquals(3, top10.size)

        val all = filterTileAttentionItems(tiles, AttentionViewFilter.ALL)
        assertEquals(3, all.size)
    }

    @Test
    fun testRankSortingVsInputOrderSorting() {
        // Scramble initial input order
        val scrambledTiles = listOf(
            TileAttentionItem(originalIndex = 0, image = null, weight = 0.10f, rank = 3),
            TileAttentionItem(originalIndex = 1, image = null, weight = 0.60f, rank = 1),
            TileAttentionItem(originalIndex = 2, image = null, weight = 0.30f, rank = 2)
        )

        val sortedByRank = scrambledTiles.sortedBy { it.rank }
        assertEquals(listOf(1, 2, 0), sortedByRank.map { it.originalIndex })
        assertEquals(listOf(1, 2, 3), sortedByRank.map { it.rank })

        val sortedByInput = scrambledTiles.sortedBy { it.originalIndex }
        assertEquals(listOf(0, 1, 2), sortedByInput.map { it.originalIndex })
        assertEquals(listOf(3, 1, 2), sortedByInput.map { it.rank })
    }
}
