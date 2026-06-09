package com.example.snorelyzer.presentation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.snorelyzer.presentation.insights.InsightsRoot
import com.example.snorelyzer.presentation.record.RecordRoot
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable
data object RecordRoute

@Serializable
data object InsightsRoute

@Serializable
data object SettingsRoute

private data class TopLevelDestination<T : Any>(
    val route: T,
    val routeClass: KClass<T>,
    val label: String,
    val icon: ImageVector
)

private val topLevelDestinations = listOf(
    TopLevelDestination(
        route = RecordRoute,
        routeClass = RecordRoute::class,
        label = "Record",
        icon = Icons.Outlined.Mic
    ),
    TopLevelDestination(
        route = InsightsRoute,
        routeClass = InsightsRoute::class,
        label = "Insights",
        icon = Icons.Outlined.BarChart
    ),
    TopLevelDestination(
        route = SettingsRoute,
        routeClass = SettingsRoute::class,
        label = "Settings",
        icon = Icons.Outlined.Settings
    )
)

@Composable
fun SnorelyzerApp(
    onRequestRecordingPermission: ((Boolean) -> Unit) -> Unit,
    onStartRecordingService: () -> Unit,
    onStopRecordingService: () -> Unit
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Surface(color = MaterialTheme.colorScheme.background) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    topLevelDestinations.forEach { destination ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.hasRoute(destination.routeClass)
                        } == true

                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.startDestinationId)
                                    launchSingleTop = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = destination.label
                                )
                            },
                            label = { Text(destination.label) }
                        )
                    }
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = RecordRoute,
                modifier = Modifier.padding(innerPadding),
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None }
            ) {
                composable<RecordRoute> {
                    RecordRoot(
                        onRequestRecordingPermission = onRequestRecordingPermission,
                        onStartRecordingService = onStartRecordingService,
                        onStopRecordingService = onStopRecordingService
                    )
                }
                composable<InsightsRoute> {
                    InsightsRoot()
                }
                composable<SettingsRoute> {
                    SettingsScreen()
                }
            }
        }
    }
}
