package com.cancerdetection.multimodal.research.data.repository

import com.cancerdetection.multimodal.research.domain.model.AnalysisResult
import com.cancerdetection.multimodal.research.domain.model.CaseStatus
import com.cancerdetection.multimodal.research.domain.model.ModelProvenance
import com.cancerdetection.multimodal.research.domain.model.ResearchCase
import com.cancerdetection.multimodal.research.domain.model.ReviewNote
import com.cancerdetection.multimodal.research.domain.model.SpecimenImage

object DemoDataSeeder {

    fun createInitialDemoCases(): List<ResearchCase> {
        val now = System.currentTimeMillis()

        return listOf(
            ResearchCase(
                id = "demo-tcga-3l-aa1b",
                caseIdentifier = "TCGA-3L-AA1B",
                specimenType = "Colorectal Adenocarcinoma Resection (H&E)",
                clinicalReportText = "Final Pathologic Diagnosis: Colon, ascending, hemicolectomy: Invasive adenocarcinoma, moderately differentiated. Tumor invades muscularis propria into subserosal adipose tissue (pT3). Margin of resection negative for carcinoma. Regional lymph nodes 0/14 positive for metastasis (pN0). Clinical stage: Stage IIA.",
                images = listOf(
                    SpecimenImage(
                        id = "img-demo-1",
                        uriString = "android.resource://com.cancerdetection.multimodal.research/drawable/ic_launcher_foreground",
                        displayName = "TCGA-3L-AA1B_patch_042.jpg"
                    ),
                    SpecimenImage(
                        id = "img-demo-2",
                        uriString = "android.resource://com.cancerdetection.multimodal.research/drawable/ic_launcher_foreground",
                        displayName = "TCGA-3L-AA1B_patch_118.jpg"
                    )
                ),
                status = CaseStatus.ANALYZED_SIMULATED,
                result = AnalysisResult(
                    isMsi = false,
                    msiProbability = 0.1495f,
                    classificationLabel = "Model prediction: MSS",
                    decisionThresholdApplied = 0.83,
                    isSimulated = true,
                    provenance = ModelProvenance(
                        fusionArchitecture = "SummaryToPatchCrossAttention (5.25M params)",
                        fusionCheckpoint = "models/fusion_model_v1_1.pth",
                        visionBackbone = "Phikon-v2 (owkin/phikon-v2, 1024-D)",
                        textBackbone = "Bio_ClinicalBERT (110M params, 768-D [CLS])",
                        decisionThresholdTau = 0.83,
                        inferenceEnvironment = "Simulated Benchmark Baseline (Phase 1 Scaffold)"
                    ),
                    attentionWeights = listOf(0.68f, 0.32f),
                    attentionDistributionAvailable = true,
                    cellularHeatmapAvailable = false,
                    clinicalSummaryGenerated = false,
                    explainabilityNote = "Demonstration score for UI verification. Attention distribution shows relative weighting across the 2 candidate tiles (68% vs 32%). Cellular heatmaps deferred to future on-device runtime.",
                    completedAtMillis = now - 86400000L
                ),
                notes = listOf(
                    ReviewNote(
                        id = "note-1",
                        author = "Pathologist Reviewer (Simulated Demo)",
                        category = "Histological Observation",
                        content = "Simulated UI demonstration: Tubular glands without extensive mucin pools. Histomorphology consistent with MSS phenotype.",
                        createdAtMillis = now - 80000000L
                    )
                ),
                reviewStatus = "Reviewed",
                createdAtMillis = now - 90000000L,
                updatedAtMillis = now - 80000000L
            ),
            ResearchCase(
                id = "demo-tcga-a6-2675",
                caseIdentifier = "TCGA-A6-2675",
                specimenType = "Cecum Resection Specimen (H&E)",
                clinicalReportText = "Cecum, right hemicolectomy: Poorly differentiated adenocarcinoma with prominent tumor-infiltrating lymphocytes (TILs) and medullary growth pattern. Focal mucinous differentiation noted.",
                images = listOf(
                    SpecimenImage(
                        id = "img-demo-3",
                        uriString = "android.resource://com.cancerdetection.multimodal.research/drawable/ic_launcher_foreground",
                        displayName = "TCGA-A6-2675_patch_009.jpg"
                    )
                ),
                status = CaseStatus.ANALYZED_SIMULATED,
                result = AnalysisResult(
                    isMsi = true,
                    msiProbability = 0.9120f,
                    classificationLabel = "Model prediction: MSI-H",
                    decisionThresholdApplied = 0.83,
                    isSimulated = true,
                    provenance = ModelProvenance(
                        fusionArchitecture = "SummaryToPatchCrossAttention (5.25M params)",
                        fusionCheckpoint = "models/fusion_model_v1_1.pth",
                        visionBackbone = "Phikon-v2 (owkin/phikon-v2, 1024-D)",
                        textBackbone = "Bio_ClinicalBERT (110M params, 768-D [CLS])",
                        decisionThresholdTau = 0.83,
                        inferenceEnvironment = "Simulated Benchmark Baseline (Phase 1 Scaffold)"
                    ),
                    attentionWeights = listOf(1.0f),
                    attentionDistributionAvailable = true,
                    cellularHeatmapAvailable = false,
                    clinicalSummaryGenerated = false,
                    explainabilityNote = "Demonstration score for UI verification. Single patch absorbs 100% of cross-attention weight. Cellular heatmaps deferred to future on-device runtime.",
                    completedAtMillis = now - 43200000L
                ),
                notes = listOf(
                    ReviewNote(
                        id = "note-2",
                        author = "Research Fellow (Simulated Demo)",
                        category = "Model Uncertainty",
                        content = "Simulated UI demonstration: Simulated score exceeds research threshold (0.9120 > 0.8300). In real practice, confirmatory MLH1/MSH2/MSH6/PMS2 IHC would be indicated.",
                        createdAtMillis = now - 36000000L
                    )
                ),
                reviewStatus = "Under Review",
                createdAtMillis = now - 50000000L,
                updatedAtMillis = now - 36000000L
            ),
            ResearchCase(
                id = "demo-draft-003",
                caseIdentifier = "CRC-RES-DRAFT-03",
                specimenType = "Endoscopic Biopsy (H&E)",
                clinicalReportText = "Rectosigmoid mass biopsy: Atypical glandular proliferation suspicious for adenocarcinoma. Additional tissue sections pending deeper levels.",
                images = emptyList(),
                status = CaseStatus.DRAFT,
                result = null,
                notes = emptyList(),
                reviewStatus = "Draft",
                createdAtMillis = now - 18000000L,
                updatedAtMillis = now - 18000000L
            )
        )
    }
}
