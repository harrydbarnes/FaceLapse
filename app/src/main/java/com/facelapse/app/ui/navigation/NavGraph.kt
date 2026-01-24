package com.facelapse.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.facelapse.app.ui.home.HomeScreen
import com.facelapse.app.ui.home.HomeViewModel
import com.facelapse.app.ui.project.FaceAuditScreen
import com.facelapse.app.ui.project.ProjectDetailScreen
import com.facelapse.app.ui.project.ProjectViewModel
import com.facelapse.app.ui.settings.SettingsScreen

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun FaceLapseNavGraph(navController: NavHostController) {
    SharedTransitionLayout {
        NavHost(navController = navController, startDestination = Screen.Home.route) {
            composable(Screen.Home.route) {
                HomeAdaptiveRoute(
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@composable,
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    onBackClick = { navController.popBackStack() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun HomeAdaptiveRoute(
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onNavigateToSettings: () -> Unit
) {
    val navigator = rememberListDetailPaneScaffoldNavigator<String>()

    // State to track if Face Audit is active within the Detail pane
    var isFaceAuditActive by remember { mutableStateOf(false) }

    // If Face Audit is active, back press should return to Project Detail (exit audit mode)
    // If not, back press should follow navigator logic (e.g. Detail -> List)
    BackHandler(enabled = isFaceAuditActive) {
        isFaceAuditActive = false
    }

    // Only enable navigator back handler if Face Audit is NOT active
    // This ensures we don't pop the detail pane entirely when just trying to exit Face Audit
    BackHandler(enabled = !isFaceAuditActive && navigator.canNavigateBack()) {
        navigator.navigateBack()
    }

    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        value = navigator.scaffoldValue,
        listPane = {
            AnimatedPane {
                val homeViewModel: HomeViewModel = hiltViewModel()
                HomeScreen(
                    viewModel = homeViewModel,
                    onProjectClick = { projectId ->
                        // Reset audit mode when selecting a project
                        isFaceAuditActive = false
                        navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, projectId)
                    },
                    onSettingsClick = onNavigateToSettings,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope
                )
            }
        },
        detailPane = {
            AnimatedPane {
                val projectId = navigator.currentDestination?.content
                if (projectId != null) {
                    // Use projectId as key to ensure we get a ViewModel instance scoped to the current project selection
                    // This allows sharing the same instance between Detail and Audit views as long as projectId is constant
                    val projectViewModel: ProjectViewModel = hiltViewModel(key = projectId)

                    LaunchedEffect(projectId) {
                        projectViewModel.setProjectId(projectId)
                    }

                    if (isFaceAuditActive) {
                        FaceAuditScreen(
                            viewModel = projectViewModel,
                            onBackClick = { isFaceAuditActive = false }
                        )
                    } else {
                        ProjectDetailScreen(
                            viewModel = projectViewModel,
                            onBackClick = { navigator.navigateBack() },
                            onNavigateToFaceAudit = { isFaceAuditActive = true },
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                    }
                }
            }
        }
    )
}
