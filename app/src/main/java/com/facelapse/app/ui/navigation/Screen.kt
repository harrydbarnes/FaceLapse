package com.facelapse.app.ui.navigation

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object ProjectDetails : Screen("project/{projectId}") {
        fun createRoute(projectId: String) = "project/$projectId"
    }
    object Settings : Screen("settings")
    object Player : Screen("player/{projectId}") {
        fun createRoute(projectId: String) = "player/$projectId"
    }
}
