package com.cancerdetection.multimodal.research

import com.cancerdetection.multimodal.research.data.pdf.extractPdfReportContent
import com.cancerdetection.multimodal.research.data.pdf.sanitizePdfFilename
import com.cancerdetection.multimodal.research.data.pdf.wrapText
import com.cancerdetection.multimodal.research.domain.model.AnalysisResult
import com.cancerdetection.multimodal.research.domain.model.CaseStatus
import com.cancerdetection.multimodal.research.domain.model.ResearchCase
import com.cancerdetection.multimodal.research.domain.model.ReviewNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfReportGeneratorTest {

    @Test
    fun testSanitizePdfFilename_standardId() {
        val filename = sanitizePdfFilename("TCGA-AA-3680")
        assertEquals("Clinical_Research_Report_TCGA-AA-3680.pdf", filename)
    }

    @Test
    fun testSanitizePdfFilename_specialCharactersAndSpaces() {
        val filename = sanitizePdfFilename("  TCGA/AA/3680:specimen?v=1#2  ")
        assertEquals("Clinical_Research_Report_TCGA_AA_3680_specimen_v_1_2.pdf", filename)
    }

    @Test
    fun testSanitizePdfFilename_nullAndBlank() {
        assertEquals("Clinical_Research_Report_Case.pdf", sanitizePdfFilename(null))
        assertEquals("Clinical_Research_Report_Case.pdf", sanitizePdfFilename(""))
        assertEquals("Clinical_Research_Report_Case.pdf", sanitizePdfFilename("   "))
    }

    @Test
    fun testSanitizePdfFilename_maxLengthBounded() {
        val longId = "A".repeat(120)
        val filename = sanitizePdfFilename(longId)
        assertTrue(filename.startsWith("Clinical_Research_Report_"))
        assertTrue(filename.endsWith(".pdf"))
        val innerName = filename.removePrefix("Clinical_Research_Report_").removeSuffix(".pdf")
        assertEquals(60, innerName.length)
    }

    @Test
    fun testWrapText_emptyAndBlank() {
        assertTrue(wrapText("").isEmpty())
        assertTrue(wrapText("   \n  \t  ").isEmpty())
    }

    @Test
    fun testWrapText_shortTextSingleLine() {
        val lines = wrapText("Short line of clinical notes", null, 500f, approxCharsPerLine = 40)
        assertEquals(1, lines.size)
        assertEquals("Short line of clinical notes", lines[0])
    }

    @Test
    fun testWrapText_longTextWrapsAtWordBoundaries() {
        val text = "This is an investigational research result and not a confirmed clinical diagnosis under any regulatory standard."
        val lines = wrapText(text, null, 500f, approxCharsPerLine = 30)
        assertTrue(lines.size > 1)
        for (line in lines) {
            assertTrue("Line '${line}' should be <= 30 chars or single word", line.length <= 35)
        }
        assertEquals(text, lines.joinToString(" "))
    }

    @Test
    fun testWrapText_multiParagraphPreserved() {
        val text = "Paragraph one with some text.\nParagraph two with more findings."
        val lines = wrapText(text, null, 500f, approxCharsPerLine = 50)
        assertEquals(2, lines.size)
        assertEquals("Paragraph one with some text.", lines[0])
        assertEquals("Paragraph two with more findings.", lines[1])
    }

    @Test
    fun testExtractPdfReportContent_structuredSummary() {
        val structuredMarkdown = """
            ### A. Case Overview
            - **Case Identifier:** TCGA-AA-3680
            - **Specimen Source & Staining:** Surgical Resection (H&E)
            - **Summary Generation Status:** LLM Scribe Synthesis (gemma3:1b)

            ### B. Documented Clinical Findings
            | Documented Parameter | Source Finding |
            |---|---|
            | Primary Site | Cecum |
            | Histology | Adenocarcinoma |
            | Tumour Differentiation | Moderately differentiated |
            | Resection Margins | Resection margins negative |

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

        val researchCase = ResearchCase(
            caseIdentifier = "TCGA-AA-3680",
            specimenType = "Surgical Resection (H&E)",
            status = CaseStatus.ANALYZED_VERIFIED,
            reviewStatus = "Reviewed",
            result = AnalysisResult(
                isMsi = false,
                msiProbability = 0.089f,
                classificationLabel = "MSS",
                decisionThresholdApplied = 0.83,
                clinicalSummaryGenerated = true,
                clinicalSummaryText = structuredMarkdown,
                clinicalSummaryModelUsed = "gemma3:1b",
                clinicalSummarySource = "ollama_llm",
                clinicalSummaryIsLlm = true,
                attentionWeights = listOf(0.2612f, 0.2198f)
            )
        )

        val reportContent = extractPdfReportContent(researchCase)

        assertEquals("TCGA-AA-3680", reportContent.caseIdentifier)
        assertEquals("Surgical Resection (H&E)", reportContent.specimenType)
        assertEquals("Reviewed", reportContent.reviewStatus)
        assertFalse(reportContent.isMsi)
        assertEquals("MSS (Microsatellite Stable)", reportContent.predictionClass)
        assertEquals("8.9%", reportContent.probabilityFormatted)

        // Section B
        assertEquals(4, reportContent.clinicalFindings.size)
        assertEquals("Primary Site", reportContent.clinicalFindings[0].first)
        assertEquals("Cecum", reportContent.clinicalFindings[0].second)

        // Section C
        assertEquals(4, reportContent.predictionRows.size)
        assertTrue(reportContent.predictionFootnote.contains("tau = 0.83"))
        assertTrue(reportContent.predictionFootnote.contains("not a measure of confidence"))

        // Section D
        assertEquals(2, reportContent.attentionScores.size)
        assertEquals("Tile #1", reportContent.attentionScores[0].first)
        assertEquals("0.2612", reportContent.attentionScores[0].second)
        assertTrue(reportContent.attentionFootnote.contains("computational emphasis"))

        // Section E
        assertTrue(reportContent.limitationsDisclaimer.contains("investigational research result"))
    }

    @Test
    fun testExtractPdfReportContent_fallbackSparseOrEmpty() {
        val researchCase = ResearchCase(
            caseIdentifier = "TCGA-AA-Sparse",
            specimenType = "Biopsy",
            clinicalReportText = "Primary site: ascending colon. Moderately differentiated adenocarcinoma.",
            status = CaseStatus.ANALYZED_VERIFIED,
            reviewStatus = "Draft",
            result = AnalysisResult(
                isMsi = true,
                msiProbability = 0.912f,
                classificationLabel = "MSI-H",
                decisionThresholdApplied = 0.83,
                clinicalSummaryGenerated = false,
                clinicalSummaryText = null,
                attentionWeights = listOf(0.4210f, 0.3150f, 0.1520f)
            )
        )

        val reportContent = extractPdfReportContent(researchCase)

        assertEquals("TCGA-AA-Sparse", reportContent.caseIdentifier)
        assertEquals("Biopsy", reportContent.specimenType)
        assertTrue(reportContent.isMsi)
        assertEquals("MSI-High (Microsatellite Instability)", reportContent.predictionClass)
        assertEquals("91.2%", reportContent.probabilityFormatted)

        // Extracted site and diff in Section B
        val siteRow = reportContent.clinicalFindings.firstOrNull { it.first == "Primary Site" }
        assertNotNull(siteRow)
        assertEquals("Ascending colon", siteRow?.second)

        val diffRow = reportContent.clinicalFindings.firstOrNull { it.first == "Tumour Differentiation" }
        assertNotNull(diffRow)
        assertEquals("Moderately differentiated", diffRow?.second)

        // Section D attention scores mapped from attentionWeights
        assertEquals(3, reportContent.attentionScores.size)
        assertEquals("Tile #1", reportContent.attentionScores[0].first)
        assertEquals("0.4210", reportContent.attentionScores[0].second)

        // Research disclaimer and footnotes present
        assertTrue(reportContent.predictionFootnote.contains("tau = 0.83"))
        assertTrue(reportContent.limitationsDisclaimer.contains("investigational research result"))
    }

    @Test
    fun testExtractPdfReportContent_withReviewNotes() {
        val researchCase = ResearchCase(
            caseIdentifier = "TCGA-AA-Notes",
            specimenType = "Surgical Resection (H&E)",
            status = CaseStatus.ANALYZED_VERIFIED,
            reviewStatus = "Under Review",
            notes = listOf(
                ReviewNote(
                    author = "Dr. Pathologist",
                    category = "Histological Observation",
                    content = "Abundant tumor-infiltrating lymphocytes noted in peritumoral stroma."
                )
            )
        )

        val reportContent = extractPdfReportContent(researchCase)
        assertEquals(1, reportContent.reviewNotes.size)
        assertEquals("[1] (Histological Observation) Dr. Pathologist", reportContent.reviewNotes[0].first)
        assertEquals("Abundant tumor-infiltrating lymphocytes noted in peritumoral stroma.", reportContent.reviewNotes[0].second)
    }
}
