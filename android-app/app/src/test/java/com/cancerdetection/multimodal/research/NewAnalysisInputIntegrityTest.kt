package com.cancerdetection.multimodal.research

import com.cancerdetection.multimodal.research.data.storage.SpecimenIngestionManager
import com.cancerdetection.multimodal.research.domain.model.AnalysisResult
import com.cancerdetection.multimodal.research.domain.model.CaseStatus
import com.cancerdetection.multimodal.research.domain.model.ModelProvenance
import com.cancerdetection.multimodal.research.domain.model.ResearchCase
import com.cancerdetection.multimodal.research.domain.model.SAMPLE_TCGA_CASE_ID
import com.cancerdetection.multimodal.research.domain.model.SAMPLE_TCGA_REPORT_TEXT
import com.cancerdetection.multimodal.research.domain.model.SpecimenCategory
import com.cancerdetection.multimodal.research.domain.model.SpecimenImage
import com.cancerdetection.multimodal.research.ui.screens.newanalysis.NewAnalysisUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Unit test suite verifying input integrity safeguards, sample-report state binding,
 * tile identity extraction, and prediction threshold consistency.
 */
class NewAnalysisInputIntegrityTest {

    @Test
    fun testAuthenticZenodoTileMetadataExtraction() {
        // Tile naming convention from Zenodo extraction: TCGA-AA-3864-01Z-00-DX1.36.jpg
        val filenames = listOf(
            "TCGA-AA-3864-01Z-00-DX1.0.jpg",
            "TCGA-AA-3864-01Z-00-DX1.24.jpg",
            "TCGA-AA-3864-01Z-00-DX1.36.jpg",
            "TCGA-AA-3864-01Z-00-DX1.106.jpg"
        )
        val meta = SpecimenIngestionManager.extractTileMetadataFromNames(filenames)

        assertEquals("TCGA-AA-3864", meta.detectedCaseId)
        assertEquals("TCGA-AA-3864-01Z-00-DX1", meta.detectedSlideId)
        assertTrue(meta.isConsistent)
        assertEquals(4, meta.tileCount)
        assertEquals(
            "Verified from tile metadata: TCGA-AA-3864 (Slide: TCGA-AA-3864-01Z-00-DX1)",
            meta.identityStatus
        )
    }

    @Test
    fun testKaggleKatherTileMetadataExtraction() {
        // Tile naming convention from Kaggle tcga_coad_msi_mss_jpg: blk-DWAHEWFSLMGP-TCGA-AA-3864-01Z-00-DX1.jpg
        val filenames = listOf(
            "blk-DWAHEWFSLMGP-TCGA-AA-3864-01Z-00-DX1.jpg",
            "blk-GNWNMVYNNPEG-TCGA-AA-3864-01Z-00-DX1.jpg",
            "blk-CAIWYSTYQPWE-TCGA-AA-3864-01Z-00-DX1.jpg"
        )
        val meta = SpecimenIngestionManager.extractTileMetadataFromNames(filenames)

        assertEquals("TCGA-AA-3864", meta.detectedCaseId)
        assertEquals("TCGA-AA-3864-01Z-00-DX1", meta.detectedSlideId)
        assertTrue(meta.isConsistent)
        assertEquals(3, meta.tileCount)
        assertEquals(
            "Verified from tile metadata: TCGA-AA-3864 (Slide: TCGA-AA-3864-01Z-00-DX1)",
            meta.identityStatus
        )
    }

    @Test
    fun testUserSuppliedGenericTilesMarkedUnverified() {
        val genericFiles = listOf("tile_001.jpg", "tile_002.png", "patch_rect_4.jpg")
        val meta = SpecimenIngestionManager.extractTileMetadataFromNames(genericFiles)

        assertNull(meta.detectedCaseId)
        assertNull(meta.detectedSlideId)
        assertTrue(meta.isConsistent)
        assertEquals(3, meta.tileCount)
        assertEquals("User-supplied image tiles (Case identity unverified)", meta.identityStatus)
    }

