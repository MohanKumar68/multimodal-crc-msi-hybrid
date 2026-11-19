package com.cancerdetection.multimodal.research.ui.screens.report

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cancerdetection.multimodal.research.data.pdf.PdfReportGenerator
import com.cancerdetection.multimodal.research.data.repository.ResearchCaseRepository
import com.cancerdetection.multimodal.research.domain.model.ResearchCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

import java.util.Locale

data class ReportPreviewUiState(
    val researchCase: ResearchCase? = null,
    val isLoading: Boolean = true,
    val infoMessage: String? = null,
    val isExportingPdf: Boolean = false,
    val pdfExportMessage: String? = null
)

class ReportPreviewViewModel(
    private val repository: ResearchCaseRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReportPreviewUiState())
    val uiState: StateFlow<ReportPreviewUiState> = _uiState.asStateFlow()

    fun loadCase(caseId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val caseItem = repository?.getCaseById(caseId)?.firstOrNull()
            _uiState.value = ReportPreviewUiState(
                researchCase = caseItem,
                isLoading = false
            )
        }
    }

    fun setInfoMessage(msg: String?) {
        _uiState.value = _uiState.value.copy(infoMessage = msg)
    }

    fun exportPdf(context: Context, destinationUri: Uri) {
        if (_uiState.value.isExportingPdf) return
        val currentCase = _uiState.value.researchCase ?: return

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(
                isExportingPdf = true,
                pdfExportMessage = null
            )
            val result = PdfReportGenerator.exportPdfToUri(
                context = context,
                researchCase = currentCase,
                destinationUri = destinationUri
            )
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isExportingPdf = false,
                    pdfExportMessage = "PDF saved successfully"
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isExportingPdf = false,
                    pdfExportMessage = "PDF generation failed. Please try again."
                )
            }
        }
    }

    fun clearPdfExportMessage() {
        _uiState.value = _uiState.value.copy(pdfExportMessage = null)
    }

    fun generateReportText(c: ResearchCase): String {
        val result = c.result
        return buildString {
            appendLine("==================================================")
            appendLine("COLORECTAL CANCER MULTIMODAL RESEARCH REPORT")
            appendLine("INVESTIGATIONAL USE ONLY — NOT FOR CLINICAL DIAGNOSIS")
            appendLine("==================================================")
            appendLine("Case Identifier : ${c.caseIdentifier}")
            appendLine("Specimen Type   : ${c.specimenType}")
            appendLine("Case Status     : ${c.status.name}")
            appendLine("Review State    : ${c.reviewStatus}")
            appendLine("Analysis Scope  : Patch-Level Research Analysis (${c.images.size} Tiles)")
            appendLine("Tiles Analyzed  : ${c.images.size}")
            appendLine("--------------------------------------------------")
            appendLine("CLINICAL PATHOLOGY FINDINGS (INPUT):")
            appendLine(c.clinicalReportText.ifBlank { "No clinical report text recorded." })
            appendLine("--------------------------------------------------")
            appendLine("COMPUTATIONAL FUSION ASSESSMENT:")
            if (result != null) {
                appendLine("Classification  : ${result.classificationLabel}")
                appendLine("MSI Probability : ${String.format(Locale.US, "%.4f (%.1f%%)", result.msiProbability, result.msiProbability * 100)}")
                appendLine("Decision Cutoff : τ = ${result.decisionThresholdApplied} (Research threshold — not clinically validated)")
                appendLine("Result Type     : ${if (result.isSimulated) "SIMULATED DEMO (PHASE 1 SCAFFOLD)" else "EXPERIMENTAL RESEARCH RUN (UNVALIDATED)"}")
                appendLine("Architecture    : ${result.provenance.fusionArchitecture}")
                appendLine("Checkpoint      : ${result.provenance.fusionCheckpoint}")
                appendLine("Environment     : ${result.provenance.inferenceEnvironment}")

                // AI Diagnostic Clinical Summary Section
                appendLine("--------------------------------------------------")
                val isLlm = result.clinicalSummaryIsLlm
                val summarySource = result.clinicalSummarySource
                val isFallback = !isLlm || summarySource == "deterministic_template_fallback"
                val summarySourceLabel = when {
                    !isFallback -> result.clinicalSummaryModelUsed ?: "Ollama LLM"
                    else -> "Template Fallback"
                }

                if (!result.clinicalSummaryText.isNullOrBlank()) {
                    appendLine("CLINICAL SUMMARY ($summarySourceLabel):")
                    if (isFallback) {
                        appendLine("[NOTE: Local LLM inactive/offline. Summary synthesized deterministically via structured templates.]")
                    }
                    appendLine(result.clinicalSummaryText)
                    appendLine("Pathologist Review Notice: Strictly investigational AI synthesis. Must be independently reviewed by a board-certified pathologist.")
                } else {
                    appendLine("CLINICAL SUMMARY: Unavailable (Diagnostic summary not yet compiled for this case).")
                }

                // Concise Tile Evidence Section (Top 5 / Top 10)
                if (result.attentionWeights.isNotEmpty()) {
                    appendLine("--------------------------------------------------")
                    val ordinaryTiles = c.images.filter { it.category == com.cancerdetection.multimodal.research.domain.model.SpecimenCategory.ORDINARY_IMAGE }
                    val weights = result.attentionWeights
                    val indexed = weights.mapIndexed { idx, w ->
                        Triple(idx, ordinaryTiles.getOrNull(idx)?.displayName ?: "Tile #${idx + 1}", w)
                    }.sortedByDescending { it.third }

                    val topK = if (weights.size > 5) 5 else weights.size
                    appendLine("TILE-LEVEL CROSS-ATTENTION EVIDENCE (Top $topK of ${weights.size} Tiles):")
                    indexed.take(topK).forEachIndexed { rank, item ->
                        appendLine(
                            String.format(
                                Locale.US,
                                "  #%d [Tile %d: %s]: %.2f%% (weight = %.5f)",
                                rank + 1,
                                item.first + 1,
                                item.second,
                                item.third * 100f,
                                item.third
                            )
                        )
                    }
                    if (weights.size > topK) {
                        appendLine("  [Remaining ${weights.size - topK} tiles with lower attention omitted for concise reporting]")
                    }
                    appendLine("Interpretation  : Relative query-to-patch affinity in cross-attention layer. Does NOT represent histological ground truth or cell-level viability.")
                }
            } else {
                appendLine("Assessment      : PENDING / DRAFT")
            }
            appendLine("--------------------------------------------------")
            appendLine("CLINICIAN REVIEW NOTES (${c.notes.size}):")
            if (c.notes.isEmpty()) {
                appendLine("No review notes recorded.")
            } else {
                c.notes.forEachIndexed { idx, note ->
                    appendLine("[${idx + 1}] (${note.category}) ${note.author}: ${note.content}")
                }
            }
            appendLine("==================================================")
            appendLine("REGULATORY & SCOPE DISCLAIMER:")
            appendLine("1. Evaluated on selected candidate image patches. Selected tiles do not represent a complete whole-slide scan or unbiased spatial distribution across tumor margins.")
            appendLine("2. This computational research report is for academic research and investigational use only. It is not approved for in vitro diagnostic use. Confirmatory testing via standard mismatch repair immunohistochemistry (MMR-IHC) and PCR/NGS microsatellite analysis is mandatory for clinical patient management.")
            appendLine("==================================================")
        }
    }
}
