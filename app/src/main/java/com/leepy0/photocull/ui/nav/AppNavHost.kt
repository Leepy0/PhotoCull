package com.leepy0.photocull.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.leepy0.photocull.AppDestinations
import com.leepy0.photocull.R
import com.leepy0.photocull.data.review.ReviewCounts
import com.leepy0.photocull.ui.LocalAppContainer
import com.leepy0.photocull.ui.home.HomeScreen
import com.leepy0.photocull.ui.importer.ImportScreen
import com.leepy0.photocull.ui.onboarding.OnboardingScreen
import com.leepy0.photocull.ui.review.GroupScreen
import com.leepy0.photocull.ui.review.ReviewScreen
import com.leepy0.photocull.ui.settings.SettingsScreen
import com.leepy0.photocull.ui.trash.TrashScreen
import com.leepy0.photocull.ui.update.UpdateSheetHost
import kotlin.reflect.KClass

private data class TopLevel(
    val route: Any,
    val routeClass: KClass<*>,
    val labelRes: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

private val topLevels = listOf(
    TopLevel(HomeRoute, HomeRoute::class, R.string.nav_home, Icons.Outlined.Home, Icons.Filled.Home),
    TopLevel(ReviewRoute(), ReviewRoute::class, R.string.nav_review, Icons.Outlined.PhotoLibrary, Icons.Filled.PhotoLibrary),
    TopLevel(TrashRoute, TrashRoute::class, R.string.nav_trash, Icons.Outlined.Delete, Icons.Filled.Delete),
    TopLevel(SettingsRoute, SettingsRoute::class, R.string.nav_settings, Icons.Outlined.Settings, Icons.Filled.Settings),
)

/**
 * 하단 탭 4개(홈·검토·휴지통·설정). 접힌 화면은 하단 내비게이션 바, 펼친 화면은 내비게이션 레일.
 * 가져오기·그룹·온보딩 같은 흐름 화면에서는 탭을 숨긴다.
 */
@Composable
fun AppNavHost(
    startOnboarding: Boolean,
    pendingDestination: String?,
    onDestinationConsumed: () -> Unit,
) {
    val container = LocalAppContainer.current
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination
    val counts by container.review.counts().collectAsStateWithLifecycle(initialValue = ReviewCounts())
    var showUpdate by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(pendingDestination) {
        when (pendingDestination) {
            null -> return@LaunchedEffect
            AppDestinations.IMPORT -> navController.navigate(ImportRoute) { launchSingleTop = true }
            AppDestinations.REVIEW -> navController.navigateTopLevel(ReviewRoute())
            AppDestinations.UPDATE -> showUpdate = true
            AppDestinations.HOME -> navController.navigateTopLevel(HomeRoute)
        }
        onDestinationConsumed()
    }

    val isTopLevel = current == null || topLevels.any { t -> current.isRoute(t.routeClass) }
    val adaptiveType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfoV2())

    NavigationSuiteScaffold(
        layoutType = if (isTopLevel) adaptiveType else NavigationSuiteType.None,
        navigationSuiteItems = {
            topLevels.forEach { t ->
                val selected = current?.isRoute(t.routeClass) == true
                item(
                    selected = selected,
                    onClick = { navController.navigateTopLevel(t.route) },
                    icon = {
                        val icon = if (selected) t.selectedIcon else t.icon
                        if (t.routeClass == ReviewRoute::class && counts.total > 0) {
                            BadgedBox(badge = { Badge { Text(counts.total.toString()) } }) { Icon(icon, contentDescription = null) }
                        } else {
                            Icon(icon, contentDescription = null)
                        }
                    },
                    label = { Text(stringResource(t.labelRes)) },
                )
            }
        },
    ) {
        NavHost(navController = navController, startDestination = if (startOnboarding) OnboardingRoute else HomeRoute) {
            composable<HomeRoute> {
                HomeScreen(
                    onOpenImport = { navController.navigate(ImportRoute) { launchSingleTop = true } },
                    onOpenReview = { tab -> navController.navigateTopLevel(ReviewRoute(tab.name)) },
                    onOpenTrash = { navController.navigateTopLevel(TrashRoute) },
                    onOpenUpdate = { showUpdate = true },
                    onOpenOnboarding = { navController.navigate(OnboardingRoute) { launchSingleTop = true } },
                )
            }
            composable<ReviewRoute> { entry ->
                val tab = runCatching { ReviewTab.valueOf(entry.toRoute<ReviewRoute>().tab) }.getOrDefault(ReviewTab.BLUR)
                ReviewScreen(
                    initialTab = tab,
                    onOpenGroup = { id -> navController.navigate(GroupRoute(id)) },
                    onOpenHome = { navController.navigateTopLevel(HomeRoute) },
                )
            }
            composable<GroupRoute> { entry ->
                GroupScreen(groupId = entry.toRoute<GroupRoute>().groupId, onBack = { navController.popBackStack() })
            }
            composable<TrashRoute> { TrashScreen() }
            composable<SettingsRoute> {
                SettingsScreen(
                    onOpenOnboarding = { navController.navigate(OnboardingRoute) { launchSingleTop = true } },
                    onOpenUpdate = { showUpdate = true },
                )
            }
            composable<ImportRoute> {
                ImportScreen(
                    onBack = { if (!navController.popBackStack()) navController.navigateTopLevel(HomeRoute) },
                    onOpenReview = {
                        navController.navigate(ReviewRoute()) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = false }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable<OnboardingRoute> {
                OnboardingScreen(
                    onDone = {
                        navController.navigate(HomeRoute) {
                            popUpTo(navController.graph.id) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
        }
    }

    UpdateSheetHost(visible = showUpdate, onDismiss = { showUpdate = false })
}

private fun NavDestination.isRoute(routeClass: KClass<*>): Boolean = hierarchy.any { it.hasRoute(routeClass) }

private fun NavHostController.navigateTopLevel(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = route !is ReviewRoute
    }
}
