package com.cancerdetection.multimodal.research.ui.screens.history

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cancerdetection.multimodal.research.domain.model.CaseStatus
import com.cancerdetection.multimodal.research.domain.model.ResearchCase
import com.cancerdetection.multimodal.research.ui.components.InvestigationalDisclaimerBanner
import com.cancerdetection.multimodal.research.ui.components.MsiMssBadge
import com.cancerdetection.multimodal.research.ui.components.PathologyCard
import com.cancerdetection.multimodal.research.ui.components.ReviewStatusBadge
import com.cancerdetection.multimodal.research.ui.components.StatusBadge
import com.cancerdetection.multimodal.research.ui.theme.PathologyBackground
import com.cancerdetection.multimodal.research.ui.theme.PathologyNavy
import com.cancerdetection.multimodal.research.ui.theme.PathologySurface
import com.cancerdetection.multimodal.research.ui.theme.PathologyTeal
import com.cancerdetection.multimodal.research.ui.theme.PathologyTextSecondary
import com.cancerdetection.multimodal.research.ui.theme.StatusErrorColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaseHistoryScreen(
    viewModel: CaseHistoryViewModel,
    onNavigateBack: () -> Unit,
    onOpenCase: (ResearchCase) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var caseToDelete by remember { mutableStateOf<ResearchCase?>(null) }

    if (caseToDelete != null) {
        AlertDialog(
            onDismissRequest = { caseToDelete = null },
            title = { Text("Delete Case ${caseToDelete?.caseIdentifier}?") },
            text = { Text("This will remove the case and its review notes from local device storage.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        caseToDelete?.let { viewModel.deleteCase(it.id) }
                        caseToDelete = null
                    }
                ) {
                    Text("Delete", color = StatusErrorColor)
                }
            },
            dismissButton = {
                TextButton(onClick = { caseToDelete = null }) {
                    Text("Cancel", color = PathologyNavy)
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Research Case History",
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PathologySurface)
            )
        },
        containerColor = PathologyBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PathologySurface)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // Search Field
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.onSearchQueryChanged(it) },
                    placeholder = { Text("Search by Case ID or clinical findings…") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = PathologyTextSecondary
                        )
                    },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear",
                                    tint = PathologyTextSecondary
                                )
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Filter Chips
                val filterOptions = listOf("All", "Analyzed", "Draft", "Under Review", "Reviewed")
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(filterOptions) { filter ->
                        val isSelected = uiState.selectedFilter == filter
                        FilterChip(
                            selected = isSelected,
                            onClick = { viewModel.onFilterSelected(filter) },
                            label = { Text(filter) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PathologyTeal,
                                selectedLabelColor = androidx.compose.ui.graphics.Color.White
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PathologyTeal)
                }
            } else if (uiState.cases.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No cases match your search criteria.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PathologyTextSecondary
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        InvestigationalDisclaimerBanner()
                    }

                    items(uiState.cases, key = { it.id }) { c ->
                        CaseHistoryItem(
                            researchCase = c,
                            onClick = { onOpenCase(c) },
                            onDelete = { caseToDelete = c }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CaseHistoryItem(
    researchCase: ResearchCase,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    val dateStr = dateFormat.format(Date(researchCase.updatedAtMillis))

    PathologyCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = researchCase.caseIdentifier,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = PathologyNavy
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    StatusBadge(status = researchCase.status)
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = researchCase.specimenType,
                    style = MaterialTheme.typography.bodySmall.copy(color = PathologyTextSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (researchCase.result != null) {
                    MsiMssBadge(
                        isMsi = researchCase.result.isMsi,
                        isSimulated = researchCase.result.isSimulated
                    )
                } else {
                    ReviewStatusBadge(status = researchCase.reviewStatus)
                }

                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete Case",
                        tint = PathologyTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Image,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = PathologyTeal
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${researchCase.images.size} tiles",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = PathologyTextSecondary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Icon(
                    imageVector = Icons.Outlined.Description,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = PathologyTeal
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${researchCase.notes.size} notes",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = PathologyTextSecondary
                )
            }

            Text(
                text = dateStr,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = PathologyTextSecondary
            )
        }
    }
}
