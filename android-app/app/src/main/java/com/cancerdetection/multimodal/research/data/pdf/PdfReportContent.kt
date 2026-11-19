package com.cancerdetection.multimodal.research.data.pdf

import android.graphics.Paint
import com.cancerdetection.multimodal.research.domain.model.ResearchCase
import com.cancerdetection.multimodal.research.ui.components.parseStructuredSummaryText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Structured content model extracted from a ResearchCase for generating
 * a publication-quality clinical research PDF report.
 */
data class PdfReportContent(
    val title: String = "COLORECTAL CANCER MULTIMODAL RESEARCH REPORT",
    val subtitle: String = "Decision-Support via Phikon-v2 ViT-L + Bio_ClinicalBERT Multimodal Cross-Attention",
    val generatedDate: String,
    val caseIdentifier: String,
    val specimenType: String,
    val caseStatus: String,
    val reviewStatus: String,
    val summaryStatus: String,
    // Section A Overview Items
    val caseOverviewItems: List<Pair<String, String>>,
    // Section B Clinical Findings
    val clinicalFindings: List<Pair<String, String>>,
    // Section C Computational Prediction
    val predictionClass: String,
    val isMsi: Boolean,
    val probabilityFormatted: String,
    val thresholdNote: String,
    val predictionRows: List<Pair<String, String>>,
    val predictionFootnote: String,
    // Section D Attention Weights
    val attentionScores: List<Pair<String, String>>,
    val attentionFootnote: String,
    // Clinician Review Notes
    val reviewNotes: List<Pair<String, String>> = emptyList(),
    // Section E/F Disclaimer
    val limitationsDisclaimer: String
)

/**
 * Sanitizes a case identifier for use in a clean, valid PDF filename.
 * Example: "TCGA-AA-3680" -> "Clinical_Research_Report_TCGA-AA-3680.pdf"
 */
fun sanitizePdfFilename(caseIdentifier: String?): String {
    val raw = caseIdentifier?.trim()
    val sanitized = if (raw.isNullOrBlank()) {
        "Case"
    } else {
        raw.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .take(60) // Safe length limit
    }
    return "Clinical_Research_Report_${sanitized}.pdf"
}

/**
 * Wraps text into multiple lines given a max width in points or approx characters.
 * Handles both Android Canvas typography measurements and JVM unit-test fallback.
 */
fun wrapText(
    text: String,
    paint: Paint? = null,
    maxWidth: Float = 500f,
    approxCharsPerLine: Int = maxOf(20, (maxWidth / 5.2f).toInt())
): List<String> {
    if (text.isBlank()) return emptyList()

    val lines = mutableListOf<String>()
    val paragraphs = text.split("\n")

    for (paragraph in paragraphs) {
        val trimmedPara = paragraph.trim()
        if (trimmedPara.isEmpty()) continue

        val words = trimmedPara.split(Regex("\\s+"))
        var currentLine = StringBuilder()

        for (word in words) {
            val candidate = if (currentLine.isEmpty()) word else "$currentLine $word"
            val fits = try {
                if (paint != null) {
                    paint.measureText(candidate) <= maxWidth
                } else {
                    candidate.length <= approxCharsPerLine
                }
            } catch (_: Exception) {
                // JVM unit tests stub Paint.measureText; fallback to character count
                candidate.length <= approxCharsPerLine
            }

            if (fits) {
                currentLine = StringBuilder(candidate)
            } else {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine.toString())
                }
                currentLine = StringBuilder(word)
            }
        }
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.toString())
        }
    }

    return lines
}

/**
 * Extracts structured report content from the supplied ResearchCase.
 * Reuses the existing structured summary output if available, preserving
 * the exact documented parameters, predictions, attention scores, and disclaimer.
 */
