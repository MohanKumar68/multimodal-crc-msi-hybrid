package com.cancerdetection.multimodal.research.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cancerdetection.multimodal.research.domain.model.CaseStatus
import com.cancerdetection.multimodal.research.ui.theme.StatusAnalyzingColor
import com.cancerdetection.multimodal.research.ui.theme.StatusAnalyzingContainer
import com.cancerdetection.multimodal.research.ui.theme.StatusDraftColor
import com.cancerdetection.multimodal.research.ui.theme.StatusDraftContainer
import com.cancerdetection.multimodal.research.ui.theme.StatusErrorColor
import com.cancerdetection.multimodal.research.ui.theme.StatusErrorContainer
import com.cancerdetection.multimodal.research.ui.theme.StatusMsiColor
import com.cancerdetection.multimodal.research.ui.theme.StatusMsiContainer
import com.cancerdetection.multimodal.research.ui.theme.StatusMssColor
import com.cancerdetection.multimodal.research.ui.theme.StatusMssContainer
import com.cancerdetection.multimodal.research.ui.theme.StatusSimulatedColor
import com.cancerdetection.multimodal.research.ui.theme.StatusSimulatedContainer

@Composable
fun StatusBadge(
    status: CaseStatus,
    modifier: Modifier = Modifier
) {
    val (label, textColor, containerColor) = when (status) {
        CaseStatus.DRAFT -> Triple("DRAFT", StatusDraftColor, StatusDraftContainer)
        CaseStatus.ANALYZING -> Triple("ANALYZING…", StatusAnalyzingColor, StatusAnalyzingContainer)
        CaseStatus.ANALYZED_SIMULATED -> Triple("SIMULATED DEMO", StatusSimulatedColor, StatusSimulatedContainer)
        CaseStatus.ANALYZED_VERIFIED -> Triple("EXPERIMENTAL RUN", StatusMssColor, StatusMssContainer)
        CaseStatus.MODEL_UNAVAILABLE -> Triple("MODEL OFFLINE", StatusErrorColor, StatusErrorContainer)
        CaseStatus.FAILED -> Triple("FAILED", StatusErrorColor, StatusErrorContainer)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(containerColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 0.5.sp
            ),
            color = textColor
        )
    }
}

@Composable
fun MsiMssBadge(
    isMsi: Boolean,
    isSimulated: Boolean = false,
    modifier: Modifier = Modifier
) {
    val label = if (isMsi) "MSI" else "MSS"
    val subLabel = if (isSimulated) " (Simulated)" else ""
    val textColor = if (isMsi) StatusMsiColor else StatusMssColor
    val containerColor = if (isMsi) StatusMsiContainer else StatusMssContainer

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(containerColor)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = "$label$subLabel",
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp
            ),
            color = textColor
        )
    }
}

@Composable
fun ReviewStatusBadge(
    status: String,
    modifier: Modifier = Modifier
) {
    val (textColor, containerColor) = when (status) {
        "Reviewed" -> StatusMssColor to StatusMssContainer
        "Under Review" -> StatusAnalyzingColor to StatusAnalyzingContainer
        "Flagged" -> StatusErrorColor to StatusErrorContainer
        else -> StatusDraftColor to StatusDraftContainer
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(containerColor)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = status,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 10.sp
            ),
            color = textColor
        )
    }
}
