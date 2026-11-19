package com.cancerdetection.multimodal.research.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cancerdetection.multimodal.research.domain.model.ModelProvenance
import com.cancerdetection.multimodal.research.ui.theme.PathologyNavy
import com.cancerdetection.multimodal.research.ui.theme.PathologySurfaceVariant
import com.cancerdetection.multimodal.research.ui.theme.PathologyTeal
import com.cancerdetection.multimodal.research.ui.theme.PathologyTextSecondary

@Composable
fun ProvenanceInfoCard(
    provenance: ModelProvenance,
    modifier: Modifier = Modifier
) {
    PathologyCard(
        modifier = modifier,
        backgroundColor = PathologySurfaceVariant
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.AccountTree,
                contentDescription = "Architecture Provenance",
                tint = PathologyTeal
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Model Architecture & Pipeline Provenance",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = PathologyNavy
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        ProvenanceRow(label = "Cross-Attention Fusion", value = provenance.fusionArchitecture)
        ProvenanceRow(label = "Trained Checkpoint", value = provenance.fusionCheckpoint, isMonospace = true)
        ProvenanceRow(label = "Histopathology Backbone", value = provenance.visionBackbone)
        ProvenanceRow(label = "Clinical Text Backbone", value = provenance.textBackbone)
        ProvenanceRow(label = "Decision Threshold", value = "τ = ${provenance.decisionThresholdTau} (Configured Research Threshold — Not Clinically Validated)")
        ProvenanceRow(label = "Execution Environment", value = provenance.inferenceEnvironment)
        if (!provenance.requestId.isNullOrBlank()) {
            ProvenanceRow(label = "Inference Request ID", value = provenance.requestId, isMonospace = true)
        }
        if (provenance.tileCount != null && provenance.tileCount > 0) {
            ProvenanceRow(label = "Evaluated Tile Count", value = "${provenance.tileCount} patch tile(s)")
        }
        if (!provenance.reportSha256.isNullOrBlank()) {
            ProvenanceRow(label = "Clinical Report Hash (SHA-256)", value = provenance.reportSha256.take(16) + "...", isMonospace = true)
        }
        if (provenance.rawLogit != null) {
            ProvenanceRow(label = "Raw Model Output (Pre-Sigmoid Logit)", value = String.format(java.util.Locale.US, "%.4f", provenance.rawLogit), isMonospace = true)
        }
        if (provenance.tileIdentityStatus.isNotBlank()) {
            ProvenanceRow(label = "Tile Identity Status", value = provenance.tileIdentityStatus)
        }
        if (provenance.isSampleReportUsed) {
            ProvenanceRow(label = "Clinical Report Status", value = "TCGA-3L-AA1B Sample Template (Not Patient-Specific)")
        }

        if (provenance.isRealInference) {
            if (provenance.serverEndpoint.isNotBlank()) {
                ProvenanceRow(label = "Inference Server Endpoint", value = provenance.serverEndpoint, isMonospace = true)
            }
            if (provenance.latencyMs > 0L) {
                ProvenanceRow(label = "Total Execution Time", value = "${provenance.latencyMs} ms")
            }
            if (provenance.inferenceLatencyMs != null && provenance.inferenceLatencyMs > 0L) {
                ProvenanceRow(label = "Inference Latency", value = "${provenance.inferenceLatencyMs} ms")
            }
            val vShape = provenance.visionTensorShape
            if (!vShape.isNullOrBlank()) {
                ProvenanceRow(label = "Vision Embeddings Tensor", value = vShape, isMonospace = true)
            }
            val cShape = provenance.clinicalTensorShape
            if (!cShape.isNullOrBlank()) {
                ProvenanceRow(label = "Clinical Embeddings Tensor", value = cShape, isMonospace = true)
            }
        }
    }
}

@Composable
private fun ProvenanceRow(
    label: String,
    value: String,
    isMonospace: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            ),
            color = PathologyTextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.SansSerif
            ),
            color = PathologyNavy
        )
    }
}
