package com.cancerdetection.multimodal.research.ui.navigation

sealed class Screen(val route: String) {
    object Overview : Screen("overview")
    object NewAnalysis : Screen("new_analysis?caseId={caseId}") {
        fun createRoute(caseId: String? = null): String {
            return if (caseId != null) "new_analysis?caseId=$caseId" else "new_analysis"
        }
    }
    object AnalysisFindings : Screen("findings/{caseId}") {
        fun createRoute(caseId: String): String = "findings/$caseId"
    }
    object CaseHistory : Screen("history")
    object ReportPreview : Screen("report/{caseId}") {
        fun createRoute(caseId: String): String = "report/$caseId"
    }
    object ReviewNotes : Screen("notes/{caseId}") {
        fun createRoute(caseId: String): String = "notes/$caseId"
    }
}
