package com.cancerdetection.multimodal.research.ui.screens.notes

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Comment
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cancerdetection.multimodal.research.domain.model.ReviewNote
import com.cancerdetection.multimodal.research.ui.components.InvestigationalDisclaimerBanner
import com.cancerdetection.multimodal.research.ui.components.PathologyCard
import com.cancerdetection.multimodal.research.ui.components.ReviewStatusBadge
import com.cancerdetection.multimodal.research.ui.components.StatusBadge
import com.cancerdetection.multimodal.research.ui.theme.PathologyBackground
import com.cancerdetection.multimodal.research.ui.theme.PathologyCardBorder
import com.cancerdetection.multimodal.research.ui.theme.PathologyNavy
import com.cancerdetection.multimodal.research.ui.theme.PathologySurface
import com.cancerdetection.multimodal.research.ui.theme.PathologySurfaceVariant
import com.cancerdetection.multimodal.research.ui.theme.PathologyTeal
import com.cancerdetection.multimodal.research.ui.theme.PathologyTextSecondary
import com.cancerdetection.multimodal.research.ui.theme.StatusErrorColor
import com.cancerdetection.multimodal.research.ui.theme.StatusErrorContainer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewNotesScreen(
    caseId: String,
    viewModel: ReviewNotesViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(caseId) {
        viewModel.loadCase(caseId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Review Questions & Notes",
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
                        text = "Case not found.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = PathologyNavy
                    )
                }
            }
            else -> {
                val researchCase = uiState.researchCase!!

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        InvestigationalDisclaimerBanner()
                    }

                    // Case Review Status Card
                    item {
                        PathologyCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Current Review Status",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = PathologyNavy
                                    )
                                )
                                ReviewStatusBadge(status = researchCase.reviewStatus)
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            val statusOptions = listOf("Draft", "Under Review", "Reviewed", "Flagged")
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(statusOptions) { status ->
                                    val isSelected = researchCase.reviewStatus == status
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { viewModel.updateReviewStatus(researchCase.id, status) },
                                        label = { Text(status) },
                                        leadingIcon = if (isSelected) {
                                            {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        } else null,
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = PathologyTeal,
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // Add Note Card
                    item {
                        PathologyCard {
                            Text(
                                text = "Record Clinician Question or Note",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = PathologyNavy
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Add audit comments, diagnostic discrepancies, or model uncertainty inquiries.",
                                style = MaterialTheme.typography.bodySmall.copy(color = PathologyTextSecondary)
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = uiState.authorName,
                                onValueChange = { viewModel.onAuthorNameChanged(it) },
                                label = { Text("Reviewer / Author Name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = "Note Category",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                color = PathologyTextSecondary
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            val categories = listOf(
                                "Histological Observation",
                                "Clinical Discrepancy",
                                "Model Uncertainty",
                                "General Research Note"
                            )

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(categories) { cat ->
                                    val isSelected = uiState.selectedCategory == cat
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { viewModel.onCategorySelected(cat) },
                                        label = { Text(cat, fontSize = 11.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = PathologyTeal,
                                            selectedLabelColor = Color.White
                                        )
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = uiState.noteContent,
                                onValueChange = { viewModel.onNoteContentChanged(it) },
                                label = { Text("Clinical Note / Question") },
                                minLines = 3,
                                maxLines = 6,
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (uiState.error != null) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = uiState.error ?: "",
                                    style = MaterialTheme.typography.bodySmall.copy(color = StatusErrorColor)
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Button(
                                onClick = { viewModel.addNote(researchCase.id) },
                                enabled = !uiState.isSaving,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PathologyTeal),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AddComment,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Add Note to Case Log")
                            }
                        }
                    }

                    // Existing Notes Section
                    item {
                        Text(
                            text = "Case Note History (${researchCase.notes.size})",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = PathologyNavy
                            )
                        )
                    }

                    if (researchCase.notes.isEmpty()) {
                        item {
                            PathologyCard {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No notes recorded yet for this case.",
                                        style = MaterialTheme.typography.bodyMedium.copy(color = PathologyTextSecondary)
                                    )
                                }
                            }
                        }
                    } else {
                        items(researchCase.notes.reversed()) { note ->
                            NoteItemCard(note = note)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteItemCard(note: ReviewNote) {
    val dateFormat = SimpleDateFormat("MMM d, yyyy — HH:mm", Locale.getDefault())
    val dateStr = dateFormat.format(Date(note.createdAtMillis))

    PathologyCard(backgroundColor = PathologySurfaceVariant) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = note.author,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = PathologyNavy
                    )
                )
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        color = PathologyTextSecondary
                    )
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFE2E8F0))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = note.category,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = PathologyNavy
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = note.content,
            style = MaterialTheme.typography.bodyMedium.copy(color = PathologyNavy, lineHeight = 20.sp)
        )
    }
}