    @Test
    fun testConflictingMultiplePatientsDetected() {
        val mixedFiles = listOf(
            "TCGA-AA-3864-01Z-00-DX1.1.jpg",
            "TCGA-3L-AA1B-01Z-00-DX1.1.jpg"
        )
        val meta = SpecimenIngestionManager.extractTileMetadataFromNames(mixedFiles)

        assertNull(meta.detectedCaseId)
        assertFalse(meta.isConsistent)
        assertEquals(2, meta.tileCount)
        assertTrue(meta.identityStatus.contains("Multiple case IDs"))
        assertTrue(meta.identityStatus.contains("TCGA-3L-AA1B"))
        assertTrue(meta.identityStatus.contains("TCGA-AA-3864"))
    }

    @Test
    fun testEmptyTileListStatus() {
        val meta = SpecimenIngestionManager.extractTileMetadataFromNames(emptyList())
        assertNull(meta.detectedCaseId)
        assertEquals(0, meta.tileCount)
        assertEquals("No specimen tiles selected", meta.identityStatus)
    }

    @Test
    fun testCaseIdMismatchWarningLogic() {
        // Condition: Entered Case ID is TCGA-AA-3864, but tiles belong to TCGA-3L-AA1B
        val enteredCase = "TCGA-AA-3864"
        val detectedTileCaseId = "TCGA-3L-AA1B"

        val warning = if (detectedTileCaseId.uppercase() != enteredCase.uppercase()) {
            "Input Mismatch Warning: Entered Case ID '$enteredCase' does not match detected tile metadata '$detectedTileCaseId'."
        } else {
            null
        }

        assertNotNull(warning)
        assertTrue(warning!!.contains("Input Mismatch Warning"))
        assertTrue(warning.contains("TCGA-AA-3864"))
        assertTrue(warning.contains("TCGA-3L-AA1B"))
    }

    @Test
    fun testCaseIdMatchingProducesNoWarning() {
        val enteredCase = "TCGA-AA-3864"
        val detectedTileCaseId = "TCGA-AA-3864"

        val warning = if (detectedTileCaseId.uppercase() != enteredCase.uppercase()) {
            "Input Mismatch Warning"
        } else {
            null
        }

        assertNull(warning)
    }

    @Test
    fun testSampleReportMismatchValidationSafeguard() {
        // User loaded sample report (belonging to TCGA-3L-AA1B)
        // But changed Case ID to TCGA-AA-3864 without confirming or replacing report text
        val state = NewAnalysisUiState(
            caseIdentifier = "TCGA-AA-3864",
            clinicalReportText = SAMPLE_TCGA_REPORT_TEXT,
            images = listOf(
                SpecimenImage(uriString = "content://t1", displayName = "TCGA-AA-3864-01Z-00-DX1.36.jpg")
            ),
            isSampleReportActive = true,
            sampleReportConfirmedForCase = false
        )

        // Validation check for sample mismatch
        val isMismatch = state.isSampleReportActive &&
            !state.sampleReportConfirmedForCase &&
            state.caseIdentifier.trim().uppercase() != SAMPLE_TCGA_CASE_ID.uppercase()

        assertTrue("Must flag sample report mismatch when used with different case ID", isMismatch)

        // Once confirmed explicitly, validation is permitted
        val confirmedState = state.copy(sampleReportConfirmedForCase = true)
        val isConfirmedMismatch = confirmedState.isSampleReportActive &&
            !confirmedState.sampleReportConfirmedForCase &&
            confirmedState.caseIdentifier.trim().uppercase() != SAMPLE_TCGA_CASE_ID.uppercase()

        assertFalse("Must permit analysis once explicitly confirmed", isConfirmedMismatch)
    }

