package com.cancerdetection.multimodal.research.data.repository

import android.content.Context
import android.net.Uri
import com.cancerdetection.multimodal.research.data.network.InferenceApiClient
import com.cancerdetection.multimodal.research.data.storage.LocalCaseStorage
import com.cancerdetection.multimodal.research.data.storage.SpecimenIngestionManager
import com.cancerdetection.multimodal.research.domain.model.AnalysisResult
import com.cancerdetection.multimodal.research.domain.model.CaseStatus
import com.cancerdetection.multimodal.research.domain.model.ModelProvenance
import com.cancerdetection.multimodal.research.domain.model.ResearchCase
import com.cancerdetection.multimodal.research.domain.model.ReviewNote
import com.cancerdetection.multimodal.research.domain.model.SAMPLE_TCGA_REPORT_TEXT
import com.cancerdetection.multimodal.research.domain.model.SpecimenCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ResearchCaseRepository(
    private val storage: LocalCaseStorage,
    private val apiClient: InferenceApiClient = InferenceApiClient(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val _cases = MutableStateFlow<List<ResearchCase>>(emptyList())
    val cases: StateFlow<List<ResearchCase>> = _cases.asStateFlow()

    private val mutex = Mutex()
    private var isInitialized = false

    init {
        scope.launch {
            initialize()
        }
    }

    suspend fun initialize() {
        mutex.withLock {
            if (!isInitialized) {
                val loaded = storage.loadCases()
                if (loaded.isEmpty()) {
                    val demoCases = DemoDataSeeder.createInitialDemoCases()
                    _cases.value = demoCases
                    storage.saveCases(demoCases)
                } else {
                    _cases.value = loaded
                }
                isInitialized = true
            }
        }
    }

    fun getCaseById(caseId: String) = cases.map { list ->
        list.find { it.id == caseId }
    }

    suspend fun upsertCase(case: ResearchCase) {
        mutex.withLock {
            val current = _cases.value.toMutableList()
            val index = current.indexOfFirst { it.id == case.id }
            val updated = case.copy(updatedAtMillis = System.currentTimeMillis())
            if (index >= 0) {
                current[index] = updated
            } else {
                current.add(0, updated)
            }
            _cases.value = current
            storage.saveCases(current)
        }
    }

    suspend fun deleteCase(caseId: String) {
        mutex.withLock {
            val updated = _cases.value.filterNot { it.id == caseId }
            _cases.value = updated
            storage.saveCases(updated)
        }
    }

    suspend fun addNoteToCase(caseId: String, note: ReviewNote) {
        mutex.withLock {
            val current = _cases.value.toMutableList()
            val index = current.indexOfFirst { it.id == caseId }
            if (index >= 0) {
                val existing = current[index]
                val updatedNotes = existing.notes + note
                current[index] = existing.copy(
                    notes = updatedNotes,
                    updatedAtMillis = System.currentTimeMillis()
                )
                _cases.value = current
                storage.saveCases(current)
            }
        }
    }

    suspend fun updateReviewStatus(caseId: String, status: String) {
        mutex.withLock {
            val current = _cases.value.toMutableList()
            val index = current.indexOfFirst { it.id == caseId }
            if (index >= 0) {
                val existing = current[index]
                current[index] = existing.copy(
                    reviewStatus = status,
                    updatedAtMillis = System.currentTimeMillis()
                )
                _cases.value = current
                storage.saveCases(current)
            }
        }
    }

    /**
     * Executes real model inference against the Python backend API (Phikon-v2 + ClinicalBERT + Cross-Attention).
     * Sets CaseStatus.ANALYZED_VERIFIED on success, or CaseStatus.MODEL_UNAVAILABLE on connection failure.
     * Never fabricates probabilities or placeholder scores.
     */
    suspend fun executeRealInference(
        caseId: String,
        context: Context,
        serverUrl: String,
        timeoutMs: Int = 120_000,
        onProgress: ((String) -> Unit)? = null
    ): Result<ResearchCase> {
        val existingCase = _cases.value.find { it.id == caseId }
            ?: return Result.failure(Exception("Case $caseId not found in local repository"))

        // Validate clinical text
        if (existingCase.clinicalReportText.isBlank()) {
            return Result.failure(Exception("Clinical report text is required for ClinicalBERT encoding"))
        }

        // Separate ordinary image tiles from whole-slide files
        val ordinaryTiles = existingCase.images.filter { it.category == SpecimenCategory.ORDINARY_IMAGE }
        val wholeSlideFiles = existingCase.images.filter { it.category == SpecimenCategory.WHOLE_SLIDE_IMAGE }

        if (ordinaryTiles.isEmpty() && wholeSlideFiles.isEmpty()) {
            return Result.failure(Exception("Please attach at least one specimen tile or whole-slide image"))
        }

        // Mark status as ANALYZING and clear any previous analysis result to prevent stale result display
        upsertCase(existingCase.copy(status = CaseStatus.ANALYZING, result = null, errorMessage = null))

        // Preprocess ordinary tiles into 224x224 Base64 strings
        val encodedPatches = mutableListOf<String>()
        for ((idx, tile) in ordinaryTiles.withIndex()) {
            onProgress?.invoke("Preprocessing tile ${idx + 1}/${ordinaryTiles.size} (${tile.displayName})...")
            val base64Result = SpecimenIngestionManager.encodeTileToBase64(context, Uri.parse(tile.uriString))
            if (base64Result.isSuccess) {
                encodedPatches.add(base64Result.getOrThrow())
            } else {
                val err = "Failed to preprocess tile ${tile.displayName}: ${base64Result.exceptionOrNull()?.message}"
                upsertCase(existingCase.copy(status = CaseStatus.FAILED, result = null, errorMessage = err))
                return Result.failure(Exception(err))
            }
        }

        // If only WSI files were selected and no extracted tiles, dispatch notice
        if (encodedPatches.isEmpty() && wholeSlideFiles.isNotEmpty()) {
            val wsiNotice = "Whole-slide files (${wholeSlideFiles.size} attached) require backend OpenSlide patch extraction via /wsipredict. Please select extracted H&E patch tiles for direct patch-level inference."
            upsertCase(existingCase.copy(status = CaseStatus.FAILED, result = null, errorMessage = wsiNotice))
            return Result.failure(Exception(wsiNotice))
        }

        // Generate unique Request ID for this inference session
        val inferenceRequestId = java.util.UUID.randomUUID().toString()

        // Call the batched two-stage inference client supporting >100 tiles
        val apiResult = apiClient.runBatchedMultimodalInference(
            baseUrl = serverUrl,
            clinicalReport = existingCase.clinicalReportText,
            patchesBase64 = encodedPatches,
            chunkSize = 25,
            maxRetriesPerChunk = 3,
            clientRequestId = inferenceRequestId,
            onProgress = { _, _, msg ->
                onProgress?.invoke(msg)
            }
        )

        return if (apiResult.isSuccess) {
            val response = apiResult.getOrThrow()
            val prob = response.probability
            val isMsi = prob >= 0.83f

            val hasValidAttention = response.attentionWeights.isNotEmpty() && response.attentionWeights.size == ordinaryTiles.size

            // Phase 5 & 7: Automatically generate clinical summary with source tracking
            var summaryText: String? = null
            var summaryModel: String? = null
            var summarySource: String? = null
            var summaryIsLlm = false
            var summaryError: String? = null
            var summaryGenerated = false
            try {
                onProgress?.invoke("Generating AI diagnostic summary...")
                val topScores = if (hasValidAttention) {
                    response.attentionWeights.sortedDescending().take(5)
                } else emptyList()
                val summaryRes = apiClient.generateSummary(
                    baseUrl = serverUrl,
                    confidence = prob,
                    isMsi = isMsi,
                    clinicalReport = existingCase.clinicalReportText,
                    topScores = topScores,
                    allowFallback = true,
                    caseId = existingCase.caseIdentifier,
                    specimenSource = existingCase.specimenType,
                    timeoutMs = timeoutMs
                )
                if (summaryRes.isSuccess) {
                    val summaryData = summaryRes.getOrThrow()
                    summaryText = summaryData.summary
                    summaryModel = summaryData.modelUsed
                    summarySource = summaryData.source
                    summaryIsLlm = summaryData.isLlm
                    summaryError = summaryData.errorDetail
                    summaryGenerated = true
                } else {
                    summaryError = summaryRes.exceptionOrNull()?.message ?: "Summary generation failed"
                }
            } catch (e: Exception) {
                // Non-fatal if summary generation fails or times out; record the diagnostic error
                summaryError = e.message ?: "Summary generation error"
            }

            val tileMeta = SpecimenIngestionManager.extractTileMetadata(existingCase.images)
            val isSampleReport = existingCase.clinicalReportText.trim() == SAMPLE_TCGA_REPORT_TEXT.trim()
            val boundRequestId = response.requestId ?: inferenceRequestId

            val visionInfMs = response.visionInferenceMs?.let { if (it > 0.0) maxOf(1L, Math.round(it)) else null }
            val clinicalTextInfMs = response.clinicalTextInferenceMs?.let { if (it > 0.0) maxOf(1L, Math.round(it)) else null }
            val fusionClassInfMs = response.fusionClassificationMs?.let { if (it > 0.0) maxOf(1L, Math.round(it)) else null }
            val derivedComponentTotalMs = ((visionInfMs ?: 0L) + (clinicalTextInfMs ?: 0L) + (fusionClassInfMs ?: 0L)).takeIf { it > 0L }
            val infLatencyMs = (response.totalModelInferenceMs?.let { if (it > 0.0) maxOf(1L, Math.round(it)) else null }) ?: derivedComponentTotalMs

            android.util.Log.i(
                "DIAGNOSTIC_TRACE",
                "[STAGE 4 - Repository Result Constructed] responseTotalMs=${response.totalModelInferenceMs}, visionInfMs=$visionInfMs, clinicalTextInfMs=$clinicalTextInfMs, fusionClassInfMs=$fusionClassInfMs, derivedComponentTotalMs=$derivedComponentTotalMs, infLatencyMs=$infLatencyMs"
            )
            apiClient.sendDiagnosticTrace(serverUrl, 4, "Repository Result Constructed", mapOf("responseTotalMs" to response.totalModelInferenceMs, "visionInfMs" to visionInfMs, "clinicalTextInfMs" to clinicalTextInfMs, "fusionClassInfMs" to fusionClassInfMs, "derivedComponentTotalMs" to derivedComponentTotalMs, "infLatencyMs" to infLatencyMs))

            val result = AnalysisResult(
                isMsi = isMsi,
                msiProbability = prob,
                classificationLabel = if (isMsi) "Model prediction: MSI-H" else "Model prediction: MSS",
                decisionThresholdApplied = 0.83,
                isSimulated = false,
                provenance = ModelProvenance(
                    fusionArchitecture = "SummaryToPatchCrossAttention (5,250,561 params)",
                    fusionCheckpoint = "models/fusion_model_v1_1.pth",
                    visionBackbone = "Phikon-v2 (owkin/phikon-v2, 1024-D)",
                    textBackbone = "Bio_ClinicalBERT (110M params, 768-D [CLS])",
                    decisionThresholdTau = 0.83,
                    inferenceEnvironment = "Live Python Inference API",
                    serverEndpoint = response.serverUrl,
                    latencyMs = response.latencyMs,
                    isRealInference = true,
                    visionTensorShape = response.visionShape.toString(),
                    clinicalTensorShape = response.clinicalShape.toString(),
                    tileIdentityStatus = tileMeta.identityStatus,
                    detectedTileCaseId = tileMeta.detectedCaseId,
                    detectedTileSlideId = tileMeta.detectedSlideId,
                    isSampleReportUsed = isSampleReport,
                    requestId = boundRequestId,
                    tileCount = response.tileCount ?: ordinaryTiles.size,
                    reportSha256 = response.reportSha256,
                    rawLogit = response.rawLogit,
                    inferenceLatencyMs = infLatencyMs,
                    visionInferenceMs = visionInfMs,
                    clinicalTextInferenceMs = clinicalTextInfMs,
                    fusionClassificationMs = fusionClassInfMs,
                    inferenceTimingScope = response.inferenceTimingScope
                ),
                requestId = boundRequestId,
                visionShape = response.visionShape,
                clinicalShape = response.clinicalShape,
                attentionWeights = if (hasValidAttention) response.attentionWeights else emptyList(),
                attentionDistributionAvailable = hasValidAttention,
                cellularHeatmapAvailable = true,
                clinicalSummaryGenerated = summaryGenerated,
                clinicalSummaryText = summaryText,
                clinicalSummaryModelUsed = summaryModel,
                clinicalSummarySource = summarySource,
                clinicalSummaryIsLlm = summaryIsLlm,
                clinicalSummaryError = summaryError,
                inferenceLatencyMs = infLatencyMs,
                explainabilityNote = if (hasValidAttention) {
                    "Tile-level relative cross-attention weights extracted directly from SummaryToPatchCrossAttention layer. Phikon-v2 ViT attention rollout cellular heatmaps available on demand."
                } else {
                    "Attention evidence unavailable for this run (weights omitted or tile count mismatch)."
                },
                completedAtMillis = System.currentTimeMillis()
            )

            android.util.Log.i(
                "DIAGNOSTIC_TRACE",
                "[STAGE 5 - ExecutionProvenance Populated] provenance.inferenceLatencyMs=${result.provenance.inferenceLatencyMs}, provenance.latencyMs=${result.provenance.latencyMs}, result.inferenceLatencyMs=${result.inferenceLatencyMs}"
            )
            apiClient.sendDiagnosticTrace(serverUrl, 5, "ExecutionProvenance Populated", mapOf("provenanceInferenceLatencyMs" to result.provenance.inferenceLatencyMs, "provenanceLatencyMs" to result.provenance.latencyMs, "resultInferenceLatencyMs" to result.inferenceLatencyMs))

            val analyzedCase = existingCase.copy(
                status = CaseStatus.ANALYZED_VERIFIED,
                result = result,
                serverEndpointUsed = response.serverUrl,
                errorMessage = null,
                reviewStatus = "Under Review",
                updatedAtMillis = System.currentTimeMillis()
            )
            upsertCase(analyzedCase)
            Result.success(analyzedCase)
        } else {
            val exception = apiResult.exceptionOrNull() ?: Exception("Unknown inference failure")
            val offlineCase = existingCase.copy(
                status = CaseStatus.MODEL_UNAVAILABLE,
                result = null,
                errorMessage = "Inference server offline or error: ${exception.message}",
                serverEndpointUsed = serverUrl,
                updatedAtMillis = System.currentTimeMillis()
            )
            upsertCase(offlineCase)
            Result.failure(exception)
        }
    }

    /**
     * Executes simulated UI analysis for demo verification cases only.
     * Clearly labels result with isSimulated = true.
     */
    suspend fun simulateAnalysis(caseId: String): ResearchCase? {
        mutex.withLock {
            val current = _cases.value.toMutableList()
            val index = current.indexOfFirst { it.id == caseId }
            if (index < 0) return null

            val existing = current[index]
            val hash = (existing.caseIdentifier.hashCode() + existing.clinicalReportText.hashCode()).let {
                if (it < 0) -it else it
            }
            val simulatedProb = ((hash % 75) + 15) / 100.0f
            val isMsi = simulatedProb >= 0.83f

            val patchCount = existing.images.size.coerceAtLeast(1)
            val simWeights = if (patchCount == 1) {
                listOf(1.0f)
            } else {
                val raw = List(patchCount) { i -> (i + 1).toFloat() }
                val sum = raw.sum()
                raw.map { it / sum }
            }

            val simSummary = """
### A. Case Overview
- **Case Identifier:** ${existing.caseIdentifier}
- **Specimen Source & Staining:** Surgical Resection (H&E)
- **Summary Generation Status:** Simulated Research Scribe

### B. Documented Clinical Findings
| Documented Parameter | Source Finding |
|---|---|
| Primary Site | ${if (existing.clinicalReportText.contains("cecum", ignoreCase = true)) "Cecum" else if (existing.clinicalReportText.contains("colon", ignoreCase = true)) "Colon" else "Not stated in supplied report"} |
| Histology | ${if (existing.clinicalReportText.contains("adenocarcinoma", ignoreCase = true)) "Adenocarcinoma" else "Not stated in supplied report"} |
| Tumour Differentiation | ${if (existing.clinicalReportText.contains("moderately", ignoreCase = true)) "Moderately differentiated" else "Not stated in supplied report"} |
| Tumour Extension / Pathological Stage | ${if (existing.clinicalReportText.contains("pt3", ignoreCase = true)) "pT3" else "Not stated in supplied report"} |
| Resection Margins | ${if (existing.clinicalReportText.contains("negative", ignoreCase = true)) "Resection margins negative" else "Not stated in supplied report"} |
| Lymph-Node Findings | ${if (existing.clinicalReportText.contains("0/", ignoreCase = true)) "0 positive for metastasis (no nodal metastasis)" else "Not stated in supplied report"} |
| Peritoneal Involvement | Not stated in supplied report |
| Other Documented Findings | None documented |

### C. Computational Multimodal Prediction
| Metric | Research Finding |
|---|---|
| Predicted MSI/MSS Class | ${if (isMsi) "MSI-High (Microsatellite Instability)" else "MSS (Microsatellite Stable)"} |
| Estimated MSI Probability | ${String.format(java.util.Locale.US, "%.1f%%", simulatedProb * 100f)} |
| Research Classification Threshold | tau = 0.83 (configured decision threshold) |
| Interpretation Status | Computational research output; not a confirmed diagnosis |

Note: The estimated MSI probability reflects computational multimodal query-key affinity and is not a measure of confidence in the predicted class. The threshold tau = 0.83 is a configured research parameter, not a clinically validated cut-off.

### D. Top Cross-Attention Scores
| Tile Rank | Attention Weight |
|---|---:|
${simWeights.take(5).mapIndexed { idx, w -> "| Tile #${idx + 1} | ${String.format(java.util.Locale.US, "%.4f", w)} |" }.joinToString("\n")}

Note: Attention weights describe computational emphasis across input tiles and do not independently identify histological features or anatomical locations.

### E. Limitations & Verification Disclaimer
This is an investigational research result, not a confirmed clinical diagnosis. MSI/MSS status requires appropriate independent verification by qualified professionals.
""".trimIndent()

            val tileMeta = SpecimenIngestionManager.extractTileMetadata(existing.images)
            val isSampleReport = existing.clinicalReportText.trim() == SAMPLE_TCGA_REPORT_TEXT.trim()

            val result = AnalysisResult(
                isMsi = isMsi,
                msiProbability = simulatedProb,
                classificationLabel = if (isMsi) "Model prediction: MSI-H" else "Model prediction: MSS",
                decisionThresholdApplied = 0.83,
                isSimulated = true,
                provenance = ModelProvenance(
                    fusionArchitecture = "SummaryToPatchCrossAttention (5.25M params)",
                    fusionCheckpoint = "models/fusion_model_v1_1.pth",
                    visionBackbone = "Phikon-v2 (owkin/phikon-v2, 1024-D)",
                    textBackbone = "Bio_ClinicalBERT (110M params, 768-D [CLS])",
                    decisionThresholdTau = 0.83,
                    inferenceEnvironment = "Simulated UI Demonstration (Phase 1 Scaffold)",
                    serverEndpoint = "Offline UI Simulator",
                    tileIdentityStatus = tileMeta.identityStatus,
                    detectedTileCaseId = tileMeta.detectedCaseId,
                    detectedTileSlideId = tileMeta.detectedSlideId,
                    isSampleReportUsed = isSampleReport
                ),
                visionShape = listOf(patchCount, 1024),
                clinicalShape = listOf(1, 768),
                attentionWeights = simWeights,
                attentionDistributionAvailable = true,
                cellularHeatmapAvailable = false,
                clinicalSummaryGenerated = true,
                clinicalSummaryText = simSummary,
                clinicalSummaryModelUsed = "Simulation Scribe",
                explainabilityNote = "Demonstration score for UI verification. Attention weights are simulated across $patchCount tiles. Not a verified model result.",
                completedAtMillis = System.currentTimeMillis()
            )

            val updated = existing.copy(
                status = CaseStatus.ANALYZED_SIMULATED,
                result = result,
                reviewStatus = "Under Review",
                updatedAtMillis = System.currentTimeMillis()
            )
            current[index] = updated
            storage.saveCases(current)
            _cases.value = current
            return updated
        }
    }

    suspend fun generateTileHeatmap(serverUrl: String, patchBase64: String): Result<String> {
        return apiClient.generateHeatmap(serverUrl, patchBase64)
    }

    suspend fun generateSummary(
        serverUrl: String,
        confidence: Float,
        isMsi: Boolean,
        clinicalReport: String,
        topScores: List<Float>,
        allowFallback: Boolean = true,
        caseId: String? = null,
        specimenSource: String? = null,
        timeoutMs: Int = 120_000
    ): Result<com.cancerdetection.multimodal.research.data.network.SummaryResponseData> {
        return apiClient.generateSummary(serverUrl, confidence, isMsi, clinicalReport, topScores, allowFallback, caseId, specimenSource, timeoutMs)
    }

    suspend fun checkLlmHealth(serverUrl: String): Result<com.cancerdetection.multimodal.research.data.network.LlmHealthData> {
        return apiClient.checkLlmHealth(serverUrl)
    }

    companion object {
        @Volatile
        private var INSTANCE: ResearchCaseRepository? = null

        fun getInstance(storage: LocalCaseStorage): ResearchCaseRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ResearchCaseRepository(storage).also { INSTANCE = it }
            }
        }
    }
}
