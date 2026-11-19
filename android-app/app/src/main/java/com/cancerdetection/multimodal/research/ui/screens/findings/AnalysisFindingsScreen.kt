package com.cancerdetection.multimodal.research.ui.screens.findings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.cancerdetection.multimodal.research.data.pdf.PdfReportGenerator
import com.cancerdetection.multimodal.research.domain.model.CaseStatus
import com.cancerdetection.multimodal.research.domain.model.ResearchCase
import com.cancerdetection.multimodal.research.domain.model.SpecimenCategory
import com.cancerdetection.multimodal.research.domain.model.SpecimenImage
import com.cancerdetection.multimodal.research.ui.components.InvestigationalDisclaimerBanner
import com.cancerdetection.multimodal.research.ui.components.MsiMssBadge
import com.cancerdetection.multimodal.research.ui.components.PathologyCard
import com.cancerdetection.multimodal.research.ui.components.ProvenanceInfoCard
import com.cancerdetection.multimodal.research.ui.components.SpecimenThumbnailItem
import com.cancerdetection.multimodal.research.ui.components.StatusBadge
import com.cancerdetection.multimodal.research.ui.components.StructuredSummaryReportView
import com.cancerdetection.multimodal.research.ui.theme.PathologyBackground
import com.cancerdetection.multimodal.research.ui.theme.PathologyCardBorder
import com.cancerdetection.multimodal.research.ui.theme.PathologyNavy
import com.cancerdetection.multimodal.research.ui.theme.PathologySurface
import com.cancerdetection.multimodal.research.ui.theme.PathologySurfaceVariant
import com.cancerdetection.multimodal.research.ui.theme.PathologyTeal
import com.cancerdetection.multimodal.research.ui.theme.PathologyTextSecondary
import com.cancerdetection.multimodal.research.ui.theme.StatusErrorColor
import com.cancerdetection.multimodal.research.ui.theme.StatusErrorContainer
import com.cancerdetection.multimodal.research.ui.theme.StatusMsiColor
import com.cancerdetection.multimodal.research.ui.theme.StatusMssColor
import com.cancerdetection.multimodal.research.ui.theme.StatusSimulatedColor
import com.cancerdetection.multimodal.research.ui.theme.StatusSimulatedContainer
import java.util.Locale

/**
 * Formats duration measurements into readable clinical/research strings:
 * - < 1000 ms: "425 ms"
 * - 1,000..59,999 ms: "8.42 sec (8,420 ms)"
 * - >= 60,000 ms: "12 min 16.30 sec"
 */
