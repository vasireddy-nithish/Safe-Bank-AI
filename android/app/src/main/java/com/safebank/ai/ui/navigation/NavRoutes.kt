package com.safebank.ai.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.ui.graphics.vector.ImageVector

sealed class NavRoute(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    data object Home : NavRoute("home", "Home", Icons.Default.Home)
    data object Transfers : NavRoute("transfers", "Transfers", Icons.Default.SwapHoriz)
    data object History : NavRoute("history", "History", Icons.Default.History)
    data object Security : NavRoute("security", "Security", Icons.Default.Security)
    data object Profile : NavRoute("profile", "Profile", Icons.Default.Person)

    companion object {
        val bottomNavItems: List<NavRoute>
            get() = listOf(Home, Transfers, History, Security, Profile)
    }
}

/** Top-level routes — NOT part of bottom nav */
object TopLevelRoute {
    const val WELCOME = "welcome"
    const val REGISTER = "register"
    const val RESTRICTED = "restricted"
}
