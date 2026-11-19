package com.cancerdetection.multimodal.research.ui.screens.newanalysis

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cancerdetection.multimodal.research.data.network.InferenceApiClient
import com.cancerdetection.multimodal.research.data.network.InferenceConfig
import com.cancerdetection.multimodal.research.data.repository.ResearchCaseRepository
import com.cancerdetection.multimodal.research.data.storage.SpecimenIngestionManager
import com.cancerdetection.multimodal.research.domain.model.CaseStatus
import com.cancerdetection.multimodal.research.domain.model.ResearchCase
import com.cancerdetection.multimodal.research.domain.model.SAMPLE_TCGA_CASE_ID
import com.cancerdetection.multimodal.research.domain.model.SAMPLE_TCGA_REPORT_TEXT
import com.cancerdetection.multimodal.research.domain.model.SpecimenImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.UUID

data class NewAnalysisUiState(
    val editingCaseId: String? = null,
    val caseIdentifier: String = "",
    val specimenType: String = "Colorectal Adenocarcinoma Resection (H&E)",
    val clinicalReportText: String = "",
    val images: List<SpecimenImage> = emptyList(),
    val serverUrl: String = InferenceConfig.DEFAULT_SERVER_URL,
    val isServerOnline: Boolean? = null,
    val serverStatusMessage: String? = null,
    val isCheckingServer: Boolean = false,
    val isSaving: Boolean = false,
    val isAnalyzing: Boolean = false,
    val analysisProgressMessage: String? = null,
    val validationError: String? = null,
    val analysisSuccessCaseId: String? = null,
    val folderIngestionNotice: String? = null,
    val isIngestingFolder: Boolean = false,
    val selectedFolderName: String? = null,
    // Input integrity & metadata safeguards
    val isSampleReportActive: Boolean = false,
    val sampleReportConfirmedForCase: Boolean = false,
    val detectedTileCaseId: String? = null,
    val detectedTileSlideId: String? = null,
    val tileIdentityStatus: String = "No specimen tiles selected",
    val caseIdConflictWarning: String? = null
)

