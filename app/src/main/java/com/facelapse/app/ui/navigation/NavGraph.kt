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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
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
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToFaceAudit = { projectId -> navController.navigate("face_audit/$projectId") }
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable(
                route = "face_audit/{projectId}",
                arguments = listOf(navArgument("projectId") { type = NavType.StringType })
            ) { backStackEntry ->
                val viewModel: ProjectViewModel = hiltViewModel()
                FaceAuditScreen(
                    viewModel = viewModel,
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
    onNavigateToSettings: () -> Unit,
    onNavigateToFaceAudit: (String) -> Unit
) {
    val navigator = rememberListDetailPaneScaffoldNavigator<String>()

    BackHandler(navigator.canNavigateBack()) {
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
                        navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, projectId)
                    },
                    onSettingsClick = onNavigateToSettings,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = this
                )
            }
        },
        detailPane = {
            AnimatedPane {
                val projectId = navigator.currentDestination?.content
                if (projectId != null) {
                    val projectViewModel: ProjectViewModel = hiltViewModel()
                    LaunchedEffect(projectId) {
                        projectViewModel.setProjectId(projectId)
                    }

                    ProjectDetailScreen(
                        viewModel = projectViewModel,
                        onBackClick = { navigator.navigateBack() },
                        onNavigateToFaceAudit = onNavigateToFaceAudit,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = this
                    )
                }
            }
        }
    )
}
