package com.cancerdetection.multimodal.research.ui.screens.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cancerdetection.multimodal.research.data.repository.ResearchCaseRepository
import com.cancerdetection.multimodal.research.domain.model.CaseStatus
import com.cancerdetection.multimodal.research.domain.model.ResearchCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class OverviewUiState(
    val recentCases: List<ResearchCase> = emptyList(),
    val totalCasesCount: Int = 0,
    val draftCasesCount: Int = 0,
    val analyzedCasesCount: Int = 0,
    val underReviewCount: Int = 0,
    val isLoading: Boolean = false
)

class OverviewViewModel(
    private val repository: ResearchCaseRepository
) : ViewModel() {

    val uiState: StateFlow<OverviewUiState> = repository.cases.map { cases ->
        OverviewUiState(
            recentCases = cases.take(5),
            totalCasesCount = cases.size,
            draftCasesCount = cases.count { it.status == CaseStatus.DRAFT },
            analyzedCasesCount = cases.count {
                it.status == CaseStatus.ANALYZED_SIMULATED || it.status == CaseStatus.ANALYZED_VERIFIED
            },
            underReviewCount = cases.count { it.reviewStatus == "Under Review" },
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = OverviewUiState(isLoading = true)
    )
}
