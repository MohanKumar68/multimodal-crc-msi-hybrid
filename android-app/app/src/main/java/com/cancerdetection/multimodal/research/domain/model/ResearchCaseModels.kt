package com.cancerdetection.multimodal.research.domain.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class CaseStatus {
    DRAFT,
    ANALYZING,
    ANALYZED_SIMULATED,
    ANALYZED_VERIFIED,
    MODEL_UNAVAILABLE,
    FAILED
}

@Serializable
enum class SpecimenCategory {
    ORDINARY_IMAGE,      // JPG, JPEG, PNG, WEBP
    WHOLE_SLIDE_IMAGE,   // SVS, TIF, TIFF, NDPI, MRXS, SCN
    UNSUPPORTED
}

@Serializable
enum class IngestionStatus {
    READY,
    PROCESSING,
    UNSUPPORTED_FORMAT,
    FILE_TOO_LARGE,
    ERROR
}

const val SAMPLE_TCGA_CASE_ID = "TCGA-3L-AA1B"
const val SAMPLE_TCGA_REPORT_TEXT = "Final Pathologic Diagnosis: Colon, ascending, hemicolectomy: Invasive adenocarcinoma, moderately differentiated. Tumor invades muscularis propria into subserosal adipose tissue (pT3). Margin of resection negative for carcinoma. Regional lymph nodes 0/14 positive for metastasis (pN0). Clinical stage: Stage IIA."

@Serializable
data class SpecimenImage(
    val id: String = UUID.randomUUID().toString(),
    val uriString: String,
    val displayName: String,
    val fileSizeBytes: Long = 0L,
    val mimeType: String? = null,
    val extension: String = "",
    val category: SpecimenCategory = SpecimenCategory.ORDINARY_IMAGE,
    val ingestionStatus: IngestionStatus = IngestionStatus.READY,
    val statusMessage: String? = null,
    val addedTimestamp: Long = System.currentTimeMillis()
)

@Serializable
data class ModelProvenance(
    val fusionArchitecture: String = "SummaryToPatchCrossAttention (5,250,561 params)",
    val fusionCheckpoint: String = "models/fusion_model_v1_1.pth",
    val visionBackbone: String = "Phikon-v2 (owkin/phikon-v2, 1024-D ViT-L)",
    val textBackbone: String = "Bio_ClinicalBERT (110M params, 768-D [CLS])",
    val decisionThresholdTau: Double = 0.83,
    val inferenceEnvironment: String = "Backend Inference API (Phase 2 Integration)",
    val serverEndpoint: String = "",
    val latencyMs: Long = 0L,
    val isRealInference: Boolean = false,
    val visionTensorShape: String? = null,
    val clinicalTensorShape: String? = null,
    val tileIdentityStatus: String = "User-supplied image tiles (Case identity unverified)",
    val detectedTileCaseId: String? = null,
    val detectedTileSlideId: String? = null,
    val isSampleReportUsed: Boolean = false,
    val requestId: String? = null,
    val tileCount: Int? = null,
    val reportSha256: String? = null,
    val rawLogit: Double? = null,
    val inferenceLatencyMs: Long? = null,
    val visionInferenceMs: Long? = null,
    val clinicalTextInferenceMs: Long? = null,
    val fusionClassificationMs: Long? = null,
    val inferenceTimingScope: String? = null
)

@Serializable
data class AnalysisResult(
    val isMsi: Boolean,
    val msiProbability: Float,
    val classificationLabel: String,
    val decisionThresholdApplied: Double = 0.83,
    val isSimulated: Boolean = false,
    val provenance: ModelProvenance = ModelProvenance(),
    val requestId: String? = null,
    val visionShape: List<Int> = emptyList(),
    val clinicalShape: List<Int> = emptyList(),
    val attentionWeights: List<Float> = emptyList(),
    val attentionDistributionAvailable: Boolean = false,
    val cellularHeatmapAvailable: Boolean = true,
    val clinicalSummaryGenerated: Boolean = false,
    val clinicalSummaryText: String? = null,
    val clinicalSummaryModelUsed: String? = null,
    val clinicalSummarySource: String? = null, // "ollama_llm", "other_llm_provider", "deterministic_template_fallback"
    val clinicalSummaryIsLlm: Boolean = false,
    val clinicalSummaryError: String? = null,
    val explainabilityNote: String = "Tile-level cross-attention extracted directly from SummaryToPatchCrossAttention layer. Microscopic cellular heatmap generated via Phikon-v2 ViT attention rollout.",
    val rawError: String? = null,
    val inferenceLatencyMs: Long? = null,
    val completedAtMillis: Long = System.currentTimeMillis()
)

@Serializable
data class ReviewNote(
    val id: String = UUID.randomUUID().toString(),
    val author: String = "Clinician Reviewer",
    val category: String = "General Research Note", // Histological Observation, Clinical Discrepancy, Model Uncertainty, General
    val content: String,
    val createdAtMillis: Long = System.currentTimeMillis()
)

@Serializable
data class ResearchCase(
    val id: String = UUID.randomUUID().toString(),
    val caseIdentifier: String,
    val specimenType: String = "Surgical Resection (H&E)",
    val clinicalReportText: String = "",
    val images: List<SpecimenImage> = emptyList(),
    val status: CaseStatus = CaseStatus.DRAFT,
    val result: AnalysisResult? = null,
    val notes: List<ReviewNote> = emptyList(),
    val reviewStatus: String = "Draft", // Draft, Under Review, Reviewed, Flagged
    val serverEndpointUsed: String = "",
    val errorMessage: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)
