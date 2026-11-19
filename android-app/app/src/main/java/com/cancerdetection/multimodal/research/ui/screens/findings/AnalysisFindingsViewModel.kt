package com.cancerdetection.multimodal.research.ui.screens.findings

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cancerdetection.multimodal.research.data.network.InferenceConfig
import com.cancerdetection.multimodal.research.data.repository.ResearchCaseRepository
import com.cancerdetection.multimodal.research.data.storage.SpecimenIngestionManager
import com.cancerdetection.multimodal.research.domain.model.ResearchCase
import com.cancerdetection.multimodal.research.domain.model.SpecimenImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

data class FindingsUiState(
    val researchCase: ResearchCase? = null,
    val isLoading: Boolean = true,
    val isRetrying: Boolean = false,
    val errorMessage: String? = null,
    // Phase 4 & 7 Heatmap inspection state
    val inspectingTile: SpecimenImage? = null,
    val isGeneratingHeatmap: Boolean = false,
    val heatmapBase64: String? = null,
    val heatmapError: String? = null,
    val isHeatmapExpanded: Boolean = false,
    // Phase 5 & 7 Clinical summary generation state
    val isGeneratingSummary: Boolean = false,
    val isReportTextExpanded: Boolean = false,
    val summaryFeedbackMessage: String? = null,
    val llmHealth: com.cancerdetection.multimodal.research.data.network.LlmHealthData? = null,
    val isCheckingLlm: Boolean = false
)

