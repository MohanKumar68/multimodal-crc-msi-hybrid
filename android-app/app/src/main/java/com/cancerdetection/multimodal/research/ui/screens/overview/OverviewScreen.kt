package com.cancerdetection.multimodal.research.ui.screens.overview

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.outlined.Biotech
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.cancerdetection.multimodal.research.ui.theme.PathologySurfaceVariant
import com.cancerdetection.multimodal.research.ui.theme.PathologyTeal
import com.cancerdetection.multimodal.research.ui.theme.PathologyTealContainer
import com.cancerdetection.multimodal.research.ui.theme.PathologyTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    viewModel: OverviewViewModel,
    onNavigateToNewAnalysis: (String?) -> Unit,
    onNavigateToFindings: (String) -> Unit,
    onNavigateToHistory: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "CRC MSI/MSS Research",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = PathologyNavy
                            )
                        )
                        Text(
                            text = "Multimodal Fusion Workspace",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = PathologyTextSecondary
                            )
                        )
                    }
                },
                actions = {
                    OutlinedButton(
                        onClick = onNavigateToHistory,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.History,
                            contentDescription = "History",
                            modifier = Modifier.size(16.dp),
                            tint = PathologyTeal
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "All Cases",
                            style = MaterialTheme.typography.labelSmall,
                            color = PathologyNavy
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
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = PathologyTeal)
            }
        } else {
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

                item {
                    // Primary Action: New Analysis
                    Card(
                        onClick = { onNavigateToNewAnalysis(null) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = PathologyTeal),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Start Specimen Analysis",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Input clinical findings & select H&E slide crops via Photo Picker",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = Color(0xFFE0F2F1)
                                    )
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(0x33FFFFFF)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "New Analysis",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }

                item {
                    // Quick Status Summary Metric Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetricCard(
                            label = "Total Cases",
                            count = uiState.totalCasesCount,
                            modifier = Modifier.weight(1f)
                        )
                        MetricCard(
                            label = "Analyzed",
                            count = uiState.analyzedCasesCount,
                            modifier = Modifier.weight(1f)
                        )
                        MetricCard(
                            label = "Under Review",
                            count = uiState.underReviewCount,
                            modifier = Modifier.weight(1f)
                        )
                        MetricCard(
                            label = "Drafts",
                            count = uiState.draftCasesCount,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recent Research Cases",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = PathologyNavy
                            )
                        )
                        Text(
                            text = "View All (${uiState.totalCasesCount})",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = PathologyTeal,
                                fontWeight = FontWeight.SemiBold
                            ),
                            modifier = Modifier.clickable { onNavigateToHistory() }
                        )
                    }
                }

                if (uiState.recentCases.isEmpty()) {
                    item {
                        EmptyCasesCard(onNewAnalysis = { onNavigateToNewAnalysis(null) })
                    }
                } else {
                    items(uiState.recentCases) { caseItem ->
                        RecentCaseCard(
                            researchCase = caseItem,
                            onClick = {
                                if (caseItem.status == CaseStatus.DRAFT) {
                                    onNavigateToNewAnalysis(caseItem.id)
                                } else {
                                    onNavigateToFindings(caseItem.id)
                                }
                            }
                        )
                    }
                }

                item {
                    // Model Architecture Summary Note
                    PathologyCard(backgroundColor = PathologySurfaceVariant) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Biotech,
                                contentDescription = null,
                                tint = PathologyTeal,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Cross-Attention Multimodal Core",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = PathologyNavy
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Combines Phikon-v2 histopathology patch embeddings (1024-D) with ClinicalBERT report representations (768-D). Designed for Samsung Galaxy S24 edge execution.",
                            style = MaterialTheme.typography.bodySmall.copy(color = PathologyTextSecondary)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    count: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = PathologySurface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = PathologyNavy
                )
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    color = PathologyTextSecondary
                ),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun RecentCaseCard(
    researchCase: ResearchCase,
    onClick: () -> Unit
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

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = researchCase.specimenType,
                    style = MaterialTheme.typography.bodySmall.copy(color = PathologyTextSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (researchCase.result != null) {
                MsiMssBadge(
                    isMsi = researchCase.result.isMsi,
                    isSimulated = researchCase.result.isSimulated
                )
            } else {
                ReviewStatusBadge(status = researchCase.reviewStatus)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

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
                Spacer(modifier = Modifier.width(12.dp))
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

@Composable
private fun EmptyCasesCard(
    onNewAnalysis: () -> Unit
) {
    PathologyCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.Biotech,
                contentDescription = null,
                tint = PathologyTeal,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "No Research Cases Found",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = PathologyNavy
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Create a new case to enter clinical findings and select histology specimen tiles.",
                style = MaterialTheme.typography.bodyMedium.copy(color = PathologyTextSecondary),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onNewAnalysis,
                colors = ButtonDefaults.buttonColors(containerColor = PathologyTeal)
            ) {
                Text("Start First Analysis")
            }
        }
    }
}
