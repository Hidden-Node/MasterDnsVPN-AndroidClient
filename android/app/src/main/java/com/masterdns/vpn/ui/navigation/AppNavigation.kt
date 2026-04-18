package com.masterdns.vpn.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.masterdns.vpn.ui.home.HomeScreen
import com.masterdns.vpn.ui.info.InfoScreen
import com.masterdns.vpn.ui.logs.LogsScreen
import com.masterdns.vpn.ui.profiles.ProfilesScreen
import com.masterdns.vpn.ui.settings.GlobalSettingsScreen
import com.masterdns.vpn.ui.settings.SettingsScreen

sealed class Screen(val route: String, val title: String) {
    data object Home : Screen("home", "Home")
    data object Profiles : Screen("profiles", "Profiles")
    data object Logs : Screen("logs", "Logs")
    data object Settings : Screen("settings", "Settings")
    data object Info : Screen("info", "Info")
    data object ProfileSettings : Screen("profile_settings/{profileId}", "Profile Settings")
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val bottomBarScreens = listOf(Screen.Home, Screen.Profiles, Screen.Logs, Screen.Settings)
    fun navigateToRoot(screen: Screen) {
        val currentRoute = currentDestination?.route
        if (currentRoute == screen.route) {
            return
        }
        navController.navigate(screen.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = false
            }
            launchSingleTop = true
            restoreState = false
        }
    }
    val icons = mapOf(
        Screen.Home.route to Icons.Filled.Home,
        Screen.Profiles.route to Icons.Filled.Person,
        Screen.Logs.route to Icons.Filled.Terminal,
        Screen.Settings.route to Icons.Filled.Settings
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                bottomBarScreens.forEach { screen ->
                    NavigationBarItem(
                        icon = {
                            Icon(
                                icons[screen.route] ?: Icons.Filled.Home,
                                contentDescription = screen.title
                            )
                        },
                        label = {
                            Text(screen.title, fontWeight = FontWeight.Medium)
                        },
                        selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navigateToRoot(screen)
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    onNavigateToProfiles = {
                        navController.navigate(Screen.Profiles.route)
                    },
                    onOpenInfo = { navController.navigate(Screen.Info.route) }
                )
            }
            composable(Screen.Profiles.route) {
                ProfilesScreen(
                    onBack = { navigateToRoot(Screen.Home) },
                    onOpenSettings = { profileId ->
                        navController.navigate("profile_settings/$profileId")
                    }
                )
            }
            composable(Screen.Logs.route) {
                LogsScreen(
                    onBack = { navigateToRoot(Screen.Home) }
                )
            }
            composable(Screen.Settings.route) {
                GlobalSettingsScreen()
            }
            composable(Screen.ProfileSettings.route) {
                SettingsScreen(
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Info.route) {
                InfoScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
