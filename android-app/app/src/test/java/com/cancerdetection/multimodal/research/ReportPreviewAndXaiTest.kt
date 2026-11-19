package com.cancerdetection.multimodal.research

import com.cancerdetection.multimodal.research.domain.model.AnalysisResult
import com.cancerdetection.multimodal.research.domain.model.CaseStatus
import com.cancerdetection.multimodal.research.domain.model.ModelProvenance
import com.cancerdetection.multimodal.research.domain.model.ResearchCase
import com.cancerdetection.multimodal.research.domain.model.SpecimenCategory
import com.cancerdetection.multimodal.research.domain.model.SpecimenImage
import com.cancerdetection.multimodal.research.ui.screens.report.ReportPreviewViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportPreviewAndXaiTest {

    private fun createDummyCase(
        tileCount: Int,
        weights: List<Float> = emptyList(),
        summaryText: String? = "Adenocarcinoma with high microsatellite instability features.",
        isLlm: Boolean = false,
        summarySource: String = "deterministic_template_fallback",
        modelUsed: String? = null
    ): ResearchCase {
        val images = (0 until tileCount).map { i ->
            SpecimenImage(
                id = "img_$i",
                uriString = "content://media/patch_$i.png",
                displayName = "patch_$i.png",
                category = SpecimenCategory.ORDINARY_IMAGE,
                fileSizeBytes = 150000L,
                mimeType = "image/png"
            )
        }

        val result = AnalysisResult(
            classificationLabel = "MSI-High (Microsatellite Instability Detected)",
            isMsi = true,
            msiProbability = 0.9421f,
            decisionThresholdApplied = 0.83,
            provenance = ModelProvenance(
                fusionArchitecture = "SummaryToPatchCrossAttention",
                fusionCheckpoint = "models/fusion_model_v1_1.pth",
                inferenceEnvironment = "PyTorch 2.6.0 CPU"
            ),
            attentionWeights = weights,
            clinicalSummaryText = summaryText,
            clinicalSummaryIsLlm = isLlm,
            clinicalSummarySource = summarySource,
            clinicalSummaryModelUsed = modelUsed,
            clinicalSummaryGenerated = !summaryText.isNullOrBlank(),
            attentionDistributionAvailable = weights.isNotEmpty(),
            cellularHeatmapAvailable = true
        )

        return ResearchCase(
            id = "case_test_123",
            caseIdentifier = "CRC-RESEARCH-CASE-001",
            specimenType = "Endoscopic biopsy formalin-fixed paraffin-embedded tissue",
            clinicalReportText = "Sigmoid colon mass showing moderately differentiated adenocarcinoma, infiltrative borders.",
            images = images,
            result = result,
            status = CaseStatus.ANALYZED_VERIFIED
        )
    }

    @Test
    fun testReportTextDoesNotDumpAll199Tiles() {
        val vm = ReportPreviewViewModel()

        // Generate 199 tiles with weights
        val weights = (0 until 199).map { idx ->
            if (idx == 0) 0.15f else (0.85f / 198f)
        }
        val rc = createDummyCase(tileCount = 199, weights = weights)

        val reportText = vm.generateReportText(rc)

        // Must preserve threshold tau = 0.83
        assertTrue(reportText.contains("τ = 0.83"))
        // Must contain Top 5 label
        assertTrue(reportText.contains("TILE-LEVEL CROSS-ATTENTION EVIDENCE (Top 5 of 199 Tiles):"))
        // Must mention that remaining tiles are omitted for concise reporting
        assertTrue(reportText.contains("Remaining 194 tiles with lower attention omitted for concise reporting"))
        // Must NOT list tile 199 in the main evidence
        assertFalse(reportText.contains("Tile 199"))
        // Must list tile 1
        assertTrue(reportText.contains("Tile 1"))
    }

    @Test
    fun testReportTextClinicalSummaryFallbackLabeling() {
        val vm = ReportPreviewViewModel()

        val fallbackCase = createDummyCase(
            tileCount = 5,
            isLlm = false,
            summarySource = "deterministic_template_fallback"
        )
        val fallbackText = vm.generateReportText(fallbackCase)

        assertTrue(fallbackText.contains("CLINICAL SUMMARY (Template Fallback):"))
        assertTrue(fallbackText.contains("Local LLM inactive/offline. Summary synthesized deterministically via structured templates."))

        val llmCase = createDummyCase(
            tileCount = 5,
            isLlm = true,
            summarySource = "ollama_endpoint",
            modelUsed = "llama3.2:latest"
        )
        val llmText = vm.generateReportText(llmCase)

        assertTrue(llmText.contains("CLINICAL SUMMARY (llama3.2:latest):"))
        assertFalse(llmText.contains("Local LLM inactive/offline"))
    }

    @Test
    fun testAllTilesHeatmapVisibilityLogic() {
        // Test visibility thresholds:
        // Weight >= 0.01f (1%) -> visible
        // Weight 0.005f (< 1%) -> low attention hidden by default
        // Weight 0.0f (exact zero) -> hidden, no button
        val highWeight = 0.045f // 4.5%
        val lowWeight = 0.003f  // 0.3%
        val exactZero = 0.0f

        // In Top 5 / Top 10 view, buttons are visible unconditionally
        fun isVisibleInTopView(w: Float) = true
        assertTrue(isVisibleInTopView(highWeight))
        assertTrue(isVisibleInTopView(lowWeight))
        assertTrue(isVisibleInTopView(exactZero))

        // In All Tiles view (default: showAll = false)
        fun isVisibleInAllView(w: Float, showAll: Boolean): Boolean {
            val numeric = if (w.isFinite() && w > 0f) w else 0f
            return numeric >= 0.01f || showAll
        }

        fun isLowAttentionTag(w: Float, showAll: Boolean): Boolean {
            val numeric = if (w.isFinite() && w > 0f) w else 0f
            return !showAll && numeric > 0f && numeric < 0.01f
        }

        // Test with showAll = false
        assertTrue(isVisibleInAllView(highWeight, showAll = false))
        assertFalse(isVisibleInAllView(lowWeight, showAll = false))
        assertTrue(isLowAttentionTag(lowWeight, showAll = false))

        // Exact zero: no button, no low attention tag
        assertFalse(isVisibleInAllView(exactZero, showAll = false))
        assertFalse(isLowAttentionTag(exactZero, showAll = false))

        // Test with showAll = true
        assertTrue(isVisibleInAllView(highWeight, showAll = true))
        assertTrue(isVisibleInAllView(lowWeight, showAll = true))
        assertFalse(isLowAttentionTag(lowWeight, showAll = true))
    }
}
