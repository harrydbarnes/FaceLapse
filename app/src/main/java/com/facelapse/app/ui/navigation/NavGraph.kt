package com.facelapse.app.ui.navigation

import android.content.res.Configuration
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.facelapse.app.ui.home.HomeScreen
import com.facelapse.app.ui.project.FaceAuditScreen
import com.facelapse.app.ui.project.ProjectDetailScreen
import com.facelapse.app.ui.settings.SettingsScreen

@Composable
fun FaceLapseNavGraph(navController: NavHostController) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isWideScreen = configuration.screenWidthDp > 600

    val topLevelDestinations = listOf(Screen.Home, Screen.Settings)
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Logic to hide nav bar/rail on detail screens if desired, but requirement implies structural navigation.
    // For "Project Detail", maybe we want full screen?
    // Let's show the nav bar/rail for Home and Settings, but maybe hide for Project Detail?
    // Usually "Project Management" is a major feature, so maybe Project List is Home.
    // Let's keep it visible for now, or hide it if it takes too much space.
    // Actually, Project Detail needs maximum space for photos. I will hide it for ProjectDetail.
    val showNav = topLevelDestinations.any { it.route == currentDestination?.route }

    if (isWideScreen) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (showNav) {
                NavigationRail {
                    topLevelDestinations.forEach { screen ->
                        NavigationRailItem(
                            icon = { Icon(screen.icon!!, contentDescription = screen.title) },
                            label = { Text(screen.title) },
                            selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }

            // Content
            NavHostContainer(navController = navController, modifier = Modifier.weight(1f))
        }
    } else {
        Scaffold(
            bottomBar = {
                if (showNav) {
                    NavigationBar {
                        topLevelDestinations.forEach { screen ->
                            NavigationBarItem(
                                icon = { Icon(screen.icon!!, contentDescription = screen.title) },
                                label = { Text(screen.title) },
                                selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                                onClick = {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )
                        }
                    }
                }
            }
        ) { padding ->
            NavHostContainer(navController = navController, modifier = Modifier.padding(padding))
        }
    }
}

@Composable
fun NavHostContainer(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(navController = navController, startDestination = Screen.Home.route, modifier = modifier) {
        composable(Screen.Home.route) {
            HomeScreen(
                onProjectClick = { projectId ->
                    navController.navigate(Screen.ProjectDetails.createRoute(projectId))
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }
        composable(
            route = Screen.ProjectDetails.route,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) {
            ProjectDetailScreen(
                onBackClick = { navController.popBackStack() },
                onNavigateToFaceAudit = { projectId ->
                    navController.navigate(Screen.FaceAudit.createRoute(projectId))
                }
            )
        }
        composable(
            route = Screen.FaceAudit.route,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) {
            FaceAuditScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}