fun extractPdfReportContent(researchCase: ResearchCase): PdfReportContent {
    val result = researchCase.result
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    val currentDateStr = dateFormat.format(Date(result?.completedAtMillis ?: System.currentTimeMillis()))

    val caseId = researchCase.caseIdentifier.ifBlank { "Research Case" }
    val specimenType = researchCase.specimenType.ifBlank { "Surgical Resection (H&E)" }
    val caseStatus = researchCase.status.name
    val reviewStatus = researchCase.reviewStatus

    val isLlm = result?.clinicalSummaryIsLlm == true
    val modelUsed = result?.clinicalSummaryModelUsed ?: "AI Clinical Scribe"
    val summarySource = result?.clinicalSummarySource ?: "deterministic_template_fallback"
    val summaryStatusLabel = when {
        isLlm -> "LLM Scribe Synthesis ($modelUsed)"
        summarySource == "ollama_llm" -> "LLM Scribe Synthesis ($modelUsed)"
        else -> "Deterministic Template Fallback"
    }

    val defaultPredictionFootnote =
        "Note: The estimated MSI probability reflects computational multimodal query-key affinity and is not a measure of confidence in the predicted class. The threshold tau = 0.83 is a configured research parameter, not a clinically validated cut-off."
    val defaultAttentionFootnote =
        "Note: Attention weights describe computational emphasis across input tiles and do not independently identify histological features or anatomical locations."
    val defaultDisclaimer =
        "This is an investigational research result, not a confirmed clinical diagnosis. MSI/MSS status requires appropriate independent verification by qualified professionals."

    val reviewNotes = researchCase.notes.mapIndexed { idx, note ->
        "[${idx + 1}] (${note.category}) ${note.author}" to note.content
    }

    val rawSummary = result?.clinicalSummaryText
    val parsed = if (!rawSummary.isNullOrBlank()) {
        parseStructuredSummaryText(rawSummary)
    } else {
        null
    }

    if (parsed != null && parsed.isStructured) {
        val overviewItems = if (parsed.caseOverview.isNotEmpty()) {
            parsed.caseOverview
        } else {
            listOf(
                "Case Identifier" to caseId,
                "Specimen Source & Staining" to specimenType,
                "Summary Generation Status" to summaryStatusLabel,
                "Review State" to reviewStatus
            )
        }

        val isMsi = result?.isMsi ?: parsed.predictionRows.any { it.second.contains("MSI", ignoreCase = true) }
        val probFormatted = parsed.predictionRows.firstOrNull { it.first.contains("Probability", ignoreCase = true) }?.second
            ?: String.format(Locale.US, "%.1f%%", (result?.msiProbability ?: 0f) * 100)
        val predClass = parsed.predictionRows.firstOrNull { it.first.contains("Class", ignoreCase = true) }?.second
            ?: if (isMsi) "MSI-High (Microsatellite Instability)" else "MSS (Microsatellite Stable)"

        val finalAttentionScores = if (result != null && result.attentionWeights.isNotEmpty()) {
            val genuineCount = result.attentionWeights.size.coerceAtMost(5)
            if (parsed.attentionScores.size > genuineCount) {
                parsed.attentionScores.take(genuineCount)
            } else if (parsed.attentionScores.isEmpty()) {
                result.attentionWeights.sortedDescending().take(genuineCount).mapIndexed { idx, w ->
                    "Tile #${idx + 1}" to String.format(Locale.US, "%.4f", w)
                }
            } else {
                parsed.attentionScores
            }
        } else {
            listOf("Tile-level attention" to "Not available")
        }

        return PdfReportContent(
            generatedDate = currentDateStr,
            caseIdentifier = caseId,
            specimenType = specimenType,
            caseStatus = caseStatus,
            reviewStatus = reviewStatus,
            summaryStatus = summaryStatusLabel,
            caseOverviewItems = overviewItems,
            clinicalFindings = parsed.clinicalFindings,
            predictionClass = predClass,
            isMsi = isMsi,
            probabilityFormatted = probFormatted,
            thresholdNote = "tau = 0.83 (configured decision threshold)",
            predictionRows = parsed.predictionRows,
            predictionFootnote = parsed.predictionNote ?: defaultPredictionFootnote,
            attentionScores = finalAttentionScores,
            attentionFootnote = parsed.attentionNote ?: defaultAttentionFootnote,
            reviewNotes = reviewNotes,
            limitationsDisclaimer = parsed.disclaimer ?: defaultDisclaimer
        )
    }

    // Fallback: Generate structured model directly from ResearchCase properties
    val isMsi = result?.isMsi ?: false
    val probFormatted = String.format(Locale.US, "%.1f%%", (result?.msiProbability ?: 0f) * 100)
    val predClass = if (isMsi) "MSI-High (Microsatellite Instability)" else "MSS (Microsatellite Stable)"

    val overviewItems = listOf(
        "Case Identifier" to caseId,
        "Specimen Source & Staining" to specimenType,
        "Summary Generation Status" to summaryStatusLabel,
        "Review State" to reviewStatus
    )

    val clinicalFindings = listOf(
        "Primary Site" to extractDocumentedSite(researchCase.clinicalReportText),
        "Histology" to "Adenocarcinoma",
        "Tumour Differentiation" to extractDocumentedDiff(researchCase.clinicalReportText),
        "Tumour Extension / Pathological Stage" to "Not stated in supplied report",
        "Resection Margins" to "Not stated in supplied report",
        "Lymph-Node Findings" to "Not stated in supplied report",
        "Peritoneal Involvement" to "Not stated in supplied report",
        "Other Documented Findings" to if (researchCase.clinicalReportText.isNotBlank()) researchCase.clinicalReportText.take(120) else "None documented"
    )

    val predictionRows = listOf(
        "Predicted MSI/MSS Class" to predClass,
        "Estimated MSI Probability" to probFormatted,
        "Research Classification Threshold" to "tau = 0.83 (configured decision threshold)",
        "Interpretation Status" to "Computational research output; not a confirmed diagnosis"
    )

    val attentionScores = if (result != null && result.attentionWeights.isNotEmpty()) {
        result.attentionWeights.sortedDescending().take(5).mapIndexed { idx, w ->
            "Tile #${idx + 1}" to String.format(Locale.US, "%.4f", w)
        }
    } else {
        listOf("Tile-level attention" to "Not available")
    }

    return PdfReportContent(
        generatedDate = currentDateStr,
        caseIdentifier = caseId,
        specimenType = specimenType,
        caseStatus = caseStatus,
        reviewStatus = reviewStatus,
        summaryStatus = summaryStatusLabel,
        caseOverviewItems = overviewItems,
        clinicalFindings = clinicalFindings,
        predictionClass = predClass,
        isMsi = isMsi,
        probabilityFormatted = probFormatted,
        thresholdNote = "tau = 0.83 (configured decision threshold)",
        predictionRows = predictionRows,
        predictionFootnote = defaultPredictionFootnote,
        attentionScores = attentionScores,
        attentionFootnote = defaultAttentionFootnote,
        reviewNotes = reviewNotes,
        limitationsDisclaimer = defaultDisclaimer
    )
}

private fun extractDocumentedSite(text: String): String {
    val low = text.lowercase()
    return when {
        "cecum" in low -> "Cecum"
        "ascending" in low -> "Ascending colon"
        "sigmoid" in low -> "Sigmoid colon"
        "rectosigmoid" in low -> "Rectosigmoid"
        "rectum" in low -> "Rectum"
        "colon" in low -> "Colon"
        else -> "Not stated in supplied report"
    }
}

private fun extractDocumentedDiff(text: String): String {
    val low = text.lowercase()
    return when {
        "poorly differentiated" in low -> "Poorly differentiated (High grade)"
        "moderately differentiated" in low -> "Moderately differentiated"
        "well differentiated" in low -> "Well differentiated (Low grade)"
        else -> "Not stated in supplied report"
    }
}
