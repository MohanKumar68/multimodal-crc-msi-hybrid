package com.cancerdetection.multimodal.research

import com.cancerdetection.multimodal.research.data.network.InferenceResponseData
import com.cancerdetection.multimodal.research.domain.model.AnalysisResult
import com.cancerdetection.multimodal.research.domain.model.ModelProvenance
import com.cancerdetection.multimodal.research.ui.screens.findings.formatExecutionDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for model inference latency measurement, duration formatting,
 * backward-compatible response parsing, and provenance preservation.
 */
class InferenceLatencyMeasurementTest {

    @Test
    fun testFormatExecutionDuration_subSecond() {
        assertEquals("425 ms", formatExecutionDuration(425L))
        assertEquals("50 ms", formatExecutionDuration(50L))
        assertEquals("999 ms", formatExecutionDuration(999L))
    }

    @Test
    fun testFormatExecutionDuration_secondsRange() {
        assertEquals("1.25 sec (1,250 ms)", formatExecutionDuration(1250L))
        assertEquals("8.42 sec (8,420 ms)", formatExecutionDuration(8420L))
        assertEquals("10.00 sec (10,000 ms)", formatExecutionDuration(10000L))
        assertEquals("59.99 sec (59,990 ms)", formatExecutionDuration(59990L))
    }

    @Test
    fun testFormatExecutionDuration_minutesRange() {
        // 736,298 ms = 12 min 16.30 sec (matches prompt specification)
        assertEquals("12 min 16.30 sec", formatExecutionDuration(736298L))
        assertEquals("1 min 0.00 sec", formatExecutionDuration(60000L))
        assertEquals("2 min 30.50 sec", formatExecutionDuration(150500L))
    }

    @Test
    fun testInferenceResponseDataWithTimingFields() {
        val resp = InferenceResponseData(
            probability = 0.94f,
            visionShape = listOf(1, 1024),
            clinicalShape = listOf(1, 768),
            latencyMs = 1560L,
            serverUrl = "http://192.168.1.100:5000/predict",
            totalModelInferenceMs = 1355.74,
            visionInferenceMs = 1259.0,
            clinicalTextInferenceMs = 93.12,
            fusionClassificationMs = 3.62,
            inferenceTimingScope = "forward_pass_accumulated"
        )

        assertEquals(0.94f, resp.probability, 0.001f)
        assertEquals(1560L, resp.latencyMs)
        assertNotNull(resp.totalModelInferenceMs)
        assertEquals(1355.74, resp.totalModelInferenceMs!!, 0.01)
        assertEquals(1259.0, resp.visionInferenceMs!!, 0.01)
        assertEquals(93.12, resp.clinicalTextInferenceMs!!, 0.01)
        assertEquals(3.62, resp.fusionClassificationMs!!, 0.01)
        assertEquals("forward_pass_accumulated", resp.inferenceTimingScope)
    }

    @Test
    fun testInferenceResponseDataBackwardCompatibility() {
        // Constructing without optional timing fields defaults to null without runtime failure
        val legacyResp = InferenceResponseData(
            probability = 0.65f,
            visionShape = listOf(2, 1024),
            clinicalShape = listOf(1, 768),
            latencyMs = 800L,
            serverUrl = "http://localhost:5000/fuse"
        )

        assertEquals(800L, legacyResp.latencyMs)
        assertNull(legacyResp.totalModelInferenceMs)
        assertNull(legacyResp.visionInferenceMs)
        assertNull(legacyResp.clinicalTextInferenceMs)
        assertNull(legacyResp.fusionClassificationMs)
        assertNull(legacyResp.inferenceTimingScope)
    }

    @Test
    fun testModelProvenanceDualTimingPreservation() {
        val totalWorkflowMs = 12450L
        val modelInferenceMs = 8420L

        val provenance = ModelProvenance(
            latencyMs = totalWorkflowMs,
            inferenceLatencyMs = modelInferenceMs,
            visionInferenceMs = 7800L,
            clinicalTextInferenceMs = 600L,
            fusionClassificationMs = 20L,
            inferenceTimingScope = "forward_pass_accumulated",
            isRealInference = true,
            serverEndpoint = "http://10.0.0.5:5000/fuse"
        )

        // Strict non-conflation: Total execution and Inference latency remain separate
        assertEquals(12450L, provenance.latencyMs)
        assertEquals(8420L, provenance.inferenceLatencyMs)
        assertTrue(provenance.latencyMs > provenance.inferenceLatencyMs!!)

        val result = AnalysisResult(
            isMsi = true,
            msiProbability = 0.92f,
            classificationLabel = "Model prediction: MSI-H",
            provenance = provenance,
            inferenceLatencyMs = modelInferenceMs
        )

        assertEquals(12450L, result.provenance.latencyMs)
        assertEquals(8420L, result.provenance.inferenceLatencyMs)
        assertEquals(8420L, result.inferenceLatencyMs)
    }

    @Test
    fun testUnavailableTimingStateSafety() {
        // Missing timing state must never fabricate a 0 ms measurement
        val provenance = ModelProvenance(
            latencyMs = 3500L,
            inferenceLatencyMs = null, // Unavailable from older or simulated backend
            isRealInference = true
        )

        assertNull(provenance.inferenceLatencyMs)
        assertEquals(3500L, provenance.latencyMs)
    }

    @Test
    fun testDerivedTotalFallbackWhenTotalMissing() {
        // When totalModelInferenceMs is null, derivedComponentTotalMs resolves the latency
        val visionInfMs: Long? = 83110L
        val clinicalTextInfMs: Long? = 74L
        val fusionClassInfMs: Long? = 7L
        val derivedComponentTotalMs = ((visionInfMs ?: 0L) + (clinicalTextInfMs ?: 0L) + (fusionClassInfMs ?: 0L)).takeIf { it > 0L }

        val totalModelInferenceMs: Double? = null
        val infLatencyMs = (totalModelInferenceMs?.let { Math.round(it) }?.takeIf { it > 0L }) ?: derivedComponentTotalMs

        assertNotNull(infLatencyMs)
        assertEquals(83191L, infLatencyMs!!)
        assertEquals("1 min 23.19 sec", formatExecutionDuration(infLatencyMs))
    }

    @Test
    fun testMultiChunkFuseStageAccumulation() {
        // If server only timed the /fuse stage (81.46 ms) and client accumulated 83,110.18 ms of vision time,
        // client detects totalModelInferenceMs < accumulatedVisionMs and resolves the full latency
        val accumulatedVisionMs = 83110.18
        var totalModelInferenceMs: Double? = 81.46
        val clinicalTextInferenceMs: Double? = 74.37
        val fusionClassificationMs: Double? = 7.09

        if (accumulatedVisionMs > 0.0) {
            val stageMs = (clinicalTextInferenceMs ?: 0.0) + (fusionClassificationMs ?: 0.0)
            if (totalModelInferenceMs == null) {
                totalModelInferenceMs = accumulatedVisionMs + stageMs
            } else if (totalModelInferenceMs < accumulatedVisionMs) {
                totalModelInferenceMs = accumulatedVisionMs + totalModelInferenceMs
            }
        }

        assertEquals(83191.64, totalModelInferenceMs!!, 0.01)
        val infLatencyMs = Math.round(totalModelInferenceMs).takeIf { it > 0L }
        assertEquals(83192L, infLatencyMs!!)
        assertEquals("1 min 23.19 sec", formatExecutionDuration(infLatencyMs))
    }
}
