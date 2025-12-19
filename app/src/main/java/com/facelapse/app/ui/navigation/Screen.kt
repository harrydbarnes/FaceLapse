package com.facelapse.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector? = null) {
    object Home : Screen("home", "Home", Icons.Default.Home)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    object ProjectGraph : Screen("project_graph/{projectId}", "Project Graph") {
        fun createRoute(projectId: String) = "project_graph/$projectId"
    }
    object ProjectDetails : Screen("project_overview", "Project")
    object FaceAudit : Screen("face_audit", "Face Audit")
}
