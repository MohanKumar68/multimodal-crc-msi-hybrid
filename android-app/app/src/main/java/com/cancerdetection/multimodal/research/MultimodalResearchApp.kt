package com.cancerdetection.multimodal.research

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cancerdetection.multimodal.research.data.network.InferenceConfig
import com.cancerdetection.multimodal.research.data.repository.ResearchCaseRepository
import com.cancerdetection.multimodal.research.data.storage.LocalCaseStorage
import com.cancerdetection.multimodal.research.domain.model.CaseStatus
import com.cancerdetection.multimodal.research.ui.navigation.Screen
import com.cancerdetection.multimodal.research.ui.screens.findings.AnalysisFindingsScreen
import com.cancerdetection.multimodal.research.ui.screens.findings.AnalysisFindingsViewModel
import com.cancerdetection.multimodal.research.ui.screens.history.CaseHistoryScreen
import com.cancerdetection.multimodal.research.ui.screens.history.CaseHistoryViewModel
import com.cancerdetection.multimodal.research.ui.screens.newanalysis.NewAnalysisScreen
import com.cancerdetection.multimodal.research.ui.screens.newanalysis.NewAnalysisViewModel
import com.cancerdetection.multimodal.research.ui.screens.notes.ReviewNotesScreen
import com.cancerdetection.multimodal.research.ui.screens.notes.ReviewNotesViewModel
import com.cancerdetection.multimodal.research.ui.screens.overview.OverviewScreen
import com.cancerdetection.multimodal.research.ui.screens.overview.OverviewViewModel
import com.cancerdetection.multimodal.research.ui.screens.report.ReportPreviewScreen
import com.cancerdetection.multimodal.research.ui.screens.report.ReportPreviewViewModel
import com.cancerdetection.multimodal.research.ui.theme.MultimodalResearchTheme

@Composable
fun MultimodalResearchApp() {
    val context = LocalContext.current
    val storage = remember { LocalCaseStorage(context.applicationContext) }
    val repository = remember { ResearchCaseRepository.getInstance(storage) }
    val inferenceConfig = remember { InferenceConfig(context.applicationContext) }
    val navController = rememberNavController()

    MultimodalResearchTheme {
        NavHost(
            navController = navController,
            startDestination = Screen.Overview.route
        ) {
            // Overview Screen
            composable(Screen.Overview.route) {
                val overviewViewModel = remember { OverviewViewModel(repository) }
                OverviewScreen(
                    viewModel = overviewViewModel,
                    onNavigateToNewAnalysis = { caseId ->
                        navController.navigate(Screen.NewAnalysis.createRoute(caseId))
                    },
                    onNavigateToFindings = { caseId ->
                        navController.navigate(Screen.AnalysisFindings.createRoute(caseId))
                    },
                    onNavigateToHistory = {
                        navController.navigate(Screen.CaseHistory.route)
                    }
                )
            }

            // New Specimen Analysis Screen
            composable(
                route = Screen.NewAnalysis.route,
                arguments = listOf(
                    navArgument("caseId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val caseId = backStackEntry.arguments?.getString("caseId")
                val newAnalysisViewModel = remember(caseId) { NewAnalysisViewModel(repository, inferenceConfig) }
                NewAnalysisScreen(
                    caseIdToEdit = caseId,
                    viewModel = newAnalysisViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToFindings = { resultCaseId ->
                        navController.navigate(Screen.AnalysisFindings.createRoute(resultCaseId)) {
                            popUpTo(Screen.Overview.route)
                        }
                    }
                )
            }

            // Analysis Findings Screen
            composable(
                route = Screen.AnalysisFindings.route,
                arguments = listOf(
                    navArgument("caseId") {
                        type = NavType.StringType
                    }
                )
            ) { backStackEntry ->
                val caseId = backStackEntry.arguments?.getString("caseId") ?: ""
                val findingsViewModel = remember(caseId) { AnalysisFindingsViewModel(repository, inferenceConfig) }
                AnalysisFindingsScreen(
                    caseId = caseId,
                    viewModel = findingsViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToReport = { id ->
                        navController.navigate(Screen.ReportPreview.createRoute(id))
                    },
                    onNavigateToNotes = { id ->
                        navController.navigate(Screen.ReviewNotes.createRoute(id))
                    }
                )
            }

            // Case History Screen
            composable(Screen.CaseHistory.route) {
                val historyViewModel = remember { CaseHistoryViewModel(repository) }
                CaseHistoryScreen(
                    viewModel = historyViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onOpenCase = { selectedCase ->
                        if (selectedCase.status == CaseStatus.DRAFT) {
                            navController.navigate(Screen.NewAnalysis.createRoute(selectedCase.id))
                        } else {
                            navController.navigate(Screen.AnalysisFindings.createRoute(selectedCase.id))
                        }
                    }
                )
            }

            // Structured Report Preview Screen
            composable(
                route = Screen.ReportPreview.route,
                arguments = listOf(
                    navArgument("caseId") {
                        type = NavType.StringType
                    }
                )
            ) { backStackEntry ->
                val caseId = backStackEntry.arguments?.getString("caseId") ?: ""
                val reportViewModel = remember(caseId) { ReportPreviewViewModel(repository) }
                ReportPreviewScreen(
                    caseId = caseId,
                    viewModel = reportViewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToNotes = { id ->
                        navController.navigate(Screen.ReviewNotes.createRoute(id))
                    }
                )
            }

            // Clinician Review Questions & Notes Screen
            composable(
                route = Screen.ReviewNotes.route,
                arguments = listOf(
                    navArgument("caseId") {
                        type = NavType.StringType
                    }
                )
            ) { backStackEntry ->
                val caseId = backStackEntry.arguments?.getString("caseId") ?: ""
                val notesViewModel = remember(caseId) { ReviewNotesViewModel(repository) }
                ReviewNotesScreen(
                    caseId = caseId,
                    viewModel = notesViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}
