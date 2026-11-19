package com.cancerdetection.multimodal.research

import com.cancerdetection.multimodal.research.ui.components.parseStructuredSummaryText
import com.cancerdetection.multimodal.research.ui.components.synthesizeStructuredSummaryFromParagraphs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredSummaryReportTest {

    @Test
    fun testParseStructuredSummarySuccess() {
        val structuredMarkdown = """
            ### A. Case Overview
            - **Case Identifier:** TCGA-AA-3680
            - **Specimen Source & Staining:** Surgical Resection (H&E)
            - **Summary Generation Status:** LLM Scribe Synthesis

            ### B. Documented Clinical Findings
            | Documented Parameter | Source Finding |
            |---|---|
            | Primary Site | Cecum |
            | Histology | Adenocarcinoma |
            | Tumour Differentiation | Moderately differentiated |
            | Tumour Extension / Pathological Stage | pT3 |
            | Resection Margins | Resection margins negative |
            | Lymph-Node Findings | 0/14 positive for metastasis |
            | Peritoneal Involvement | Not stated in supplied report |
            | Other Documented Findings | Ulcerated lesion |

            ### C. Computational Multimodal Prediction
            | Metric | Research Finding |
            |---|---|
            | Predicted MSI/MSS Class | MSS (Microsatellite Stable) |
            | Estimated MSI Probability | 8.9% |
            | Research Classification Threshold | tau = 0.83 (configured decision threshold) |
            | Interpretation Status | Computational research output; not a confirmed diagnosis |

            Note: The estimated MSI probability reflects computational multimodal query-key affinity and is not a measure of confidence in the predicted class. The threshold tau = 0.83 is a configured research parameter, not a clinically validated cut-off.

            ### D. Top Cross-Attention Scores
            | Tile Rank | Attention Weight |
            |---|---:|
            | Tile #1 | 0.2612 |
            | Tile #2 | 0.2198 |

            Note: Attention weights describe computational emphasis across input tiles and do not independently identify histological features or anatomical locations.

            ### E. Limitations & Verification Disclaimer
            This is an investigational research result, not a confirmed clinical diagnosis. MSI/MSS status requires appropriate independent verification by qualified professionals.
        """.trimIndent()

        val parsed = parseStructuredSummaryText(structuredMarkdown)
        assertTrue(parsed.isStructured)

        // Case Overview
        assertEquals(3, parsed.caseOverview.size)
        assertEquals("Case Identifier", parsed.caseOverview[0].first)
        assertEquals("TCGA-AA-3680", parsed.caseOverview[0].second)
        assertEquals("Specimen Source & Staining", parsed.caseOverview[1].first)
        assertEquals("Surgical Resection (H&E)", parsed.caseOverview[1].second)

        // Clinical Findings
        assertEquals(8, parsed.clinicalFindings.size)
        assertEquals("Primary Site", parsed.clinicalFindings[0].first)
        assertEquals("Cecum", parsed.clinicalFindings[0].second)
        assertEquals("Resection Margins", parsed.clinicalFindings[4].first)
        assertEquals("Resection margins negative", parsed.clinicalFindings[4].second)

        // Prediction
        assertEquals(4, parsed.predictionRows.size)
        assertEquals("Predicted MSI/MSS Class", parsed.predictionRows[0].first)
        assertEquals("MSS (Microsatellite Stable)", parsed.predictionRows[0].second)
        assertEquals("Estimated MSI Probability", parsed.predictionRows[1].first)
        assertEquals("8.9%", parsed.predictionRows[1].second)
        assertNotNull(parsed.predictionNote)
        assertTrue(parsed.predictionNote!!.contains("tau = 0.83"))

        // Cross-attention
        assertEquals(2, parsed.attentionScores.size)
        assertEquals("Tile #1", parsed.attentionScores[0].first)
        assertEquals("0.2612", parsed.attentionScores[0].second)
        assertNotNull(parsed.attentionNote)

        // Disclaimer
        assertNotNull(parsed.disclaimer)
        assertTrue(parsed.disclaimer!!.contains("investigational research result"))
    }

    @Test
    fun testParseLegacyParagraphSummaryFallback() {
        val legacyText = """
            Paragraph 1: Colorectal adenocarcinoma of the ascending colon.
            
            Paragraph 2: Computational model predicts MSI-High with 92.4% probability.
            
            Paragraph 3: Top tile attention: Tile #1: 0.35. Investigational use only.
        """.trimIndent()

        val parsed = parseStructuredSummaryText(legacyText)
        assertFalse(parsed.isStructured)
        assertTrue(parsed.clinicalFindings.isEmpty())
        assertTrue(parsed.predictionRows.isEmpty())
    }

    @Test
    fun testParseBoldHeadingsAndVariations() {
        val boldMarkdown = """
            **A. Case Overview**
            - **Case Identifier:** TCGA-AA-3680
            - **Specimen Source & Staining:** Surgical Resection (H&E)
            - **Summary Generation Status:** LLM Scribe Synthesis

            **B. Documented Clinical Findings**
            | Documented Parameter | Source Finding |
            |---|---|
            | Primary Site | Cecum |
            | Histology | Adenocarcinoma |

            **C. Computational Multimodal Prediction**
            | Metric | Research Finding |
            |---|---|
            | Predicted MSI/MSS Class | MSS (Microsatellite Stable) |
            | Estimated MSI Probability | 8.9% |

            Note: The estimated MSI probability reflects computational multimodal query-key affinity.

            **D. Top Cross-Attention Scores**
            | Tile Rank | Attention Weight |
            |---|---:|
            | Tile #1 | 0.2612 |

            **E. Limitations & Verification Disclaimer**
            This is an investigational research result, not a confirmed clinical diagnosis.
        """.trimIndent()

        val parsed = parseStructuredSummaryText(boldMarkdown)
        assertTrue(parsed.isStructured)
        assertEquals(3, parsed.caseOverview.size)
        assertEquals(2, parsed.clinicalFindings.size)
        assertEquals("Primary Site", parsed.clinicalFindings[0].first)
        assertEquals("Cecum", parsed.clinicalFindings[0].second)
        assertEquals(2, parsed.predictionRows.size)
        assertEquals("MSS (Microsatellite Stable)", parsed.predictionRows[0].second)
        assertEquals(1, parsed.attentionScores.size)
        assertEquals("Tile #1", parsed.attentionScores[0].first)
        assertNotNull(parsed.disclaimer)
    }

    @Test
    fun testSynthesizeStructuredSummaryFromParagraphs() {
        val paragraphSummary = """
            Case TCGA-AA-3680: Hemicolectomy specimen showed adenocarcinoma of the ascending colon, moderately differentiated, pT3, with negative resection margins and 0/14 lymph nodes involved.

            Multimodal fusion model predicted MSS (Microsatellite Stable) with 8.9% estimated MSI probability relative to threshold tau = 0.83.

            Top tile attention weights were Tile #1: 0.2612, Tile #2: 0.2198.
        """.trimIndent()

        val synthesized = synthesizeStructuredSummaryFromParagraphs(paragraphSummary)
        assertTrue(synthesized.isStructured)
        assertEquals("TCGA-AA-3680", synthesized.caseOverview.first { it.first == "Case Identifier" }.second)
        assertEquals("Ascending colon", synthesized.clinicalFindings.first { it.first == "Primary Site" }.second)
        assertEquals("Adenocarcinoma", synthesized.clinicalFindings.first { it.first == "Histology" }.second)
        assertEquals("Moderately differentiated", synthesized.clinicalFindings.first { it.first == "Tumour Differentiation" }.second)
        assertEquals("PT3", synthesized.clinicalFindings.first { it.first == "Tumour Extension / Pathological Stage" }.second)
        assertEquals("Resection margins negative", synthesized.clinicalFindings.first { it.first == "Resection Margins" }.second)
        assertTrue(synthesized.clinicalFindings.first { it.first == "Lymph-Node Findings" }.second.contains("0/14"))
        assertEquals("MSS (Microsatellite Stable)", synthesized.predictionRows.first { it.first == "Predicted MSI/MSS Class" }.second)
        assertEquals("8.9%", synthesized.predictionRows.first { it.first == "Estimated MSI Probability" }.second)
        assertEquals(2, synthesized.attentionScores.size)
        assertEquals("Tile #1", synthesized.attentionScores[0].first)
        assertEquals("0.2612", synthesized.attentionScores[0].second)
        assertNotNull(synthesized.disclaimer)
    }

    @Test
    fun testSingleTileDoesNotProduceExtraRowsWhenSanitized() {
        val markdownWithHallucinatedRows = """
            ### A. Case Overview
            - **Case Identifier:** TCGA-AA-3680
            - **Specimen Source & Staining:** Surgical Resection (H&E)

            ### B. Documented Clinical Findings
            | Documented Parameter | Source Finding |
            |---|---|
            | Primary Site | Cecum |

            ### C. Computational Multimodal Prediction
            | Metric | Research Finding |
            |---|---|
            | Predicted MSI/MSS Class | MSS (Microsatellite Stable) |

            ### D. Top Cross-Attention Scores
            | Tile Rank | Attention Weight |
            |---|---:|
            | Tile #1 | 1.0000 |
            | Tile #2 | 0.9800 |
            | Tile #3 | 0.9600 |
            | Tile #4 | 0.9400 |
            | Tile #5 | 0.9200 |

            ### E. Limitations & Verification Disclaimer
            Investigational use only.
        """.trimIndent()

        val parsed = parseStructuredSummaryText(markdownWithHallucinatedRows)
        // Without actual weights constraint, raw markdown has 5 rows
        assertEquals(5, parsed.attentionScores.size)

        // When actualAttentionWeights has 1 item, sanitized must clamp to 1 item
        val actualWeights = listOf(1.0f)
        val genuineCount = actualWeights.size.coerceAtMost(5)
        val sanitized = parsed.attentionScores.take(genuineCount)
        assertEquals(1, sanitized.size)
        assertEquals("Tile #1", sanitized[0].first)
        assertEquals("1.0000", sanitized[0].second)
    }

    @Test
    fun testSynthesizeWithSingleActualWeightProducesExactlyOneRow() {
        val paragraphSummary = """
            Case TCGA-AA-3680: Colorectal adenocarcinoma of the cecum, MSS with 8.9% probability.
        """.trimIndent()

        val synthesized = synthesizeStructuredSummaryFromParagraphs(
            summaryText = paragraphSummary,
            actualAttentionWeights = listOf(1.0f)
        )
        assertTrue(synthesized.isStructured)
        assertEquals(1, synthesized.attentionScores.size)
        assertEquals("Tile #1", synthesized.attentionScores[0].first)
        assertEquals("1.0000", synthesized.attentionScores[0].second)
    }

    @Test
    fun testEmptyAttentionWeightsProducesUnavailableMessage() {
        val paragraphSummary = """
            Case TCGA-AA-3680: Colorectal adenocarcinoma of the cecum, MSS with 8.9% probability.
        """.trimIndent()

        val synthesized = synthesizeStructuredSummaryFromParagraphs(
            summaryText = paragraphSummary,
            actualAttentionWeights = emptyList()
        )
        assertTrue(synthesized.isStructured)
        assertEquals(1, synthesized.attentionScores.size)
        assertEquals("Tile-level attention", synthesized.attentionScores[0].first)
        assertEquals("Not available", synthesized.attentionScores[0].second)
    }
}
