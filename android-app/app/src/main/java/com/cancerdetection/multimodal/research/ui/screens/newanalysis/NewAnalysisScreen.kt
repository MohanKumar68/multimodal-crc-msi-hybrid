package com.cancerdetection.multimodal.research.ui.screens.newanalysis

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cancerdetection.multimodal.research.data.storage.SpecimenIngestionManager
import com.cancerdetection.multimodal.research.ui.components.InvestigationalDisclaimerBanner
import com.cancerdetection.multimodal.research.ui.components.PathologyCard
import com.cancerdetection.multimodal.research.ui.components.SpecimenThumbnailItem
import com.cancerdetection.multimodal.research.ui.theme.PathologyBackground
import com.cancerdetection.multimodal.research.ui.theme.PathologyNavy
import com.cancerdetection.multimodal.research.ui.theme.PathologySurface
import com.cancerdetection.multimodal.research.ui.theme.PathologySurfaceVariant
import com.cancerdetection.multimodal.research.ui.theme.PathologyTeal
import com.cancerdetection.multimodal.research.ui.theme.PathologyTextSecondary
import com.cancerdetection.multimodal.research.ui.theme.StatusErrorColor
import com.cancerdetection.multimodal.research.ui.theme.StatusErrorContainer
import com.cancerdetection.multimodal.research.ui.theme.StatusMssColor
import com.cancerdetection.multimodal.research.ui.theme.StatusSimulatedColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewAnalysisScreen(
    caseIdToEdit: String?,
    viewModel: NewAnalysisViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToFindings: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(caseIdToEdit) {
        if (caseIdToEdit != null) {
            viewModel.loadCase(caseIdToEdit)
        }
    }

    // Storage Access Framework Document Tree Picker (Folder of pre-extracted tiles)
    val tileFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.ingestTileFolder(uri, context)
        }
    }

    // Android System Photo Picker Launcher (Ordinary H&E tiles up to 100 for manual selection)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(
            maxItems = SpecimenIngestionManager.MAX_SINGLE_SELECTION_TILES
        )
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addImagesFromUris(uris, context)
        }
    }

    // Storage Access Framework Multi-Document Picker fallback (Individual image files)
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addImagesFromUris(uris, context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (uiState.editingCaseId != null) "Edit Research Case" else "New Specimen Analysis",
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
                    TextButton(
                        onClick = {
                            viewModel.saveDraft {
                                onNavigateBack()
                            }
                        },
                        enabled = !uiState.isSaving && !uiState.isAnalyzing
                    ) {
                        Text(
                            text = if (uiState.isSaving) "Saving…" else "Save Draft",
                            color = PathologyTeal,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PathologySurface
                )
            )
        },
        containerColor = PathologyBackground
    ) { innerPadding ->
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

            // Validation Error Box
            if (uiState.validationError != null) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(StatusErrorContainer)
                            .border(1.dp, StatusErrorColor, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.ErrorOutline,
                                contentDescription = "Error",
                                tint = StatusErrorColor
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = uiState.validationError ?: "",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = StatusErrorColor
                                )
                            )
                        }
                    }
                }
            }

            // 1. Case Metadata Card
            item {
                PathologyCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "1. Research Case Identifier",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = PathologyNavy
                            )
                        )

                        TextButton(
                            onClick = { viewModel.loadSampleTcgaReport() },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = PathologyTeal
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Load TCGA Sample",
                                style = MaterialTheme.typography.labelSmall.copy(color = PathologyTeal)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = uiState.caseIdentifier,
                        onValueChange = { viewModel.updateCaseIdentifier(it) },
                        label = { Text("Case ID (e.g. TCGA-3L-AA1B, CRC-RES-001)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = uiState.specimenType,
                        onValueChange = { viewModel.updateSpecimenType(it) },
                        label = { Text("Specimen Source & Staining") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (uiState.caseIdConflictWarning != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFFEF2F2))
                                .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.ErrorOutline,
                                    contentDescription = "Conflict Warning",
                                    tint = Color(0xFFB91C1C),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = uiState.caseIdConflictWarning ?: "",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFFB91C1C)
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // 2. Clinical Report Input Card
            item {
                PathologyCard {
                    Text(
                        text = "2. Clinical Pathology Text Report",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = PathologyNavy
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Unstructured surgical pathology findings. Embedded via Bio_ClinicalBERT (768-D) to guide cross-attention.",
                        style = MaterialTheme.typography.bodySmall.copy(color = PathologyTextSecondary)
                    )

                    if (uiState.isSampleReportActive) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFFEF3C7))
                                .border(1.dp, Color(0xFFF59E0B), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Outlined.ErrorOutline,
                                        contentDescription = "Sample Data Active",
                                        tint = Color(0xFFB45309),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "SAMPLE REPORT ACTIVE: TCGA-3L-AA1B Template",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFB45309)
                                        )
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                val isCaseMismatch = uiState.caseIdentifier.trim().uppercase() != "TCGA-3L-AA1B"
                                Text(
                                    text = if (isCaseMismatch) {
                                        "Warning: Active clinical report is the TCGA-3L-AA1B sample text, which does not match case '${uiState.caseIdentifier.trim()}'. For authentic analysis, replace with actual findings or confirm below."
                                    } else {
                                        "Notice: Standard benchmark report text loaded for demonstration."
                                    },
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 11.sp,
                                        color = Color(0xFF92400E)
                                    )
                                )
                                if (isCaseMismatch && !uiState.sampleReportConfirmedForCase) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = { viewModel.confirmUseSampleReport() },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                "Confirm Sample Text for ${uiState.caseIdentifier.trim()}",
                                                fontSize = 11.sp
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = { viewModel.clearSampleReport() },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                "Clear Text",
                                                fontSize = 11.sp,
                                                color = Color(0xFFB45309)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = uiState.clinicalReportText,
                        onValueChange = { viewModel.updateClinicalReport(it) },
                        label = { Text("Gross & Microscopic Diagnosis") },
                        minLines = 4,
                        maxLines = 8,
                        supportingText = {
                            Text("${uiState.clinicalReportText.length} characters")
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 3. Histopathology Specimen Ingestion Card
            item {
                PathologyCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (uiState.images.isNotEmpty()) {
                                    "3. Pre-Extracted Histopathology Tiles (${uiState.images.size} Loaded)"
                                } else {
                                    "3. Pre-Extracted Histopathology Tiles"
                                },
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = PathologyNavy
                                )
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (uiState.selectedFolderName != null) {
                                    "Source: Folder '${uiState.selectedFolderName}' (${uiState.images.size} tiles loaded without cap)."
                                } else {
                                    "Scope: Patch-Level Analysis (${uiState.images.size} tiles selected; picker limit ${SpecimenIngestionManager.MAX_SINGLE_SELECTION_TILES})."
                                },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.sp,
                                    color = PathologyTextSecondary
                                )
                            )
                        }

                        if (uiState.images.isNotEmpty()) {
                            TextButton(
                                onClick = { viewModel.clearImages() },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = "Clear All",
                                    modifier = Modifier.size(14.dp),
                                    tint = StatusErrorColor
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Clear All",
                                    style = MaterialTheme.typography.labelSmall.copy(color = StatusErrorColor)
                                )
                            }
                        }
                    }

                    if (uiState.images.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val isVerified = uiState.detectedTileCaseId != null
                        val hasConflict = uiState.tileIdentityStatus.startsWith("Conflicting")
                        val badgeBg = when {
                            hasConflict -> Color(0xFFFEF2F2)
                            isVerified -> Color(0xFFE0F2FE)
                            else -> Color(0xFFF1F5F9)
                        }
                        val badgeBorder = when {
                            hasConflict -> Color(0xFFEF4444)
                            isVerified -> Color(0xFF0284C7)
                            else -> Color(0xFF94A3B8)
                        }
                        val badgeText = when {
                            hasConflict -> Color(0xFFB91C1C)
                            isVerified -> Color(0xFF0369A1)
                            else -> Color(0xFF475569)
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(badgeBg)
                                .border(1.dp, badgeBorder, RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isVerified) Icons.Default.CheckCircle else Icons.Outlined.Info,
                                    contentDescription = null,
                                    tint = badgeText,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = uiState.tileIdentityStatus,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = badgeText
                                        )
                                    )
                                    if (uiState.detectedTileSlideId != null) {
                                        Text(
                                            text = "Slide Ref: ${uiState.detectedTileSlideId}",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = badgeText
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Ingestion Actions: Folder selection vs Individual tiles
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                tileFolderLauncher.launch(null)
                            },
                            enabled = !uiState.isIngestingFolder,
                            modifier = Modifier.weight(1.15f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PathologyTeal)
                        ) {
                            if (uiState.isIngestingFolder) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Reading…", fontSize = 12.sp)
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.FolderOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (uiState.selectedFolderName != null) "Change Folder" else "Select Tile Folder",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                try {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                } catch (_: Exception) {
                                    documentPickerLauncher.launch(arrayOf("image/jpeg", "image/png", "image/webp"))
                                }
                            },
                            enabled = !uiState.isIngestingFolder,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddPhotoAlternate,
                                contentDescription = null,
                                tint = PathologyNavy,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Select Images",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = PathologyNavy
                                )
                            )
                        }
                    }

                    // Folder ingestion notice / status message
                    if (uiState.folderIngestionNotice != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFDCFCE7))
                                .border(1.dp, StatusMssColor, RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = StatusMssColor,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = uiState.folderIngestionNotice ?: "",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 11.sp,
                                        color = Color(0xFF14532D)
                                    )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Scope & Ingestion Format Notice
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(PathologySurfaceVariant)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Column {
                            Text(
                                text = "Scope & Tile Requirements:",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = PathologyNavy
                                )
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "• Folder Ingestion: Ingests all valid JPG, JPEG, and PNG tiles from a directory without tile limits.\n• Image Picker: Allows manual selection of up to ${SpecimenIngestionManager.MAX_SINGLE_SELECTION_TILES} tiles.\n• ARGB_8888 Color: Preserves full 24-bit histology color depth for Phikon-v2 ViT embeddings.\n• Deterministic Mapping: Tiles are sorted deterministically by filename for 1:1 cross-attention weight alignment.\n• Whole-Slide Notice: Mobile direct whole-slide (.svs) upload is not implemented; pre-extract patches prior to analysis.\n• Provider Compatibility: If folder browsing is unsupported by your storage provider, use 'Select Images' to choose files directly.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.sp,
                                    color = PathologyTextSecondary,
                                    lineHeight = 15.sp
                                )
                            )
                        }
                    }

                    if (uiState.images.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Selected Candidate Patches (${uiState.images.size}):",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = PathologyNavy
                                )
                            )
                            Text(
                                text = "Order preserved 1..${uiState.images.size}",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 10.sp,
                                    color = PathologyTextSecondary
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(uiState.images, key = { it.id }) { img ->
                                SpecimenThumbnailItem(
                                    image = img,
                                    onRemove = { viewModel.removeImage(img.id) }
                                )
                            }
                        }
                    }
                }
            }

            // 4. Multimodal Inference Server Configuration Card
            item {
                PathologyCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Dns,
                                contentDescription = null,
                                tint = PathologyTeal,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "4. Inference Backend Server",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = PathologyNavy
                                )
                            )
                        }

                        // Test Connection Button
                        TextButton(
                            onClick = { viewModel.checkServerConnection() },
                            enabled = !uiState.isCheckingServer,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            if (uiState.isCheckingServer) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp,
                                    color = PathologyTeal
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Test Connection",
                                    modifier = Modifier.size(14.dp),
                                    tint = PathologyTeal
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Test Connection",
                                style = MaterialTheme.typography.labelSmall.copy(color = PathologyTeal)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = uiState.serverUrl,
                        onValueChange = { viewModel.updateServerUrl(it) },
                        label = { Text("Server Base URL (IP:Port)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Server Connectivity Status Pill
                    val (statusColor, statusBg, statusIcon, statusText) = when (uiState.isServerOnline) {
                        true -> Quadruple(
                            StatusMssColor,
                            Color(0xFFDCFCE7),
                            Icons.Default.CloudDone,
                            uiState.serverStatusMessage ?: "Backend Online: SummaryToPatchCrossAttention ready"
                        )
                        false -> Quadruple(
                            StatusErrorColor,
                            StatusErrorContainer,
                            Icons.Default.CloudOff,
                            uiState.serverStatusMessage ?: "Backend Offline: Verify host IP and port 5000"
                        )
                        null -> Quadruple(
                            PathologyTextSecondary,
                            PathologySurfaceVariant,
                            Icons.Outlined.Info,
                            uiState.serverStatusMessage ?: "Status: Untested. Default port 5000 (LAN)"
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(statusBg)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = statusIcon,
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = statusColor
                                )
                            )
                        }
                    }
                }
            }

            // 5. Execution Buttons
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Primary Real Inference Button
                    Button(
                        onClick = {
                            viewModel.analyzeSpecimen(
                                context = context,
                                forceSimulation = false
                            ) { caseId ->
                                onNavigateToFindings(caseId)
                            }
                        },
                        enabled = !uiState.isAnalyzing,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PathologyTeal),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        if (uiState.isAnalyzing) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = uiState.analysisProgressMessage ?: "Extracting Embeddings & Fusing…",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Psychology,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Run Multimodal AI Inference",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }

                    // Secondary Simulated Demo Mode (For offline testing when host server is unreachable)
                    OutlinedButton(
                        onClick = {
                            viewModel.analyzeSpecimen(
                                context = context,
                                forceSimulation = true
                            ) { caseId ->
                                onNavigateToFindings(caseId)
                            }
                        },
                        enabled = !uiState.isAnalyzing,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        Text(
                            text = "Run Simulated Demo (Offline Testing)",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = StatusSimulatedColor
                            )
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = PathologyTextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Inputs are stored in local private sandbox. Network transmission only occurs to configured research inference server.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.sp,
                            color = PathologyTextSecondary
                        )
                    )
                }
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
