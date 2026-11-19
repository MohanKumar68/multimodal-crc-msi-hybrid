package com.cancerdetection.multimodal.research.ui.screens.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cancerdetection.multimodal.research.data.repository.ResearchCaseRepository
import com.cancerdetection.multimodal.research.domain.model.ResearchCase
import com.cancerdetection.multimodal.research.domain.model.ReviewNote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

data class ReviewNotesUiState(
    val researchCase: ResearchCase? = null,
    val authorName: String = "Pathologist Reviewer",
    val selectedCategory: String = "Histological Observation",
    val noteContent: String = "",
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null
)

class ReviewNotesViewModel(
    private val repository: ResearchCaseRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReviewNotesUiState())
    val uiState: StateFlow<ReviewNotesUiState> = _uiState.asStateFlow()

    fun loadCase(caseId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val caseItem = repository.getCaseById(caseId).firstOrNull()
            _uiState.value = _uiState.value.copy(
                researchCase = caseItem,
                isLoading = false
            )
        }
    }

    fun onAuthorNameChanged(name: String) {
        _uiState.value = _uiState.value.copy(authorName = name)
    }

    fun onCategorySelected(category: String) {
        _uiState.value = _uiState.value.copy(selectedCategory = category)
    }

    fun onNoteContentChanged(content: String) {
        _uiState.value = _uiState.value.copy(noteContent = content, error = null)
    }

    fun addNote(caseId: String) {
        val state = _uiState.value
        if (state.noteContent.trim().isBlank()) {
            _uiState.value = state.copy(error = "Please enter note content.")
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isSaving = true)
            val note = ReviewNote(
                author = state.authorName.ifBlank { "Clinician Reviewer" },
                category = state.selectedCategory,
                content = state.noteContent.trim()
            )
            repository.addNoteToCase(caseId, note)
            // Reload case
            val updated = repository.getCaseById(caseId).firstOrNull()
            _uiState.value = state.copy(
                researchCase = updated,
                noteContent = "",
                isSaving = false,
                error = null
            )
        }
    }

    fun updateReviewStatus(caseId: String, newStatus: String) {
        viewModelScope.launch {
            repository.updateReviewStatus(caseId, newStatus)
            val updated = repository.getCaseById(caseId).firstOrNull()
            _uiState.value = _uiState.value.copy(researchCase = updated)
        }
    }
}
