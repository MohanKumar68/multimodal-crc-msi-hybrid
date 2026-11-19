package com.cancerdetection.multimodal.research.ui.screens.report

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cancerdetection.multimodal.research.data.pdf.PdfReportGenerator
import com.cancerdetection.multimodal.research.ui.components.InvestigationalDisclaimerBanner
import com.cancerdetection.multimodal.research.ui.components.MsiMssBadge
import com.cancerdetection.multimodal.research.ui.components.PathologyCard
import com.cancerdetection.multimodal.research.ui.components.ReviewStatusBadge
import com.cancerdetection.multimodal.research.ui.components.StatusBadge
import com.cancerdetection.multimodal.research.ui.components.StructuredSummaryReportView
import com.cancerdetection.multimodal.research.ui.theme.PathologyBackground
import com.cancerdetection.multimodal.research.ui.theme.PathologyCardBorder
import com.cancerdetection.multimodal.research.ui.theme.PathologyNavy
import com.cancerdetection.multimodal.research.ui.theme.PathologySurface
import com.cancerdetection.multimodal.research.ui.theme.PathologySurfaceVariant
import com.cancerdetection.multimodal.research.ui.theme.PathologyTeal
import com.cancerdetection.multimodal.research.ui.theme.PathologyTextSecondary
import com.cancerdetection.multimodal.research.ui.theme.StatusMsiColor
import com.cancerdetection.multimodal.research.ui.theme.StatusMssColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportPreviewScreen(
    caseId: String,
    viewModel: ReportPreviewViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToNotes: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showPdfDialog by remember { mutableStateOf(false) }

    LaunchedEffect(caseId) {
        viewModel.loadCase(caseId)
    }

    val createPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.exportPdf(context, uri)
        }
    }

    LaunchedEffect(uiState.pdfExportMessage) {
        uiState.pdfExportMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearPdfExportMessage()
        }
    }

    if (showPdfDialog) {
        AlertDialog(
            onDismissRequest = { showPdfDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = null,
                    tint = PathologyTeal
                )
            },
            title = { Text("Report Text Copied to Clipboard") },
            text = {
                Text(
                    "A structured, research-formatted text report has been copied to your device clipboard for review and documentation. (Native on-device vector PDF export is scheduled for Phase 6)."
                )
            },
            confirmButton = {
                TextButton(onClick = { showPdfDialog = false }) {
                    Text("OK", color = PathologyNavy)
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Research Report",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = PathologyNavy
                        )
                    )
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
                    uiState.researchCase?.let { c ->
                        IconButton(onClick = {
                            val text = viewModel.generateReportText(c)
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Research Report: ${c.caseIdentifier}")
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share Research Report"))
                        }) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share Report",
                                tint = PathologyNavy
                            )
                        }

                        IconButton(onClick = {
                            val text = viewModel.generateReportText(c)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("CRC Research Report", text))
                            showPdfDialog = true
                        }) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy Report Text",
                                tint = PathologyTeal
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PathologySurface)
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
                        text = "Report data unavailable.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = PathologyNavy
                    )
                }
            }
            else -> {
                val researchCase = uiState.researchCase!!
                val result = researchCase.result
                val dateFormat = SimpleDateFormat("MMMM d, yyyy — HH:mm", Locale.getDefault())
                val reportDate = dateFormat.format(Date(researchCase.updatedAtMillis))
                var isClinicalReportExpanded by remember { mutableStateOf(false) }
                var isAllTilesExpanded by remember { mutableStateOf(false) }

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

                    // Main Report Container (Simulates Physical Document Sheet)
                    item {
                        PathologyCard(
                            backgroundColor = Color.White,
                            borderColor = Color(0xFFCBD5E1),
                            elevation = 2.dp,
                            contentPadding = 20.dp
                        ) {
                            // Document Header
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "MULTIMODAL PATHOLOGY RESEARCH REPORT",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp,
                                            color = PathologyTeal
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Case: ${researchCase.caseIdentifier}",
                                        style = MaterialTheme.typography.headlineSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = PathologyNavy
                                        )
                                    )
                                    Text(
                                        text = "Generated: $reportDate",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                        color = PathologyTextSecondary
                                    )
                                }

                                Column(horizontalAlignment = Alignment.End) {
                                    StatusBadge(status = researchCase.status)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    ReviewStatusBadge(status = researchCase.reviewStatus)
                                }
                            }

                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 14.dp),
                                color = PathologyCardBorder
                            )

                            // Section 1: Clinical Findings (Collapsible)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { isClinicalReportExpanded = !isClinicalReportExpanded }
                                    .padding(vertical = 4.dp),
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
                                        text = "1. Clinical Pathology Findings (Input)",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = PathologyNavy
                                        )
                                    )
                                }
                                Icon(
                                    imageVector = if (isClinicalReportExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (isClinicalReportExpanded) "Collapse" else "Expand",
                                    tint = PathologyNavy,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            if (isClinicalReportExpanded) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(PathologySurfaceVariant)
                                        .border(1.dp, PathologyCardBorder, RoundedCornerShape(6.dp))
                                        .padding(10.dp)
                                ) {
                                    Text(
                                        text = researchCase.clinicalReportText.ifBlank { "No clinical report recorded." },
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 11.sp,
                                            lineHeight = 16.sp,
                                            color = PathologyNavy,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    )
                                }
                            } else {
                                Text(
                                    text = researchCase.clinicalReportText.ifBlank { "No clinical report recorded." },
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp,
                                        color = PathologyNavy
                                    ),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Tap section header to expand full clinical narrative (${researchCase.clinicalReportText.length} chars).",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 10.sp,
                                        fontStyle = FontStyle.Italic,
                                        color = PathologyTeal
                                    )
                                )
                            }

                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 14.dp),
                                color = PathologyCardBorder
                            )

                            // Section 2: Specimen Details & Analysis Scope
                            ReportSectionTitle("2. Histopathology Specimen Tiles & Analysis Scope")
                            Text(
                                text = "Specimen Source: ${researchCase.specimenType}",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = PathologyNavy
                            )
                            Text(
                                text = "Analysis Scope: Patch-Level Research (${researchCase.images.size} tiles processed in 24-bit ARGB_8888)",
                                style = MaterialTheme.typography.bodySmall,
                                color = PathologyTextSecondary
                            )
                            Text(
                                text = "Scope Limitation: Candidate patches do not constitute a complete whole-slide scan or unbiased spatial distribution across tumor margins.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 10.sp,
                                    fontStyle = FontStyle.Italic
                                ),
                                color = PathologyTextSecondary
                            )

                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 14.dp),
                                color = PathologyCardBorder
                            )

                            // Section 3: Computational Assessment
                            ReportSectionTitle("3. Computational MSI/MSS Fusion Assessment")
                            if (result != null) {
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
                                            text = "Estimated MSI Probability: ${String.format(Locale.US, "%.4f (%.1f%%)", result.msiProbability, result.msiProbability * 100f)} (Cutoff τ = ${result.decisionThresholdApplied})",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            color = PathologyNavy
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(PathologySurfaceVariant)
                                        .border(1.dp, PathologyCardBorder, RoundedCornerShape(6.dp))
                                        .padding(10.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = "Architecture: ${result.provenance.fusionArchitecture}",
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                            color = PathologyNavy
                                        )
                                        Text(
                                            text = "Checkpoint: ${result.provenance.fusionCheckpoint}",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace
                                            ),
                                            color = PathologyNavy
                                        )
                                        Text(
                                            text = "Environment: ${result.provenance.inferenceEnvironment}",
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                            color = PathologyTextSecondary
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = "Pending analysis execution.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PathologyTextSecondary
                                )
                            }

                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 14.dp),
                                color = PathologyCardBorder
                            )

                            // Section 4: AI Diagnostic Clinical Summary / Automated Clinical Scribe
                            if (result != null) {
                                val isLlm = result.clinicalSummaryIsLlm
                                val summarySource = result.clinicalSummarySource
                                val isFallback = !isLlm || summarySource == "deterministic_template_fallback"
                                val summaryModelBadge = when {
                                    !isFallback -> result.clinicalSummaryModelUsed ?: "Ollama LLM"
                                    else -> "Template Fallback"
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Outlined.Psychology,
                                            contentDescription = null,
                                            tint = if (isFallback) Color(0xFFB45309) else PathologyTeal,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "4. AI Diagnostic Clinical Summary",
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = PathologyNavy
                                            )
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(if (isFallback) Color(0xFFFEF3C7) else PathologyTeal.copy(alpha = 0.12f))
                                            .border(1.dp, if (isFallback) Color(0xFFFDE68A) else PathologyTeal.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = summaryModelBadge,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isFallback) Color(0xFF92400E) else PathologyTeal
                                            )
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                if (isFallback) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFFFFFBEB))
                                            .border(1.dp, Color(0xFFFDE68A), RoundedCornerShape(6.dp))
                                            .padding(8.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.Top) {
                                            Icon(
                                                imageVector = Icons.Outlined.WarningAmber,
                                                contentDescription = null,
                                                tint = Color(0xFFB45309),
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            val fallbackMsg = when {
                                                result.clinicalSummaryError?.contains("validator", ignoreCase = true) == true ->
                                                    "LLM output rejected by evidence safety validator. Summary synthesized deterministically using verified case data."
                                                else ->
                                                    "Local LLM service (Ollama) inactive/unreachable. Summary synthesized deterministically using structured templates."
                                            }
                                            Text(
                                                text = fallbackMsg,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontSize = 10.sp,
                                                    color = Color(0xFF92400E),
                                                    lineHeight = 14.sp
                                                )
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }

                                val summaryText = result.clinicalSummaryText
                                if (!summaryText.isNullOrBlank()) {
                                    StructuredSummaryReportView(
                                        summaryText = summaryText,
                                        actualAttentionWeights = result?.attentionWeights,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFFFEF3C7))
                                            .border(1.dp, Color(0xFFFDE68A), RoundedCornerShape(6.dp))
                                            .padding(6.dp)
                                    ) {
                                        Text(
                                            text = "Pathologist Review Notice: AI research summaries and multimodal predictions are strictly investigational. All computational findings must be independently reviewed and verified by a board-certified pathologist.",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 9.sp,
                                                color = Color(0xFF92400E),
                                                lineHeight = 13.sp
                                            )
                                        )
                                    }
                                } else {
                                    Text(
                                        text = "Clinical diagnostic summary not yet compiled for this case.",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 11.sp,
                                            color = PathologyTextSecondary
                                        )
                                    )
                                }

                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 14.dp),
                                    color = PathologyCardBorder
                                )
                            }

                            // Section 5: Concise Tile-Level Cross-Attention Evidence
                            ReportSectionTitle("5. Concise Tile Cross-Attention Evidence")
                            if (result != null && result.attentionWeights.isNotEmpty()) {
                                val ordinaryTiles = researchCase.images.filter { it.category == com.cancerdetection.multimodal.research.domain.model.SpecimenCategory.ORDINARY_IMAGE }
                                val weights = result.attentionWeights
                                val maxW = weights.maxOrNull()?.coerceAtLeast(1e-6f) ?: 1f
                                val indexed = weights.mapIndexed { idx, w ->
                                    Triple(idx, ordinaryTiles.getOrNull(idx)?.displayName ?: "Tile #${idx + 1}", w)
                                }.sortedByDescending { it.third }

                                val topK = if (weights.size > 5) 5 else weights.size
                                Text(
                                    text = "Total Analyzed Tiles: ${weights.size} (Displaying Top $topK highest cross-attention patches)",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = PathologyNavy
                                    )
                                )
                                Spacer(modifier = Modifier.height(6.dp))

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    indexed.take(topK).forEachIndexed { rank, item ->
                                        val relative = (item.third / maxW).coerceIn(0f, 1f)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(PathologySurfaceVariant)
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                modifier = Modifier.weight(1f),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(3.dp))
                                                        .background(PathologyNavy)
                                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                                ) {
                                                    Text(
                                                        text = "#${rank + 1}",
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color.White
                                                        )
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Column {
                                                    Text(
                                                        text = item.second,
                                                        style = MaterialTheme.typography.bodySmall.copy(
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = PathologyNavy
                                                        ),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = "Input #${item.first + 1}",
                                                        style = MaterialTheme.typography.bodySmall.copy(
                                                            fontSize = 9.sp,
                                                            color = PathologyTextSecondary
                                                        )
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.width(8.dp))

                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(
                                                    text = String.format(Locale.US, "%.2f%%", item.third * 100f),
                                                    style = MaterialTheme.typography.bodySmall.copy(
                                                        fontSize = 11.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontWeight = FontWeight.Bold,
                                                        color = PathologyTeal
                                                    )
                                                )
                                                LinearProgressIndicator(
                                                    progress = { relative },
                                                    modifier = Modifier
                                                        .width(60.dp)
                                                        .height(4.dp)
                                                        .clip(RoundedCornerShape(2.dp)),
                                                    color = PathologyTeal,
                                                    trackColor = Color(0xFFCBD5E1)
                                                )
                                            }
                                        }
                                    }
                                }

                                // Expandable Appendix for All Tiles (collapsed by default)
                                if (weights.size > topK) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(PathologySurfaceVariant)
                                            .border(1.dp, PathologyCardBorder, RoundedCornerShape(6.dp))
                                            .clickable { isAllTilesExpanded = !isAllTilesExpanded }
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "All Tile Details Appendix (${weights.size} Tiles)",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = PathologyNavy
                                            )
                                        )
                                        Icon(
                                            imageVector = if (isAllTilesExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                            contentDescription = if (isAllTilesExpanded) "Collapse Appendix" else "Expand Appendix",
                                            tint = PathologyNavy,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    if (isAllTilesExpanded) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color.White)
                                                .border(1.dp, PathologyCardBorder, RoundedCornerShape(6.dp))
                                                .padding(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            indexed.forEachIndexed { rank, item ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(
                                                        text = "#${rank + 1} Tile ${item.first + 1}: ${item.second}",
                                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                                        color = PathologyNavy,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    Text(
                                                        text = String.format(Locale.US, "%.2f%% (w=%.5f)", item.third * 100f, item.third),
                                                        style = MaterialTheme.typography.bodySmall.copy(
                                                            fontSize = 10.sp,
                                                            fontFamily = FontFamily.Monospace,
                                                            color = PathologyTeal
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Attention Diagnostic Disclaimer: Attention weights represent computational query-to-patch affinity in the cross-attention layer. They do NOT represent histological ground truth or cell-level viability.",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 10.sp,
                                        fontStyle = FontStyle.Italic
                                    ),
                                    color = PathologyTextSecondary
                                )
                            } else {
                                Text(
                                    text = "Tile-level attention scores unavailable for this inference.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PathologyTextSecondary
                                )
                            }

                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 14.dp),
                                color = PathologyCardBorder
                            )

                            // Section 6: Clinician Review Notes
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ReportSectionTitle("6. Clinician Review Notes (${researchCase.notes.size})")
                                TextButton(
                                    onClick = { onNavigateToNotes(researchCase.id) },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("Add Note", color = PathologyTeal, style = MaterialTheme.typography.labelSmall)
                                }
                            }

                            if (researchCase.notes.isEmpty()) {
                                Text(
                                    text = "No review notes attached yet.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = PathologyTextSecondary
                                )
                            } else {
                                researchCase.notes.forEach { note ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "${note.author} — ${note.category}",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp
                                                ),
                                                color = PathologyNavy
                                            )
                                        }
                                        Text(
                                            text = note.content,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = PathologyNavy
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 14.dp),
                                color = PathologyCardBorder
                            )

                            // Institutional Disclaimer Footer
                            Text(
                                text = "ATTENTION: This computational research report was synthesized by the Multimodal Colorectal Cancer Fusion Prototype for academic investigational purposes. It has not been validated by the FDA, CE-IVD, or CAP/CLIA for clinical in vitro diagnostic use. Confirmatory testing via standard mismatch repair immunohistochemistry (MMR-IHC) and PCR/NGS microsatellite analysis is mandatory for clinical patient management.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 10.sp,
                                    lineHeight = 14.sp
                                ),
                                color = PathologyTextSecondary
                            )
                        }
                    }

                    // Action Buttons Row
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (!uiState.isExportingPdf) {
                                        val filename = PdfReportGenerator.sanitizeFilename(researchCase.caseIdentifier)
                                        createPdfLauncher.launch(filename)
                                    }
                                },
                                enabled = !uiState.isExportingPdf,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PathologyTeal),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                if (uiState.isExportingPdf) {
                                    CircularProgressIndicator(
                                        color = Color.White,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Saving...", maxLines = 1)
                                } else {
                                    Icon(
                                        imageVector = Icons.Outlined.PictureAsPdf,
                                        contentDescription = "Download PDF",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Download PDF",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            OutlinedButton(
                                onClick = { onNavigateToNotes(researchCase.id) },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Text("Edit Notes", color = PathologyNavy)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(
            fontWeight = FontWeight.Bold,
            color = PathologyNavy
        ),
        modifier = Modifier.padding(bottom = 6.dp)
    )
}