class AnalysisFindingsViewModel(
    private val repository: ResearchCaseRepository,
    private val inferenceConfig: InferenceConfig
) : ViewModel() {

    private val _uiState = MutableStateFlow(FindingsUiState())
    val uiState: StateFlow<FindingsUiState> = _uiState.asStateFlow()

    fun loadCase(caseId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val caseItem = repository.getCaseById(caseId).firstOrNull()
            if (caseItem != null) {
                _uiState.value = FindingsUiState(researchCase = caseItem, isLoading = false)
            } else {
                _uiState.value = FindingsUiState(
                    researchCase = null,
                    isLoading = false,
                    errorMessage = "Research case '$caseId' could not be found."
                )
            }
        }
    }

    fun retryInference(context: Context) {
        val currentCase = _uiState.value.researchCase ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRetrying = true, errorMessage = null)
            val serverUrl = inferenceConfig.getServerBaseUrl()
            val result = repository.executeRealInference(
                caseId = currentCase.id,
                context = context,
                serverUrl = serverUrl
            )
            if (result.isSuccess) {
                val updatedCase = result.getOrThrow()
                _uiState.value = _uiState.value.copy(
                    isRetrying = false,
                    researchCase = updatedCase,
                    errorMessage = null
                )
            } else {
                val err = result.exceptionOrNull()?.message ?: "Retry failed"
                val updatedCase = repository.getCaseById(currentCase.id).firstOrNull()
                _uiState.value = _uiState.value.copy(
                    isRetrying = false,
                    researchCase = updatedCase ?: currentCase,
                    errorMessage = "Inference retry failed: $err"
                )
            }
        }
    }

    fun openTileHeatmap(context: Context, tile: SpecimenImage) {
        _uiState.value = _uiState.value.copy(
            inspectingTile = tile,
            isGeneratingHeatmap = true,
            heatmapBase64 = null,
            heatmapError = null,
            isHeatmapExpanded = false
        )
        viewModelScope.launch {
            val serverUrl = inferenceConfig.getServerBaseUrl()
            val base64Result = SpecimenIngestionManager.encodeTileToBase64(context, Uri.parse(tile.uriString))
            if (base64Result.isFailure) {
                _uiState.value = _uiState.value.copy(
                    isGeneratingHeatmap = false,
                    heatmapError = "Failed to preprocess tile: ${base64Result.exceptionOrNull()?.message}"
                )
                return@launch
            }

            val patchBase64 = base64Result.getOrThrow()
            val heatmapResult = repository.generateTileHeatmap(serverUrl, patchBase64)
            if (heatmapResult.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isGeneratingHeatmap = false,
                    heatmapBase64 = heatmapResult.getOrThrow()
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isGeneratingHeatmap = false,
                    heatmapError = heatmapResult.exceptionOrNull()?.message ?: "Heatmap generation failed"
                )
            }
        }
    }

    fun closeTileHeatmap() {
        _uiState.value = _uiState.value.copy(
            inspectingTile = null,
            isGeneratingHeatmap = false,
            heatmapBase64 = null,
            heatmapError = null,
            isHeatmapExpanded = false
        )
    }

    fun openExpandedHeatmap() {
        _uiState.value = _uiState.value.copy(isHeatmapExpanded = true)
    }

    fun closeExpandedHeatmap() {
        _uiState.value = _uiState.value.copy(isHeatmapExpanded = false)
    }

    fun toggleReportTextExpanded() {
        _uiState.value = _uiState.value.copy(isReportTextExpanded = !_uiState.value.isReportTextExpanded)
    }

    fun checkLlmStatus() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCheckingLlm = true)
            val serverUrl = inferenceConfig.getServerBaseUrl()
            val healthRes = repository.checkLlmHealth(serverUrl)
            _uiState.value = _uiState.value.copy(
                isCheckingLlm = false,
                llmHealth = healthRes.getOrNull()
            )
        }
    }

    fun generateClinicalSummary(allowFallback: Boolean = true) {
        val currentCase = _uiState.value.researchCase ?: return
        val currentResult = currentCase.result ?: return
        val targetCaseId = currentCase.id

        // Requirement 3: Avoid concurrent duplicate summary requests
        if (_uiState.value.isGeneratingSummary) {
            Log.w("AnalysisFindingsVM", "generateClinicalSummary ignored: summary generation already running for case $targetCaseId")
            return
        }

        Log.d("AnalysisFindingsVM", "generateClinicalSummary started: caseId=$targetCaseId, allowFallback=$allowFallback")

        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    isGeneratingSummary = true,
                    summaryFeedbackMessage = null
                )

                val serverUrl = inferenceConfig.getServerBaseUrl()
                val topScores = currentResult.attentionWeights.sortedDescending().take(5)
                val summaryResult = repository.generateSummary(
                    serverUrl = serverUrl,
                    confidence = currentResult.msiProbability,
                    isMsi = currentResult.isMsi,
                    clinicalReport = currentCase.clinicalReportText,
                    topScores = topScores,
                    allowFallback = allowFallback,
                    caseId = currentCase.caseIdentifier,
                    specimenSource = currentCase.specimenType,
                    timeoutMs = inferenceConfig.getSummaryTimeoutMs()
                )

                // Requirement 8: Prevent older summary response from overwriting newer active case
                if (_uiState.value.researchCase?.id != targetCaseId) {
                    Log.w("AnalysisFindingsVM", "generateClinicalSummary response discarded: case switched from $targetCaseId to ${_uiState.value.researchCase?.id}")
                    return@launch
                }

                if (summaryResult.isSuccess) {
                    val summaryData = summaryResult.getOrThrow()
                    val updatedResult = currentResult.copy(
                        clinicalSummaryGenerated = true,
                        clinicalSummaryText = summaryData.summary,
                        clinicalSummaryModelUsed = summaryData.modelUsed,
                        clinicalSummarySource = summaryData.source,
                        clinicalSummaryIsLlm = summaryData.isLlm,
                        clinicalSummaryError = summaryData.errorDetail
                    )
                    val updatedCase = currentCase.copy(result = updatedResult)
                    repository.upsertCase(updatedCase)
                    val statusLabel = if (summaryData.isLlm) "via LLM (${summaryData.modelUsed})" else "via Template Fallback"
                    Log.d("AnalysisFindingsVM", "generateClinicalSummary succeeded for case $targetCaseId $statusLabel")
                    _uiState.value = _uiState.value.copy(
                        researchCase = updatedCase,
                        isGeneratingSummary = false,
                        summaryFeedbackMessage = "Summary updated $statusLabel"
                    )
                } else {
                    val err = summaryResult.exceptionOrNull()?.message ?: "Summary generation failed"
                    Log.w("AnalysisFindingsVM", "generateClinicalSummary failed for case $targetCaseId: $err")
                    val updatedResult = currentResult.copy(
                        clinicalSummaryError = err
                    )
                    val updatedCase = currentCase.copy(result = updatedResult)
                    repository.upsertCase(updatedCase)
                    _uiState.value = _uiState.value.copy(
                        researchCase = updatedCase,
                        isGeneratingSummary = false,
                        summaryFeedbackMessage = "Summary generation failed: $err"
                    )
                }
            } catch (e: Exception) {
                Log.e("AnalysisFindingsVM", "generateClinicalSummary exception for case $targetCaseId: ${e.message}", e)
                if (_uiState.value.researchCase?.id == targetCaseId) {
                    _uiState.value = _uiState.value.copy(
                        isGeneratingSummary = false,
                        summaryFeedbackMessage = "Summary generation error: ${e.message}"
                    )
                }
            } finally {
                // Requirement 7: Guarantee loading state termination
                _uiState.value = _uiState.value.copy(isGeneratingSummary = false)
            }
        }
    }

    fun clearFeedbackMessage() {
        _uiState.value = _uiState.value.copy(summaryFeedbackMessage = null)
    }
}