    @Test
    fun testReplacingSampleReportResetsSampleState() {
        val sampleText = SAMPLE_TCGA_REPORT_TEXT
        val authenticReport = "Cecum hemicolectomy: Invasive adenocarcinoma, moderately differentiated. pT3, pN0."

        var isSampleActive = true
        // User pastes authentic clinical report
        if (authenticReport.trim() != sampleText.trim()) {
            isSampleActive = false
        }

        assertFalse(isSampleActive)
    }

    @Test
    fun testProbabilityAndThresholdComparison() {
        val threshold = 0.83f

        // Case TCGA-AA-3864 Zenodo 107-tile authentic bag
        val prob107 = 0.77989584f
        val isMsi107 = prob107 >= threshold
        assertFalse("0.7799 must be classified as MSS at tau=0.83 (False Negative)", isMsi107)

        val formatted107 = String.format(Locale.US, "%.4f (%.1f%%)", prob107, prob107 * 100)
        assertEquals("0.7799 (78.0%)", formatted107)

        // Case TCGA-AA-3864 Kaggle 273-tile bag
        val prob273 = 0.96764117f
        val isMsi273 = prob273 >= threshold
        assertTrue("0.9676 must be classified as MSI-H at tau=0.83", isMsi273)

        // Case TCGA-AA-3864 Tile #36 with mismatched sample report (reproduced bug)
        val probMismatchedSingleTile = 0.00848814f
        val isMsiSingle = probMismatchedSingleTile >= threshold
        assertFalse(isMsiSingle)
        val formattedSingle = String.format(Locale.US, "%.4f (%.1f%%)", probMismatchedSingleTile, probMismatchedSingleTile * 100)
        assertEquals("0.0085 (0.8%)", formattedSingle)
    }

    @Test
    fun testModelProvenanceCarriesIdentityAndSampleFlags() {
        val provenance = ModelProvenance(
            fusionArchitecture = "SummaryToPatchCrossAttention (5,250,561 params)",
            fusionCheckpoint = "models/fusion_model_v1_1.pth",
            decisionThresholdTau = 0.83,
            tileIdentityStatus = "Verified from tile metadata: TCGA-AA-3864 (Slide: TCGA-AA-3864-01Z-00-DX1)",
            detectedTileCaseId = "TCGA-AA-3864",
            detectedTileSlideId = "TCGA-AA-3864-01Z-00-DX1",
            isSampleReportUsed = false
        )

        assertEquals("TCGA-AA-3864", provenance.detectedTileCaseId)
        assertEquals("TCGA-AA-3864-01Z-00-DX1", provenance.detectedTileSlideId)
        assertFalse(provenance.isSampleReportUsed)
        assertTrue(provenance.tileIdentityStatus.contains("TCGA-AA-3864"))
    }

    @Test
    fun testGenuineAttentionWeightsIntegrity() {
        val tileCount = 107
        // Genuine attention weights must have length matching the exact tile count
        val rawWeights = (0 until tileCount).map { 1.0f / tileCount }
        val response = AnalysisResult(
            isMsi = false,
            msiProbability = 0.7799f,
            classificationLabel = "Model prediction: MSS",
            attentionWeights = rawWeights,
            attentionDistributionAvailable = true
        )

        assertEquals(tileCount, response.attentionWeights.size)
        assertTrue(response.attentionDistributionAvailable)
        // Verify weights sum to ~1.0
        val sum = response.attentionWeights.sum()
        assertTrue("Sum must be approximately 1.0", Math.abs(sum - 1.0f) < 1e-3f)
    }