fun formatExecutionDuration(durationMs: Long): String {
    return when {
        durationMs < 1000L -> "$durationMs ms"
        durationMs < 60_000L -> {
            val sec = durationMs / 1000.0
            val msFormatted = String.format(Locale.US, "%,d", durationMs)
            String.format(Locale.US, "%.2f sec (%s ms)", sec, msFormatted)
        }
        else -> {
            val min = durationMs / 60_000L
            val remSec = (durationMs % 60_000L) / 1000.0
            String.format(Locale.US, "%d min %.2f sec", min, remSec)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisFindingsScreen(
    caseId: String,
    viewModel: AnalysisFindingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToReport: (String) -> Unit,
    onNavigateToNotes: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var sortByRank by remember { mutableStateOf(true) }
    var attentionFilter by remember { mutableStateOf(AttentionViewFilter.TOP_5) }
    var showAllHeatmaps by remember { mutableStateOf(false) }

    LaunchedEffect(caseId) {
        viewModel.loadCase(caseId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Analysis Findings",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = PathologyNavy
                            )
                        )
                        uiState.researchCase?.let {
                            Text(
                                text = "Case: ${it.caseIdentifier}",
                                style = MaterialTheme.typography.bodySmall.copy(color = PathologyTextSecondary)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = PathologyNavy
                        )
                    }
                },
                actions = {
                    uiState.researchCase?.let {
                        IconButton(onClick = { onNavigateToNotes(it.id) }) {
                            Icon(
                                imageVector = Icons.Default.ChatBubbleOutline,
                                contentDescription = "Review Notes",
                                tint = PathologyTeal
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PathologySurface
                )
            )
        },
        containerColor = PathologyBackground
    ) { innerPadding ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = PathologyTeal)
                }
            }
            uiState.researchCase == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = uiState.errorMessage ?: "Case not found.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = PathologyNavy
                    )
                }
            }
            else -> {
                val researchCase = uiState.researchCase!!
                val result = researchCase.result
                val isModelUnavailable = researchCase.status == CaseStatus.MODEL_UNAVAILABLE

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        InvestigationalDisclaimerBanner()
                    }

                    // Scope Clarification Banner (Patch-level analysis, not complete whole-slide scan)
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(PathologySurfaceVariant)
                                .border(1.dp, PathologyCardBorder, RoundedCornerShape(8.dp))
                                .padding(12.dp)
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Outlined.Layers,
                                        contentDescription = null,
                                        tint = PathologyTeal,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "SCOPE: PATCH-LEVEL RESEARCH ANALYSIS",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.5.sp,
                                            color = PathologyNavy
                                        )
                                    )
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = "Predictions are evaluated across ${researchCase.images.size} selected candidate patch(es). Selected tiles do not represent a complete whole-slide scan or an unbiased spatial representation across the resection margin.",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 11.sp,
                                        color = PathologyTextSecondary,
                                        lineHeight = 15.sp
                                    )
                                )
                            }
                        }
                    }

                    // Model Offline Banner (Honest failure reporting)
                    if (isModelUnavailable) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(StatusErrorContainer)
                                    .border(1.dp, StatusErrorColor, RoundedCornerShape(8.dp))
                                    .padding(14.dp)
                            ) {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.CloudOff,
                                            contentDescription = "Offline",
                                            tint = StatusErrorColor,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "MODEL OFFLINE / BACKEND UNREACHABLE",
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = StatusErrorColor
                                            )
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Text(
                                        text = result?.provenance?.inferenceEnvironment
                                            ?: "The mobile app could not establish a connection to the Python inference server. Ensure that the server is running on the host machine via 'python -m src.api.inference_api' and that both devices share the same local network.",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 12.sp,
                                            color = Color(0xFF7F1D1D)
                                        )
                                    )

                                    Spacer(modifier = Modifier.height(10.dp))

                                    Button(
                                        onClick = { viewModel.retryInference(context) },
                                        enabled = !uiState.isRetrying,
                                        shape = RoundedCornerShape(6.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = StatusErrorColor),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        if (uiState.isRetrying) {
                                            CircularProgressIndicator(
                                                color = Color.White,
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Retrying Connection…", fontSize = 12.sp)
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.Refresh,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Retry Inference", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Verified Live Model Execution Banner
                    if (result != null && !result.isSimulated && !isModelUnavailable) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFDCFCE7))
                                    .border(1.dp, StatusMssColor, RoundedCornerShape(8.dp))
                                    .padding(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.CloudDone,
                                        contentDescription = "Live Model Result",
                                        tint = StatusMssColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "VERIFIED LIVE MODEL PREDICTION",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = StatusMssColor
                                            )
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        if (result.provenance.latencyMs > 0L) {
                                            Text(
                                                text = "Total Execution Time: ${formatExecutionDuration(result.provenance.latencyMs)}",
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontSize = 11.sp,
                                                    color = Color(0xFF14532D)
                                                )
                                            )
                                        }
                                        val infLatency = result.provenance.inferenceLatencyMs ?: result.inferenceLatencyMs
                                        android.util.Log.i(
                                            "DIAGNOSTIC_TRACE",
                                            "[STAGE 6 - AnalysisFindingsScreen Received] provenance.inferenceLatencyMs=${result.provenance.inferenceLatencyMs}, result.inferenceLatencyMs=${result.inferenceLatencyMs}, infLatency=$infLatency, isRealInference=${result.provenance.isRealInference}, latencyMs=${result.provenance.latencyMs}"
                                        )
                                        LaunchedEffect(result.provenance.requestId) {
                                            if (result.provenance.isRealInference) {
                                                val server = result.provenance.serverEndpoint.ifBlank { com.cancerdetection.multimodal.research.data.network.InferenceConfig.DEFAULT_SERVER_URL }
                                                com.cancerdetection.multimodal.research.data.network.InferenceApiClient().sendDiagnosticTrace(
                                                    server,
                                                    6,
                                                    "AnalysisFindingsScreen Received",
                                                    mapOf(
                                                        "provenanceInferenceLatencyMs" to result.provenance.inferenceLatencyMs,
                                                        "resultInferenceLatencyMs" to result.inferenceLatencyMs,
                                                        "infLatency" to infLatency,
                                                        "isRealInference" to result.provenance.isRealInference,
                                                        "latencyMs" to result.provenance.latencyMs
                                                    )
                                                )
                                            }
                                        }
                                        if (infLatency != null && infLatency > 0L) {
                                            Text(
                                                text = "Inference Latency: ${formatExecutionDuration(infLatency)}",
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(0xFF14532D)
                                                )
                                            )
                                        } else if (result.provenance.isRealInference) {
                                            Text(
                                                text = "Inference Latency: Unavailable",
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontSize = 11.sp,
                                                    fontStyle = FontStyle.Italic,
                                                    color = Color(0xFF14532D)
                                                )
                                            )
                                        }
                                        Text(
                                            text = "via ${result.provenance.serverEndpoint.ifBlank { "backend" }}",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 11.sp,
                                                color = Color(0xFF14532D)
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Simulated Warning Banner if result is simulated
                    if (result?.isSimulated == true) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(StatusSimulatedContainer)
                                    .border(1.dp, StatusSimulatedColor, RoundedCornerShape(8.dp))
                                    .padding(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.Top) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "Simulated Notice",
                                        tint = StatusSimulatedColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "SIMULATED DEMO PREDICTION",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = StatusSimulatedColor
                                            )
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "Generated for offline demonstration purposes. Scores displayed here demonstrate UI workflows and do not represent genuine patient diagnosis.",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 11.sp,
                                                color = Color(0xFF78350F)
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // MSI/MSS Classification Card
                    item {
                        PathologyCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Computational MSI/MSS Prediction",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = PathologyNavy
                                    )
                                )
                                StatusBadge(status = researchCase.status)
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            if (result != null && !isModelUnavailable) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    MsiMssBadge(
                                        isMsi = result.isMsi,
                                        isSimulated = result.isSimulated
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = result.classificationLabel,
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = if (result.isMsi) StatusMsiColor else StatusMssColor
                                            )
                                        )
                                        Text(
                                            text = "Decision Threshold: τ = ${result.decisionThresholdApplied} (Configured research threshold — not clinically validated)",
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                            color = PathologyTextSecondary
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                // Probability Score Progress Bar
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Estimated MSI Probability",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                            color = PathologyTextSecondary
                                        )
                                        Text(
                                            text = String.format(Locale.US, "%.4f (%.1f%%)", result.msiProbability, result.msiProbability * 100),
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace
                                            ),
                                            color = PathologyNavy
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    LinearProgressIndicator(
                                        progress = { result.msiProbability.coerceIn(0f, 1f) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(4.dp)),
                                        color = if (result.isMsi) StatusMsiColor else StatusMssColor,
                                        trackColor = Color(0xFFE2E8F0)
                                    )

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("0.00 (MSS)", style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp))
                                        Text("τ = 0.83", style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold))
                                        Text("1.00 (MSI-H)", style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp))
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Text(
                                        text = "Research prediction for exploratory study. Not a validated in vitro diagnostic test.",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, color = PathologyTextSecondary)
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (isModelUnavailable)
                                            "Inference output unavailable due to backend failure."
                                        else
                                            "Inference output not yet generated for this draft case.",
                                        style = MaterialTheme.typography.bodyMedium.copy(color = PathologyTextSecondary)
                                    )
                                }
                            }
                        }
                    }

                    // Model & Pipeline Provenance Card
                    item {
                        result?.provenance?.let {
                            ProvenanceInfoCard(provenance = it)
                        }
                    }

                    // Tile-Level Cross-Attention Distribution Card
                    if (result != null && !isModelUnavailable) {
                        if (result.attentionDistributionAvailable && result.attentionWeights.isNotEmpty()) {
                            val ordinaryTiles = researchCase.images.filter { it.category == SpecimenCategory.ORDINARY_IMAGE }
                            val weights = result.attentionWeights
                            val maxWeight = weights.maxOrNull()?.coerceAtLeast(1e-6f) ?: 1f

                            val indexedWeights = weights.mapIndexed { idx, w ->
                                Triple(idx, ordinaryTiles.getOrNull(idx), w)
                            }
                            val sortedRanks = indexedWeights.sortedByDescending { it.third }
                            val rankMap = sortedRanks.mapIndexed { rankIdx, item -> item.first to (rankIdx + 1) }.toMap()
                            val tileAttentionItems = indexedWeights.map { (idx, img, w) ->
                                TileAttentionItem(
                                    originalIndex = idx,
                                    image = img,
                                    weight = w,
                                    rank = rankMap[idx] ?: (idx + 1)
                                )
                            }

                            val filteredTiles = filterTileAttentionItems(tileAttentionItems, attentionFilter)

                            val displayTiles = if (sortByRank) {
                                filteredTiles.sortedBy { it.rank }
                            } else {
                                filteredTiles.sortedBy { it.originalIndex }
                            }

                            item {
                                PathologyCard {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "Tile-Level Cross-Attention Evidence",
                                                    style = MaterialTheme.typography.titleSmall.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        color = PathologyNavy
                                                    )
                                                )
                                                Text(
                                                    text = "Affinity weights from SummaryToPatchCrossAttention (${weights.size} total tiles)",
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontSize = 11.sp,
                                                        color = PathologyTextSecondary
                                                    )
                                                )
                                            }

                                            OutlinedButton(
                                                onClick = { sortByRank = !sortByRank },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.SwapVert,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp),
                                                    tint = PathologyTeal
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = if (sortByRank) "Ranked" else "Input Order",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = PathologyNavy
                                                    )
                                                )
                                            }
                                        }

                                        // Top 5 / Top 10 / All selector
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Display:",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 11.sp,
                                                    color = PathologyTextSecondary,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            )
                                            listOf(
                                                AttentionViewFilter.TOP_5 to "Top 5",
                                                AttentionViewFilter.TOP_10 to "Top 10",
                                                AttentionViewFilter.ALL to "All (${weights.size})"
                                            ).forEach { (filterOption, label) ->
                                                val isSelected = attentionFilter == filterOption
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(if (isSelected) PathologyTeal else PathologySurfaceVariant)
                                                        .border(
                                                            1.dp,
                                                            if (isSelected) PathologyTeal else PathologyCardBorder,
                                                            RoundedCornerShape(6.dp)
                                                        )
                                                        .clickable { attentionFilter = filterOption }
                                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                                ) {
                                                    Text(
                                                        text = label,
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                            color = if (isSelected) Color.White else PathologyNavy,
                                                            fontSize = 11.sp
                                                        )
                                                    )
                                                }
                                            }
                                        }

                                        // All Tiles Heatmap Visibility Toggle & Clarification (for larger cohorts)
                                        if (attentionFilter == AttentionViewFilter.ALL && weights.size > 10) {
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(if (showAllHeatmaps) PathologyTeal.copy(alpha = 0.12f) else PathologySurfaceVariant)
                                                        .border(
                                                            1.dp,
                                                            if (showAllHeatmaps) PathologyTeal else PathologyCardBorder,
                                                            RoundedCornerShape(6.dp)
                                                        )
                                                        .clickable { showAllHeatmaps = !showAllHeatmaps }
                                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(
                                                            imageVector = if (showAllHeatmaps) Icons.Default.Check else Icons.Outlined.Layers,
                                                            contentDescription = null,
                                                            tint = if (showAllHeatmaps) PathologyTeal else PathologyTextSecondary,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = "Show all tile heatmaps (${weights.size})",
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                fontWeight = if (showAllHeatmaps) FontWeight.Bold else FontWeight.Medium,
                                                                color = if (showAllHeatmaps) PathologyTeal else PathologyNavy,
                                                                fontSize = 11.sp
                                                            )
                                                        )
                                                    }
                                                    Text(
                                                        text = if (showAllHeatmaps) "All Buttons Visible" else "Default (≥1.0% only)",
                                                        style = MaterialTheme.typography.bodySmall.copy(
                                                            fontSize = 10.sp,
                                                            color = PathologyTextSecondary
                                                        )
                                                    )
                                                }

                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = "UI Convenience Note: In the All Tiles view, heatmap buttons are hidden by default for tiles with < 1.0% attention or exact 0% attention to reduce interface clutter. This is a display filter, not a biological cutoff or claim that low-attention tissue is non-informative.",
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontSize = 9.sp,
                                                        fontStyle = FontStyle.Italic,
                                                        color = PathologyTextSecondary,
                                                        lineHeight = 13.sp
                                                    )
                                                )
                                        }

                                        Spacer(modifier = Modifier.height(12.dp))

                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            val isTopView = attentionFilter == AttentionViewFilter.TOP_5 || attentionFilter == AttentionViewFilter.TOP_10
                                            displayTiles.forEach { item ->
                                                val numericWeight = if (item.weight.isFinite() && item.weight > 0f) item.weight else 0f
                                                val showHeatmapButton = shouldShowHeatmapButton(attentionFilter, weights.size, numericWeight, showAllHeatmaps)
                                                val isLowAttention = !isTopView && weights.size > 10 && !showAllHeatmaps && numericWeight > 0f && numericWeight < 0.01f

                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(PathologySurfaceVariant)
                                                        .border(1.dp, PathologyCardBorder, RoundedCornerShape(8.dp))
                                                        .clickable(enabled = showHeatmapButton && item.image != null) {
                                                            if (item.image != null) {
                                                                viewModel.openTileHeatmap(context, item.image)
                                                            }
                                                        }
                                                        .padding(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(64.dp)
                                                            .clip(RoundedCornerShape(6.dp))
                                                            .background(Color(0xFFE2E8F0)),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        if (item.image != null) {
                                                            AsyncImage(
                                                                model = ImageRequest.Builder(context)
                                                                    .data(Uri.parse(item.image.uriString))
                                                                    .crossfade(true)
                                                                    .size(128, 128)
                                                                    .error(com.cancerdetection.multimodal.research.R.drawable.ic_launcher_foreground)
                                                                    .fallback(com.cancerdetection.multimodal.research.R.drawable.ic_launcher_foreground)
                                                                    .build(),
                                                                contentDescription = item.image.displayName,
                                                                contentScale = ContentScale.Crop,
                                                                modifier = Modifier.fillMaxSize()
                                                            )
                                                        } else {
                                                            Icon(
                                                                imageVector = Icons.Outlined.Layers,
                                                                contentDescription = null,
                                                                tint = PathologyTeal,
                                                                modifier = Modifier.size(24.dp)
                                                            )
                                                        }

                                                        Box(
                                                            modifier = Modifier
                                                                .align(Alignment.TopStart)
                                                                .padding(2.dp)
                                                                .clip(RoundedCornerShape(4.dp))
                                                                .background(PathologyNavy.copy(alpha = 0.90f))
                                                                .padding(horizontal = 5.dp, vertical = 1.dp)
                                                        ) {
                                                            Text(
                                                                text = "#${item.rank}",
                                                                style = MaterialTheme.typography.labelSmall.copy(
                                                                    fontSize = 9.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = Color.White
                                                                )
                                                            )
                                                        }
                                                    }

                                                    Spacer(modifier = Modifier.width(10.dp))

                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(
                                                                text = item.image?.displayName ?: "Tile #${item.originalIndex + 1}",
                                                                style = MaterialTheme.typography.bodySmall.copy(
                                                                    fontWeight = FontWeight.SemiBold,
                                                                    color = PathologyNavy
                                                                ),
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                                modifier = Modifier.weight(1f)
                                                            )
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Text(
                                                                text = String.format(Locale.US, "%.2f%%", item.weight * 100f),
                                                                style = MaterialTheme.typography.labelMedium.copy(
                                                                    fontWeight = FontWeight.Bold,
                                                                    fontFamily = FontFamily.Monospace,
                                                                    color = PathologyTeal
                                                                )
                                                            )
                                                        }

                                                        Spacer(modifier = Modifier.height(4.dp))

                                                        val relativeRatio = (item.weight / maxWeight).coerceIn(0f, 1f)
                                                        LinearProgressIndicator(
                                                            progress = { relativeRatio },
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .height(6.dp)
                                                                .clip(RoundedCornerShape(3.dp)),
                                                            color = PathologyTeal,
                                                            trackColor = Color(0xFFCBD5E1)
                                                        )

                                                        Spacer(modifier = Modifier.height(3.dp))

                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(
                                                                text = "Input #${item.originalIndex + 1} (${(relativeRatio * 100).toInt()}% of peak)",
                                                                style = MaterialTheme.typography.bodySmall.copy(
                                                                    fontSize = 10.sp,
                                                                    color = PathologyTextSecondary
                                                                )
                                                            )

                                                            if (showHeatmapButton) {
                                                                Row(
                                                                    verticalAlignment = Alignment.CenterVertically,
                                                                    modifier = Modifier
                                                                        .clip(RoundedCornerShape(4.dp))
                                                                        .background(PathologyTeal.copy(alpha = 0.12f))
                                                                        .clickable {
                                                                            if (item.image != null) {
                                                                                viewModel.openTileHeatmap(context, item.image)
                                                                            }
                                                                        }
                                                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                                                ) {
                                                                    Icon(
                                                                        imageVector = Icons.Outlined.ZoomIn,
                                                                        contentDescription = "Inspect ViT Heatmap",
                                                                        tint = PathologyTeal,
                                                                        modifier = Modifier.size(12.dp)
                                                                    )
                                                                    Spacer(modifier = Modifier.width(3.dp))
                                                                    Text(
                                                                        text = "ViT Heatmap",
                                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                                            fontSize = 9.sp,
                                                                            fontWeight = FontWeight.Bold,
                                                                            color = PathologyTeal
                                                                        )
                                                                    )
                                                                }
                                                            } else if (isLowAttention) {
                                                                Text(
                                                                    text = "Low attention — heatmap hidden by default",
                                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                                        fontSize = 9.sp,
                                                                        fontStyle = FontStyle.Italic,
                                                                        color = PathologyTextSecondary
                                                                    )
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))

                                        val weightSum = weights.sum()
                                        Text(
                                            text = String.format(
                                                Locale.US,
                                                "Displaying %d of %d tiles. Full cohort weight sum: %.1f%% (Softmax normalized).",
                                                displayTiles.size,
                                                weights.size,
                                                weightSum * 100f
                                            ),
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = PathologyTextSecondary
                                            )
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color(0xFFFEF3C7))
                                                .border(1.dp, Color(0xFFFDE68A), RoundedCornerShape(6.dp))
                                                .padding(8.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.Top) {
                                                Icon(
                                                    imageVector = Icons.Default.Info,
                                                    contentDescription = null,
                                                    tint = Color(0xFFB45309),
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "Attention Diagnostic Disclaimer: Attention weights reflect computational query-to-patch affinity in the cross-attention layer. They do NOT represent histological ground truth, spatial boundaries of cancer margins, or cell-level viability.",
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontSize = 10.sp,
                                                        color = Color(0xFF92400E),
                                                        lineHeight = 14.sp
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                        item {
                            PathologyCard {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Layers,
                                        contentDescription = null,
                                        tint = PathologyTextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Tile-Level Cross-Attention Distribution",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = PathologyNavy
                                        )
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(PathologySurfaceVariant)
                                        .border(1.dp, PathologyCardBorder, RoundedCornerShape(6.dp))
                                        .padding(10.dp)
                                ) {
                                    Text(
                                        text = "Attention evidence unavailable for this run. Tile-level cross-attention weights were either omitted by the inference backend or did not match the submitted tile count.",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 11.sp,
                                            color = PathologyTextSecondary,
                                            lineHeight = 15.sp
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                    // AI Diagnostic Clinical Summary Card
                    if (result != null && !isModelUnavailable) {
                        item {
                            PathologyCard {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    val isLlm = result.clinicalSummaryIsLlm
                                    val summarySource = result.clinicalSummarySource
                                    val isFallback = !isLlm || summarySource == "deterministic_template_fallback"

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Psychology,
                                                contentDescription = null,
                                                tint = if (isFallback) Color(0xFFB45309) else PathologyTeal,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = if (isFallback) "Clinical Summary (Template Fallback)" else "AI-Generated Research Summary",
                                                style = MaterialTheme.typography.titleSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    color = PathologyNavy
                                                )
                                            )
                                        }

                                        val modelBadgeText = when {
                                            !isFallback -> result.clinicalSummaryModelUsed ?: "Ollama LLM"
                                            else -> "Template Fallback"
                                        }
                                        val badgeBg = if (isFallback) Color(0xFFFEF3C7) else PathologyTeal.copy(alpha = 0.12f)
                                        val badgeBorder = if (isFallback) Color(0xFFFDE68A) else PathologyTeal.copy(alpha = 0.35f)
                                        val badgeTextColor = if (isFallback) Color(0xFF92400E) else PathologyTeal

                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(badgeBg)
                                                .border(1.dp, badgeBorder, RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = modelBadgeText,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = badgeTextColor
                                                )
                                            )
                                        }
                                    }

                                    // Fallback Warning & Diagnostic Banner
                                    if (isFallback) {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFFFFFBEB))
                                                .border(1.dp, Color(0xFFFDE68A), RoundedCornerShape(8.dp))
                                                .padding(10.dp)
                                        ) {
                                            Column {
                                                val isValidationFallback = result.clinicalSummaryError?.contains("validator", ignoreCase = true) == true
                                                val bannerHeader = if (isValidationFallback) "EVIDENCE VALIDATION FALLBACK ACTIVE" else "LOCAL LLM INACTIVE (FALLBACK MODE)"
                                                val bannerMsg = if (isValidationFallback) {
                                                    "The generated LLM summary was rejected by the evidence grounding validator due to unsupported claims. This clinical summary was synthesized deterministically using verified case facts."
                                                } else {
                                                    "The local LLM service (Ollama) is not currently running or unreachable on the backend host. This clinical summary was synthesized deterministically using structured templates and does not represent deep LLM reasoning."
                                                }
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        imageVector = Icons.Outlined.WarningAmber,
                                                        contentDescription = null,
                                                        tint = Color(0xFFB45309),
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = bannerHeader,
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color(0xFF92400E),
                                                            fontSize = 10.sp
                                                        )
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = bannerMsg,
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontSize = 11.sp,
                                                        color = Color(0xFF78350F),
                                                        lineHeight = 15.sp
                                                    )
                                                )
                                                if (!result.clinicalSummaryError.isNullOrBlank()) {
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = "Diagnostic: ${result.clinicalSummaryError}",
                                                        style = MaterialTheme.typography.bodySmall.copy(
                                                            fontSize = 10.sp,
                                                            color = Color(0xFF991B1B),
                                                            fontFamily = FontFamily.Monospace
                                                        )
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Button(
                                                        onClick = { viewModel.generateClinicalSummary(allowFallback = false) },
                                                        enabled = !uiState.isGeneratingSummary,
                                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                        shape = RoundedCornerShape(6.dp),
                                                        colors = ButtonDefaults.buttonColors(containerColor = PathologyTeal)
                                                    ) {
                                                        if (uiState.isGeneratingSummary) {
                                                            CircularProgressIndicator(
                                                                color = Color.White,
                                                                modifier = Modifier.size(12.dp),
                                                                strokeWidth = 2.dp
                                                            )
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            Text("Connecting...", fontSize = 11.sp)
                                                        } else {
                                                            Icon(
                                                                imageVector = Icons.Default.Refresh,
                                                                contentDescription = null,
                                                                modifier = Modifier.size(12.dp)
                                                            )
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            Text("Retry with LLM", fontSize = 11.sp)
                                                        }
                                                    }

                                                    OutlinedButton(
                                                        onClick = { viewModel.checkLlmStatus() },
                                                        enabled = !uiState.isCheckingLlm,
                                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                        shape = RoundedCornerShape(6.dp)
                                                    ) {
                                                        if (uiState.isCheckingLlm) {
                                                            CircularProgressIndicator(
                                                                color = PathologyNavy,
                                                                modifier = Modifier.size(12.dp),
                                                                strokeWidth = 2.dp
                                                            )
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            Text("Checking...", fontSize = 11.sp)
                                                        } else {
                                                            Icon(
                                                                imageVector = Icons.Outlined.Dns,
                                                                contentDescription = null,
                                                                modifier = Modifier.size(12.dp),
                                                                tint = PathologyTeal
                                                            )
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            Text("Check LLM Status", fontSize = 11.sp, color = PathologyNavy)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // LLM Health Status Details Card (if checked)
                                    if (uiState.llmHealth != null) {
                                        val health = uiState.llmHealth!!
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (health.reachable) Color(0xFFDCFCE7) else Color(0xFFFEE2E2))
                                                .border(1.dp, if (health.reachable) StatusMssColor else StatusErrorColor, RoundedCornerShape(6.dp))
                                                .padding(8.dp)
                                        ) {
                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        imageVector = if (health.reachable) Icons.Default.CloudDone else Icons.Default.CloudOff,
                                                        contentDescription = null,
                                                        tint = if (health.reachable) StatusMssColor else StatusErrorColor,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = if (health.reachable) "LLM ONLINE: ${health.provider} (${health.model})" else "LLM OFFLINE (${health.provider})",
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (health.reachable) Color(0xFF14532D) else StatusErrorColor,
                                                            fontSize = 10.sp
                                                        )
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = health.message,
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontSize = 10.sp,
                                                        color = PathologyNavy
                                                    )
                                                )
                                                if (!health.reachable && !health.setupHint.isNullOrBlank()) {
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Text(
                                                        text = "Setup: ${health.setupHint}",
                                                        style = MaterialTheme.typography.bodySmall.copy(
                                                            fontSize = 10.sp,
                                                            color = Color(0xFF7F1D1D),
                                                            fontFamily = FontFamily.Monospace
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    val summaryText = result.clinicalSummaryText
                                    if (!summaryText.isNullOrBlank()) {
                                        val clipboardManager = LocalClipboardManager.current
                                        StructuredSummaryReportView(
                                            summaryText = summaryText,
                                            actualAttentionWeights = result.attentionWeights,
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                 text = if (isLlm) "Synthesized via ${result.clinicalSummaryModelUsed ?: "LLM Scribe"}." else "Deterministic template fallback synthesis.",
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontSize = 10.sp,
                                                    color = PathologyTextSecondary
                                                ),
                                                modifier = Modifier.weight(1f)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            OutlinedButton(
                                                onClick = {
                                                    clipboardManager.setText(AnnotatedString(summaryText))
                                                    Toast.makeText(context, "Summary copied to clipboard", Toast.LENGTH_SHORT).show()
                                                },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.ContentCopy,
                                                    contentDescription = "Copy",
                                                    modifier = Modifier.size(13.dp),
                                                    tint = PathologyTeal
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "Copy",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontSize = 11.sp,
                                                        color = PathologyNavy
                                                    )
                                                )
                                            }
                                        }
                                    } else {
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                text = "Clinical diagnostic summary not yet compiled for this case.",
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontSize = 11.sp,
                                                    color = PathologyTextSecondary
                                                )
                                            )

                                            if (!result.clinicalSummaryError.isNullOrBlank()) {
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(Color(0xFFFEF2F2))
                                                        .border(1.dp, Color(0xFFFECACA), RoundedCornerShape(6.dp))
                                                        .padding(8.dp)
                                                ) {
                                                    Text(
                                                        text = "Previous generation detail: ${result.clinicalSummaryError}",
                                                        style = MaterialTheme.typography.bodySmall.copy(
                                                            fontSize = 10.sp,
                                                            color = Color(0xFF991B1B),
                                                            fontFamily = FontFamily.Monospace
                                                        )
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(8.dp))
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Button(
                                                    onClick = { viewModel.generateClinicalSummary(allowFallback = true) },
                                                    enabled = !uiState.isGeneratingSummary,
                                                    colors = ButtonDefaults.buttonColors(containerColor = PathologyTeal),
                                                    shape = RoundedCornerShape(6.dp)
                                                ) {
                                                    if (uiState.isGeneratingSummary) {
                                                        CircularProgressIndicator(
                                                            color = Color.White,
                                                            strokeWidth = 2.dp,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text("Generating Summary...")
                                                    } else {
                                                        Icon(
                                                            imageVector = Icons.Outlined.Psychology,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text("Generate Summary")
                                                    }
                                                }

                                                OutlinedButton(
                                                    onClick = { viewModel.generateClinicalSummary(allowFallback = false) },
                                                    enabled = !uiState.isGeneratingSummary,
                                                    shape = RoundedCornerShape(6.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Refresh,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(14.dp),
                                                        tint = PathologyTeal
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = "Retry LLM",
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontSize = 11.sp,
                                                            color = PathologyNavy
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    if (uiState.summaryFeedbackMessage != null) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = uiState.summaryFeedbackMessage!!,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 10.sp,
                                                color = PathologyTeal
                                            )
                                        )
                                    }

                                    // Expandable Original Clinical Report (Input)
                                    if (researchCase.clinicalReportText.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(PathologySurfaceVariant)
                                                .border(1.dp, PathologyCardBorder, RoundedCornerShape(8.dp))
                                        ) {
                                            Column(modifier = Modifier.fillMaxWidth()) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable { viewModel.toggleReportTextExpanded() }
                                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(
                                                            imageVector = Icons.Outlined.Description,
                                                            contentDescription = null,
                                                            tint = PathologyTeal,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = "Original Clinical Report (Input)",
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                fontWeight = FontWeight.Bold,
                                                                color = PathologyNavy
                                                            )
                                                        )
                                                    }
                                                    Icon(
                                                        imageVector = if (uiState.isReportTextExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                                        contentDescription = if (uiState.isReportTextExpanded) "Collapse" else "Expand",
                                                        tint = PathologyNavy,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }

                                                if (uiState.isReportTextExpanded) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(horizontal = 12.dp, vertical = 8.dp)
                                                            .clip(RoundedCornerShape(6.dp))
                                                            .background(Color.White)
                                                            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(6.dp))
                                                            .padding(10.dp)
                                                    ) {
                                                        Text(
                                                            text = researchCase.clinicalReportText,
                                                            style = MaterialTheme.typography.bodySmall.copy(
                                                                fontSize = 11.sp,
                                                                lineHeight = 16.sp,
                                                                color = PathologyNavy,
                                                                fontFamily = FontFamily.Monospace
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // Pathologist Review Notice
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFFFEF3C7))
                                            .border(1.dp, Color(0xFFFDE68A), RoundedCornerShape(6.dp))
                                            .padding(8.dp)
                                        ) {
                                        Row(verticalAlignment = Alignment.Top) {
                                            Icon(
                                                imageVector = Icons.Default.Info,
                                                contentDescription = null,
                                                tint = Color(0xFFB45309),
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Pathologist Review Notice: AI research summaries and multimodal predictions are strictly investigational. All computational findings, attention heatmaps, and textual summaries must be independently reviewed and verified by a board-certified pathologist before clinical or surgical decision-making.",
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontSize = 10.sp,
                                                    color = Color(0xFF92400E),
                                                    lineHeight = 14.sp
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Explainability & Evidence Section
                    item {
                        PathologyCard {
                            Text(
                                text = "Explainable AI (XAI) Evidence Checklist",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = PathologyNavy
                                )
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            EvidenceCheckItem(
                                title = "Text-Guided Patch Cross-Attention",
                                description = "ClinicalBERT text query directs relative cross-attention weights over candidate visual patches.",
                                isAvailable = result?.attentionDistributionAvailable == true
                            )

                            EvidenceCheckItem(
                                title = "Phikon-v2 Cellular Attention Heatmaps",
                                description = "Pixel-level ViT-L/14 recursive attention rollout product + Otsu tissue masking + cellular ROI contours.",
                                isAvailable = result?.cellularHeatmapAvailable == true
                            )

                            EvidenceCheckItem(
                                title = "Automated Clinical Summary Scribe",
                                description = "AI synthesis of clinical narrative and multimodal predictions via localized LLM (Ollama) / clinical scribe.",
                                isAvailable = result?.clinicalSummaryGenerated == true
                            )

                            if (result != null && result.explainabilityNote.isNotBlank()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(PathologySurfaceVariant)
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        text = "Explainability Note: ${result.explainabilityNote}",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 11.sp,
                                            color = PathologyNavy
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // Selected Specimen Tiles Preview
                    if (researchCase.images.isNotEmpty()) {
                        item {
                            PathologyCard {
                                Text(
                                    text = "Analyzed Specimen Tiles & Slides (${researchCase.images.size})",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = PathologyNavy
                                    )
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(researchCase.images) { img ->
                                        SpecimenThumbnailItem(
                                            image = img,
                                            onRemove = null
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Action Buttons: View Report & Review Notes
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { onNavigateToReport(researchCase.id) },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PathologyTeal),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Assignment,
                                    contentDescription = "Preview Report",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Preview Report",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            OutlinedButton(
                                onClick = { onNavigateToNotes(researchCase.id) },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ChatBubbleOutline,
                                    contentDescription = null,
                                    tint = PathologyTeal,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Review Notes", color = PathologyNavy)
                            }
                        }
                    }
                }
            }
        }

        if (uiState.inspectingTile != null) {
            val tile = uiState.inspectingTile!!
            Dialog(
                onDismissRequest = { viewModel.closeTileHeatmap() },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.94f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .border(1.dp, PathologyCardBorder, RoundedCornerShape(12.dp))
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Microscopic ViT Attention Heatmap",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = PathologyNavy
                                    )
                                )
                                Text(
                                    text = tile.displayName,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 11.sp,
                                        color = PathologyTextSecondary
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { viewModel.closeTileHeatmap() },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = PathologyNavy,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(PathologySurfaceVariant)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Method: Phikon-v2 ViT-L/14 Attention Rollout Product (6-layer recursive attention product × tissue mask + ROI contours)",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 10.sp,
                                    color = PathologyNavy
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        when {
                            uiState.isGeneratingHeatmap -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    CircularProgressIndicator(
                                        color = PathologyTeal,
                                        strokeWidth = 3.dp,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "Computing ViT attention rollout on backend...",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 11.sp,
                                            color = PathologyTextSecondary
                                        )
                                    )
                                }
                            }
                            uiState.heatmapError != null -> {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Cancel,
                                        contentDescription = null,
                                        tint = StatusErrorColor,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Heatmap computation error:",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = StatusErrorColor
                                        )
                                    )
                                    Text(
                                        text = uiState.heatmapError ?: "Unknown error",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 11.sp,
                                            color = PathologyTextSecondary
                                        ),
                                        maxLines = 4
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Button(
                                        onClick = { viewModel.openTileHeatmap(context, tile) },
                                        colors = ButtonDefaults.buttonColors(containerColor = PathologyTeal),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text("Retry Computation", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            uiState.heatmapBase64 != null -> {
                                val bitmap = remember(uiState.heatmapBase64) {
                                    decodeBase64ToBitmap(uiState.heatmapBase64!!)
                                }
                                if (bitmap != null) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        // 3-Panel Guide
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Composite 3-Panel Attention Analysis",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp,
                                                    color = PathologyNavy
                                                )
                                            )
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(PathologyTeal.copy(alpha = 0.12f))
                                                    .clickable { viewModel.openExpandedHeatmap() }
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Fullscreen,
                                                    contentDescription = "Expand",
                                                    modifier = Modifier.size(12.dp),
                                                    tint = PathologyTeal
                                                )
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text(
                                                    text = "Tap to Expand",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = PathologyTeal
                                                    )
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))

                                        // Compact horizontal preview preserving ~2.88:1 aspect ratio with ContentScale.Fit
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(16f / 5.5f)
                                                .clip(RoundedCornerShape(6.dp))
                                                .border(1.dp, PathologyCardBorder, RoundedCornerShape(6.dp))
                                                .background(Color(0xFF0F172A))
                                                .clickable { viewModel.openExpandedHeatmap() },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Image(
                                                bitmap = bitmap.asImageBitmap(),
                                                contentDescription = "ViT 3-Panel Attention Heatmap",
                                                contentScale = ContentScale.Fit,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))

                                        // Panel Description Guide
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "[1] Input H&E Tile",
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = PathologyTextSecondary
                                                )
                                            )
                                            Text(
                                                text = "[2] ViT Rollout Product",
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = PathologyTextSecondary
                                                )
                                            )
                                            Text(
                                                text = "[3] Mask + ROI Contours",
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = PathologyTextSecondary
                                                )
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Text(
                                            text = "Deep Transformer Attention Rollout (Phikon-v2 ViT-L/14, 6 deep layers). Warm cellular overlays (yellow/red) highlight microscopic regions contributing highest self-attention mass. Contours outline top cellular ROIs.",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 10.sp,
                                                color = PathologyTextSecondary,
                                                lineHeight = 14.sp
                                            )
                                        )
                                    }
                                } else {
                                    Text(
                                        text = "Unable to decode heatmap image stream.",
                                        style = MaterialTheme.typography.bodySmall.copy(color = StatusErrorColor)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (uiState.heatmapBase64 != null) {
                                OutlinedButton(
                                    onClick = { viewModel.openExpandedHeatmap() },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Fullscreen,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = PathologyTeal
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("View Full-Screen", fontSize = 11.sp, color = PathologyNavy)
                                }
                            }

                            Button(
                                onClick = { viewModel.closeTileHeatmap() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = PathologyNavy),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text("Dismiss", style = MaterialTheme.typography.labelMedium.copy(color = Color.White))
                            }
                        }
                    }
                }
            }
        }

        // Full-Screen Heatmap Dialog
        if (uiState.isHeatmapExpanded && uiState.inspectingTile != null && uiState.heatmapBase64 != null) {
            val tile = uiState.inspectingTile!!
            val bitmap = remember(uiState.heatmapBase64) {
                decodeBase64ToBitmap(uiState.heatmapBase64!!)
            }
            if (bitmap != null) {
                var scale by remember { mutableStateOf(1f) }
                var offset by remember { mutableStateOf(Offset.Zero) }

                Dialog(
                    onDismissRequest = { viewModel.closeExpandedHeatmap() },
                    properties = DialogProperties(
                        dismissOnBackPress = true,
                        dismissOnClickOutside = false,
                        usePlatformDefaultWidth = false
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF0B1120))
                            .padding(14.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Full-Screen Header with Title & Dismiss
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Phikon-v2 ViT Attention Rollout (Full Resolution)",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    )
                                    Text(
                                        text = tile.displayName,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 11.sp,
                                            color = Color.LightGray
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.closeExpandedHeatmap() },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close Full Screen",
                                        tint = Color.White
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // 3-Panel Guide with equal weight chips
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF1E293B))
                                        .padding(horizontal = 4.dp, vertical = 5.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "[1] Input H&E Tile",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF93C5FD)
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF1E293B))
                                        .padding(horizontal = 4.dp, vertical = 5.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "[2] ViT Rollout",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFFDE047)
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF1E293B))
                                        .padding(horizontal = 4.dp, vertical = 5.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "[3] Mask + ROIs",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFFCA5A5)
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Interactive Zoom & Pan Viewport
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .clipToBounds()
                                    .background(Color.Black)
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onDoubleTap = {
                                                if (scale > 1.2f) {
                                                    scale = 1f
                                                    offset = Offset.Zero
                                                } else {
                                                    scale = 2.5f
                                                }
                                            }
                                        )
                                    }
                                    .pointerInput(Unit) {
                                        detectTransformGestures { _, pan, zoom, _ ->
                                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                                            scale = newScale
                                            if (newScale > 1f) {
                                                val maxOffsetX = 800f * (newScale - 1f)
                                                val maxOffsetY = 800f * (newScale - 1f)
                                                offset = Offset(
                                                    x = (offset.x + pan.x).coerceIn(-maxOffsetX, maxOffsetX),
                                                    y = (offset.y + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
                                                )
                                            } else {
                                                offset = Offset.Zero
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Full-Screen 3-Panel ViT Attention Rollout",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer(
                                            scaleX = scale,
                                            scaleY = scale,
                                            translationX = offset.x,
                                            translationY = offset.y
                                        )
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Quick Zoom & Pan Controls Toolbar
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            scale = (scale - 0.5f).coerceAtLeast(1f)
                                            if (scale <= 1f) offset = Offset.Zero
                                        },
                                        enabled = scale > 1f,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Remove,
                                            contentDescription = "Zoom Out",
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            scale = 1f
                                            offset = Offset.Zero
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.RestartAlt,
                                            contentDescription = "Reset Zoom",
                                            tint = Color(0xFF93C5FD),
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = String.format(Locale.US, "%.1fx", scale),
                                            fontSize = 10.sp,
                                            color = Color.White
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            scale = (scale + 0.5f).coerceAtMost(5f)
                                        },
                                        enabled = scale < 5f,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = "Zoom In",
                                            tint = Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }

                                Button(
                                    onClick = { viewModel.closeExpandedHeatmap() },
                                    colors = ButtonDefaults.buttonColors(containerColor = PathologyTeal),
                                    shape = RoundedCornerShape(4.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                                ) {
                                    Text("Done", fontSize = 11.sp, color = Color.White)
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "Tip: Pinch or double-tap to zoom into cellular ROIs. Drag to pan. 1x view preserves full 3-panel composite. Recursive rollout computes product of attention matrices across the last 6 transformer blocks multiplied by Otsu mask.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 9.sp,
                                    color = Color.LightGray,
                                    lineHeight = 13.sp
                                )
                            )
                        }
                    }
                }
            } else {
                Dialog(
                    onDismissRequest = { viewModel.closeExpandedHeatmap() }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White)
                            .padding(16.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Unable to decode heatmap image data.",
                                style = MaterialTheme.typography.bodyMedium.copy(color = StatusErrorColor)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = { viewModel.closeExpandedHeatmap() },
                                colors = ButtonDefaults.buttonColors(containerColor = PathologyNavy)
                            ) {
                                Text("Close")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun decodeBase64ToBitmap(base64Str: String): Bitmap? {
    return try {
        val clean = base64Str.substringAfter("base64,")
        val decodedBytes = Base64.decode(clean, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun EvidenceCheckItem(
    title: String,
    description: String,
    isAvailable: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = if (isAvailable) Icons.Outlined.CheckCircleOutline else Icons.Outlined.Cancel,
            contentDescription = if (isAvailable) "Available" else "Deferred",
            tint = if (isAvailable) StatusMssColor else PathologyTextSecondary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = if (isAvailable) PathologyNavy else PathologyTextSecondary
                )
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.sp,
                    color = PathologyTextSecondary
                )
            )
        }
    }
}

data class TileAttentionItem(
    val originalIndex: Int,
    val image: SpecimenImage?,
    val weight: Float,
    val rank: Int
)

enum class AttentionViewFilter {
    TOP_5,
    TOP_10,
    ALL
}

fun filterTileAttentionItems(
    items: List<TileAttentionItem>,
    filter: AttentionViewFilter
): List<TileAttentionItem> {
    return when (filter) {
        AttentionViewFilter.TOP_5 -> items.sortedBy { it.rank }.take(5)
        AttentionViewFilter.TOP_10 -> items.sortedBy { it.rank }.take(10)
        AttentionViewFilter.ALL -> items
    }
}

fun shouldShowHeatmapButton(
    filter: AttentionViewFilter,
    tileCount: Int,
    numericWeight: Float,
    showAllHeatmaps: Boolean
): Boolean {
    val isTopView = filter == AttentionViewFilter.TOP_5 || filter == AttentionViewFilter.TOP_10
    return isTopView || tileCount <= 10 || (numericWeight >= 0.01f || showAllHeatmaps)
}
