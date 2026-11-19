package com.cancerdetection.multimodal.research.ui.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cancerdetection.multimodal.research.data.repository.ResearchCaseRepository
import com.cancerdetection.multimodal.research.domain.model.CaseStatus
import com.cancerdetection.multimodal.research.domain.model.ResearchCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CaseHistoryUiState(
    val cases: List<ResearchCase> = emptyList(),
    val searchQuery: String = "",
    val selectedFilter: String = "All", // All, Draft, Analyzed, Under Review, Reviewed
    val isLoading: Boolean = false
)

class CaseHistoryViewModel(
    private val repository: ResearchCaseRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _selectedFilter = MutableStateFlow("All")

    val uiState: StateFlow<CaseHistoryUiState> = combine(
        repository.cases,
        _searchQuery,
        _selectedFilter
    ) { allCases, query, filter ->
        val filtered = allCases.filter { c ->
            val matchesQuery = query.isBlank() ||
                    c.caseIdentifier.contains(query, ignoreCase = true) ||
                    c.specimenType.contains(query, ignoreCase = true) ||
                    c.clinicalReportText.contains(query, ignoreCase = true)

            val matchesFilter = when (filter) {
                "Draft" -> c.status == CaseStatus.DRAFT
                "Analyzed" -> c.status == CaseStatus.ANALYZED_SIMULATED || c.status == CaseStatus.ANALYZED_VERIFIED
                "Under Review" -> c.reviewStatus == "Under Review"
                "Reviewed" -> c.reviewStatus == "Reviewed"
                else -> true
            }

            matchesQuery && matchesFilter
        }

        CaseHistoryUiState(
            cases = filtered,
            searchQuery = query,
            selectedFilter = filter,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CaseHistoryUiState(isLoading = true)
    )

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun onFilterSelected(filter: String) {
        _selectedFilter.value = filter
    }

    fun deleteCase(caseId: String) {
        viewModelScope.launch {
            repository.deleteCase(caseId)
        }
    }
}