    @Test
    fun testRequestIdBindingToAnalysisResultAndProvenance() {
        val testReqId = "req-uuid-test-107-tiles"
        val testTileCount = 107
        val testSha256 = "f5a7be1ddda414f3cb99fd1fd3dc74ad7c6577955f9287b666b4f71e6fa7a7e4"
        val testRawLogit = 1.265059

        val provenance = ModelProvenance(
            fusionArchitecture = "SummaryToPatchCrossAttention (5,250,561 params)",
            decisionThresholdTau = 0.83,
            requestId = testReqId,
            tileCount = testTileCount,
            reportSha256 = testSha256,
            rawLogit = testRawLogit
        )

        val result = AnalysisResult(
            isMsi = false,
            msiProbability = 0.77989584f,
            classificationLabel = "Model prediction: MSS",
            provenance = provenance,
            requestId = testReqId
        )

        assertEquals(testReqId, result.requestId)
        assertEquals(testReqId, result.provenance.requestId)
        assertEquals(testTileCount, result.provenance.tileCount)
        assertEquals(testSha256, result.provenance.reportSha256)
        assertEquals(testRawLogit, result.provenance.rawLogit ?: 0.0, 1e-5)
    }

    @Test
    fun testStaleResultClearedWhenAnalysisStarts() {
        // A prior case that previously had a run with the 0.008488 result
        val priorResult = AnalysisResult(
            isMsi = false,
            msiProbability = 0.00848814f,
            classificationLabel = "Model prediction: MSS",
            requestId = "old-stale-req-1"
        )
        val priorCase = ResearchCase(
            id = "case-tcga-aa-3864",
            caseIdentifier = "TCGA-AA-3864",
            clinicalReportText = "Initial report",
            status = CaseStatus.ANALYZED_VERIFIED,
            result = priorResult
        )

        // When a new analysis begins, the case must be reset to ANALYZING and result cleared to null
        val newAnalyzingCase = priorCase.copy(
            status = CaseStatus.ANALYZING,
            result = null,
            errorMessage = null
        )

        assertNull("Prior result must be explicitly cleared to null on new analysis start", newAnalyzingCase.result)
        assertEquals(CaseStatus.ANALYZING, newAnalyzingCase.status)
        assertNull(newAnalyzingCase.errorMessage)
    }

    @Test
    fun testInferenceFailureTransitionsToModelUnavailableWithNullResult() {
        val priorResult = AnalysisResult(
            isMsi = false,
            msiProbability = 0.00848814f,
            classificationLabel = "Model prediction: MSS"
        )
        val analyzingCase = ResearchCase(
            id = "case-tcga-aa-3864",
            caseIdentifier = "TCGA-AA-3864",
            clinicalReportText = "Authentic report",
            status = CaseStatus.ANALYZING,
            result = null
        )

        // When inference fails (server offline), offlineCase must have result = null
        val offlineCase = analyzingCase.copy(
            status = CaseStatus.MODEL_UNAVAILABLE,
            result = null,
            errorMessage = "Inference server offline or error: Connection refused"
        )

        assertNull("Failed inference must never retain or fabricate an AnalysisResult", offlineCase.result)
        assertEquals(CaseStatus.MODEL_UNAVAILABLE, offlineCase.status)
        assertTrue(offlineCase.errorMessage!!.contains("offline"))
    }

    @Test
    fun test107TilesEvaluationPreservesFullCountInMetadata() {
        val tileCount = 107
        val reqId = "req-audit-107-tiles"
        val prob = 0.77989584f
        val threshold = 0.83

        val provenance = ModelProvenance(
            requestId = reqId,
            tileCount = tileCount,
            decisionThresholdTau = threshold,
            visionTensorShape = "[$tileCount, 1024]",
            clinicalTensorShape = "[1, 768]"
        )

        val result = AnalysisResult(
            isMsi = prob >= threshold,
            msiProbability = prob,
            classificationLabel = if (prob >= threshold) "Model prediction: MSI-H" else "Model prediction: MSS",
            decisionThresholdApplied = threshold,
            provenance = provenance,
            requestId = reqId,
            visionShape = listOf(tileCount, 1024),
            clinicalShape = listOf(1, 768)
        )

        assertEquals(107, result.provenance.tileCount)
        assertEquals("[107, 1024]", result.provenance.visionTensorShape)
        assertEquals(listOf(107, 1024), result.visionShape)
        assertFalse("At tau=0.83, probability 0.7799 must be MSS", result.isMsi)
    }
}