class NewAnalysisViewModel(
    private val repository: ResearchCaseRepository,
    private val inferenceConfig: InferenceConfig
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        NewAnalysisUiState(serverUrl = inferenceConfig.getServerBaseUrl())
    )
    val uiState: StateFlow<NewAnalysisUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            inferenceConfig.serverUrlFlow.collect { url ->
                _uiState.value = _uiState.value.copy(serverUrl = url)
            }
        }
    }

    fun loadCase(caseId: String?) {
        if (caseId.isNullOrBlank()) return
        viewModelScope.launch {
            val existing = repository.getCaseById(caseId).firstOrNull() ?: return@launch
            val isSample = existing.clinicalReportText.trim() == SAMPLE_TCGA_REPORT_TEXT.trim()
            _uiState.value = _uiState.value.copy(
                editingCaseId = existing.id,
                caseIdentifier = existing.caseIdentifier,
                specimenType = existing.specimenType,
                clinicalReportText = existing.clinicalReportText,
                images = existing.images,
                isSampleReportActive = isSample,
                sampleReportConfirmedForCase = false
            )
            refreshMetadataAndConflictWarnings()
        }
    }

    fun updateCaseIdentifier(id: String) {
        _uiState.value = _uiState.value.copy(
            caseIdentifier = id,
            validationError = null
        )
        refreshMetadataAndConflictWarnings()
    }

    fun updateSpecimenType(type: String) {
        _uiState.value = _uiState.value.copy(specimenType = type)
    }

    fun updateClinicalReport(text: String) {
        val isStillSample = _uiState.value.isSampleReportActive &&
            text.trim() == SAMPLE_TCGA_REPORT_TEXT.trim()
        _uiState.value = _uiState.value.copy(
            clinicalReportText = text,
            isSampleReportActive = isStillSample,
            sampleReportConfirmedForCase = if (!isStillSample) false else _uiState.value.sampleReportConfirmedForCase,
            validationError = null
        )
        refreshMetadataAndConflictWarnings()
    }

    private fun refreshMetadataAndConflictWarnings() {
        val state = _uiState.value
        val metadata = SpecimenIngestionManager.extractTileMetadata(state.images)
        val trimmedCaseId = state.caseIdentifier.trim().uppercase()

        val warning = when {
            metadata.uniqueCaseIds.size > 1 -> {
                "Metadata Conflict: Selected tiles contain multiple patient IDs: ${metadata.uniqueCaseIds.sorted().joinToString()}"
            }
            metadata.detectedCaseId != null && trimmedCaseId.isNotBlank() &&
                metadata.detectedCaseId.uppercase() != trimmedCaseId -> {
                "Input Mismatch Warning: Entered Case ID '${state.caseIdentifier.trim()}' does not match detected tile metadata '${metadata.detectedCaseId}'."
            }
            else -> null
        }

        _uiState.value = state.copy(
            detectedTileCaseId = metadata.detectedCaseId,
            detectedTileSlideId = metadata.detectedSlideId,
            tileIdentityStatus = metadata.identityStatus,
            caseIdConflictWarning = warning
        )
    }

    fun updateServerUrl(url: String) {
        inferenceConfig.setServerBaseUrl(url)
        _uiState.value = _uiState.value.copy(
            serverUrl = url,
            isServerOnline = null,
            serverStatusMessage = null
        )
    }

    fun checkServerConnection() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isCheckingServer = true,
                serverStatusMessage = "Testing connection to ${_uiState.value.serverUrl}..."
            )
            val client = InferenceApiClient()
            val result = client.checkHealth(_uiState.value.serverUrl)
            if (result.isSuccess && result.getOrNull() == true) {
                _uiState.value = _uiState.value.copy(
                    isCheckingServer = false,
                    isServerOnline = true,
                    serverStatusMessage = "Online (SummaryToPatchCrossAttention ready)"
                )
            } else {
                val err = result.exceptionOrNull()?.message ?: "Host unreachable"
                _uiState.value = _uiState.value.copy(
                    isCheckingServer = false,
                    isServerOnline = false,
                    serverStatusMessage = "Unreachable: $err"
                )
            }
        }
    }

    fun ingestTileFolder(treeUri: Uri, context: Context) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isIngestingFolder = true,
                validationError = null,
                folderIngestionNotice = null
            )
            try {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}

                when (val result = SpecimenIngestionManager.ingestFolder(context, treeUri)) {
                    is SpecimenIngestionManager.FolderIngestionResult.Success -> {
                        val notice = buildString {
                            append("Loaded ${result.images.size} tile(s) from '${result.folderName}'.")
                            if (result.skippedNonImages > 0) {
                                append(" (${result.skippedNonImages} non-image file(s) skipped)")
                            }
                            if (result.unreadableFiles.isNotEmpty()) {
                                append(" [${result.unreadableFiles.size} unreadable file(s) skipped]")
                            }
                        }
                        _uiState.value = _uiState.value.copy(
                            images = result.images,
                            selectedFolderName = result.folderName,
                            folderIngestionNotice = notice,
                            isIngestingFolder = false,
                            validationError = null
                        )
                        refreshMetadataAndConflictWarnings()
                    }
                    is SpecimenIngestionManager.FolderIngestionResult.Empty -> {
                        _uiState.value = _uiState.value.copy(
                            isIngestingFolder = false,
                            validationError = "No valid JPG, JPEG, or PNG tiles found in folder '${result.folderName}' (${result.totalFilesChecked} files scanned)."
                        )
                    }
                    is SpecimenIngestionManager.FolderIngestionResult.UnsupportedProvider -> {
                        _uiState.value = _uiState.value.copy(
                            isIngestingFolder = false,
                            validationError = result.message
                        )
                    }
                    is SpecimenIngestionManager.FolderIngestionResult.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isIngestingFolder = false,
                            validationError = result.message
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isIngestingFolder = false,
                    validationError = "Failed to access folder: ${e.localizedMessage ?: "Unknown error"}"
                )
            }
        }
    }

    fun addImagesFromUris(uris: List<Uri>, context: Context) {
        if (uris.isEmpty()) return
        val currentImages = _uiState.value.images.toMutableList()
        val existingDisplayNames = currentImages.map { it.displayName }.toSet()
        val existingUris = currentImages.map { it.uriString }.toSet()

        var duplicateCount = 0
        var addedCount = 0
        val newImages = mutableListOf<SpecimenImage>()

        for (uri in uris) {
            if (currentImages.size + newImages.size >= SpecimenIngestionManager.MAX_SINGLE_SELECTION_TILES) {
                break
            }
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}

            val inspected = SpecimenIngestionManager.inspectUri(context, uri)
            if (existingDisplayNames.contains(inspected.displayName) || existingUris.contains(inspected.uriString)) {
                duplicateCount++
            } else if (inspected.category == com.cancerdetection.multimodal.research.domain.model.SpecimenCategory.UNSUPPORTED) {
                // Skip unsupported format
            } else {
                newImages.add(inspected)
                addedCount++
            }
        }

        val combined = (currentImages + newImages).sortedWith { a, b ->
            SpecimenIngestionManager.naturalCompare(a.displayName, b.displayName)
        }

        val notice = buildString {
            if (addedCount > 0) append("Added $addedCount image tile(s). ")
            if (duplicateCount > 0) append("$duplicateCount duplicate(s) ignored. ")
            if (uris.size > addedCount + duplicateCount && combined.size >= SpecimenIngestionManager.MAX_SINGLE_SELECTION_TILES) {
                append("Manual picker limit of ${SpecimenIngestionManager.MAX_SINGLE_SELECTION_TILES} tiles reached (use 'Select Tile Folder' for larger sets).")
            }
        }.trim()

        _uiState.value = _uiState.value.copy(
            images = combined,
            folderIngestionNotice = if (notice.isNotBlank()) notice else _uiState.value.folderIngestionNotice,
            validationError = null
        )
        refreshMetadataAndConflictWarnings()
    }

    fun clearImages() {
        _uiState.value = _uiState.value.copy(
            images = emptyList(),
            selectedFolderName = null,
            folderIngestionNotice = null,
            validationError = null
        )
        refreshMetadataAndConflictWarnings()
    }

    fun removeImage(imageId: String) {
        _uiState.value = _uiState.value.copy(
            images = _uiState.value.images.filterNot { it.id == imageId }
        )
        refreshMetadataAndConflictWarnings()
    }

    fun loadSampleTcgaReport() {
        _uiState.value = _uiState.value.copy(
            caseIdentifier = SAMPLE_TCGA_CASE_ID,
            specimenType = "Colorectal Adenocarcinoma Resection (H&E)",
            clinicalReportText = SAMPLE_TCGA_REPORT_TEXT,
            isSampleReportActive = true,
            sampleReportConfirmedForCase = false,
            validationError = null
        )
        refreshMetadataAndConflictWarnings()
    }

    fun confirmUseSampleReport() {
        _uiState.value = _uiState.value.copy(
            sampleReportConfirmedForCase = true,
            validationError = null
        )
    }

    fun clearSampleReport() {
        _uiState.value = _uiState.value.copy(
            clinicalReportText = "",
            isSampleReportActive = false,
            sampleReportConfirmedForCase = false,
            validationError = null
        )
        refreshMetadataAndConflictWarnings()
    }

    fun validate(): Boolean {
        val state = _uiState.value
        if (state.caseIdentifier.trim().isBlank()) {
            _uiState.value = state.copy(validationError = "Please enter a valid Research Case Identifier.")
            return false
        }
        if (state.clinicalReportText.trim().isBlank()) {
            _uiState.value = state.copy(validationError = "Please provide clinical pathology text findings.")
            return false
        }
        if (state.images.isEmpty()) {
            _uiState.value = state.copy(validationError = "Please select at least one specimen histopathology tile image.")
            return false
        }

        // Safeguard 1: Sample report mismatch check
        if (state.isSampleReportActive && !state.sampleReportConfirmedForCase) {
            val enteredCase = state.caseIdentifier.trim().uppercase()
            if (enteredCase != SAMPLE_TCGA_CASE_ID.uppercase()) {
                _uiState.value = state.copy(
                    validationError = "Sample Report Warning: Active clinical report is the '$SAMPLE_TCGA_CASE_ID' sample text, which does not match case '${state.caseIdentifier.trim()}'. Please replace with authentic report text or explicitly confirm sample report use."
                )
                return false
            }
        }

        // Folder ingestion supports arbitrary tile counts; picker enforces limit at selection time.
        _uiState.value = state.copy(validationError = null)
        return true
    }

    fun saveDraft(onSaved: (String) -> Unit) {
        val state = _uiState.value
        if (state.caseIdentifier.trim().isBlank()) {
            _uiState.value = state.copy(validationError = "Please enter a Case Identifier to save draft.")
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isSaving = true)
            val caseId = state.editingCaseId ?: UUID.randomUUID().toString()
            val newCase = ResearchCase(
                id = caseId,
                caseIdentifier = state.caseIdentifier.trim(),
                specimenType = state.specimenType,
                clinicalReportText = state.clinicalReportText.trim(),
                images = state.images,
                status = CaseStatus.DRAFT,
                reviewStatus = "Draft"
            )
            repository.upsertCase(newCase)
            _uiState.value = state.copy(isSaving = false)
            onSaved(caseId)
        }
    }

    fun analyzeSpecimen(
        context: Context,
        forceSimulation: Boolean = false,
        onComplete: (String) -> Unit
    ) {
        if (!validate()) return

        val state = _uiState.value
        // Prevent concurrent duplicate analysis calls
        if (state.isAnalyzing) {
            Log.w("NewAnalysisViewModel", "analyzeSpecimen ignored: analysis already in progress")
            return
        }

        val caseId = state.editingCaseId ?: UUID.randomUUID().toString()
        Log.d("NewAnalysisViewModel", "analyzeSpecimen started: caseId=$caseId, tilesCount=${state.images.size}, forceSimulation=$forceSimulation")

        viewModelScope.launch {
            try {
                _uiState.value = state.copy(
                    isAnalyzing = true,
                    analysisProgressMessage = "Initializing multimodal inference..."
                )

                val initialCase = ResearchCase(
                    id = caseId,
                    caseIdentifier = state.caseIdentifier.trim(),
                    specimenType = state.specimenType,
                    clinicalReportText = state.clinicalReportText.trim(),
                    images = state.images,
                    status = CaseStatus.ANALYZING,
                    reviewStatus = "Under Review"
                )
                repository.upsertCase(initialCase)

                val completedCase = if (forceSimulation) {
                    repository.simulateAnalysis(caseId)
                } else {
                    val inferenceResult = repository.executeRealInference(
                        caseId = caseId,
                        context = context,
                        serverUrl = state.serverUrl,
                        timeoutMs = inferenceConfig.getSummaryTimeoutMs(),
                        onProgress = { progressMsg ->
                            _uiState.value = _uiState.value.copy(analysisProgressMessage = progressMsg)
                        }
                    )
                    if (inferenceResult.isFailure) {
                        val errMsg = inferenceResult.exceptionOrNull()?.message ?: "Inference server error"
                        Log.e("NewAnalysisViewModel", "Real inference failed: $errMsg")
                        _uiState.value = _uiState.value.copy(
                            isAnalyzing = false,
                            analysisProgressMessage = null,
                            validationError = "Inference execution failed: $errMsg. Please verify server connection and retry."
                        )
                        return@launch
                    }
                    inferenceResult.getOrThrow()
                }

                val finalId = completedCase?.id ?: caseId
                Log.d("NewAnalysisViewModel", "analyzeSpecimen completed successfully: finalId=$finalId, requestId=${completedCase?.result?.requestId}")
                _uiState.value = _uiState.value.copy(
                    isAnalyzing = false,
                    analysisProgressMessage = null,
                    analysisSuccessCaseId = finalId
                )
                onComplete(finalId)
            } catch (e: Exception) {
                Log.e("NewAnalysisViewModel", "analyzeSpecimen failed: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    isAnalyzing = false,
                    analysisProgressMessage = null,
                    validationError = "Inference execution failed: ${e.message}"
                )
            } finally {
                // Ensure loading state terminates under all circumstances (error, cancellation, timeout)
                _uiState.value = _uiState.value.copy(
                    isAnalyzing = false,
                    analysisProgressMessage = null
                )
            }
        }
    }
}
