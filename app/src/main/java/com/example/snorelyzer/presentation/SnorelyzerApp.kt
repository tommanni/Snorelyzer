package com.example.snorelyzer.presentation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.snorelyzer.SleepTrackingSessionState
import com.example.snorelyzer.presentation.insights.InsightsRoot
import com.example.snorelyzer.presentation.record.ActiveSleepSessionRoot
import com.example.snorelyzer.presentation.record.RecordRoot
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable
data object RecordRoute

@Serializable
data object ActiveSleepSessionRoute

@Serializable
data object InsightsRoute

@Serializable
data object SettingsRoute

private data class TopLevelDestination<T : Any>(
    val route: T,
    val routeClass: KClass<T>,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val topLevelDestinations = listOf(
    TopLevelDestination(
        route = RecordRoute,
        routeClass = RecordRoute::class,
        label = "Record",
        selectedIcon = Icons.Filled.Mic,
        unselectedIcon = Icons.Outlined.Mic
    ),
    TopLevelDestination(
        route = InsightsRoute,
        routeClass = InsightsRoute::class,
        label = "Insights",
        selectedIcon = Icons.Filled.BarChart,
        unselectedIcon = Icons.Outlined.BarChart
    ),
    TopLevelDestination(
        route = SettingsRoute,
        routeClass = SettingsRoute::class,
        label = "Settings",
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnorelyzerApp(
    onRequestRecordingPermission: ((Boolean) -> Unit) -> Unit,
    onStartRecordingService: () -> Unit,
    onStopRecordingService: () -> Unit,
    onDiscardRecordingService: () -> Unit,
    sleepTrackingSessionStateFlow: StateFlow<SleepTrackingSessionState>,
    openActiveSessionRequest: Int = 0,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val sleepTrackingSessionState by sleepTrackingSessionStateFlow.collectAsStateWithLifecycle()
    val isServiceRunning = sleepTrackingSessionState.isRunning
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val isActiveSessionRoute = currentDestination?.hierarchy?.any {
        it.hasRoute(ActiveSleepSessionRoute::class)
    } == true
    val showRecordingDiscardedMessage: () -> Unit = {
        snackbarScope.launch {
            snackbarHostState.showSnackbar(
                message = "Discarded successfully",
                duration = SnackbarDuration.Short
            )
        }
    }

    LaunchedEffect(isServiceRunning, isActiveSessionRoute, currentDestination) {
        if (currentDestination == null) return@LaunchedEffect

        if (isServiceRunning && !isActiveSessionRoute) {
            navController.navigate(ActiveSleepSessionRoute) {
                launchSingleTop = true
            }
        } else if (!isServiceRunning && isActiveSessionRoute) {
            navController.navigate(RecordRoute) {
                popUpTo(navController.graph.findStartDestination().id) {
                    inclusive = false
                }
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(openActiveSessionRequest, isServiceRunning, currentDestination) {
        if (openActiveSessionRequest > 0 && isServiceRunning) {
            if (currentDestination == null) return@LaunchedEffect

            navController.navigate(ActiveSleepSessionRoute) {
                launchSingleTop = true
            }
        }
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        Box {
            Scaffold(
                topBar = {
                    if (!isActiveSessionRoute) {
                        CenterAlignedTopAppBar(
                            title = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Outlined.Bedtime,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = "Snorelyzer",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background
                            )
                        )
                    }
                },
                bottomBar = {
                    if (!isActiveSessionRoute) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            tonalElevation = 3.dp
                        ) {
                            topLevelDestinations.forEach { destination ->
                                val selected = currentDestination?.hierarchy?.any {
                                    it.hasRoute(destination.routeClass)
                                } == true

                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        navController.navigate(destination.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                                            contentDescription = destination.label
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = destination.label,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
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
                            onStopRecordingService = onStopRecordingService,
                            onDiscardRecordingService = onDiscardRecordingService,
                            onShowRecordingDiscardedMessage = showRecordingDiscardedMessage
                        )
                    }
                    composable<ActiveSleepSessionRoute> {
                        ActiveSleepSessionRoot(
                            onStopRecordingService = onStopRecordingService,
                            onDiscardRecordingService = onDiscardRecordingService,
                            onShowRecordingDiscardedMessage = showRecordingDiscardedMessage
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
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(16.dp)
            )
        }
    }
}
