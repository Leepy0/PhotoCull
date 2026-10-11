package com.leepy0.photocull.ui.nav

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavBackStackEntry
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
import com.leepy0.photocull.ui.review.ReviewEvents
import com.leepy0.photocull.ui.review.ReviewScreen
import com.leepy0.photocull.ui.review.rememberReviewEvents
import com.leepy0.photocull.ui.settings.SettingsScreen
import com.leepy0.photocull.ui.trash.TrashScreen
import com.leepy0.photocull.ui.update.UpdateSheetHost
import com.leepy0.photocull.util.Permissions
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
 * - 시작 화면은 언제나 홈. 필수 권한이 없으면([startOnboarding]) 처음 한 번 시작(온보딩) 화면을 그 위에 올린다.
 * - 하단 탭을 오가도 각 탭의 상태(검토의 탭·선택·스크롤 포함)를 되살린다. 홈·알림이 특정 검토 탭을 열 때는
 *   [ReviewEvents.requestTab]으로 이미 있는 검토 화면의 탭만 바꾼다.
 * - 화면을 여는 동작은 화면이 완전히 보일 때만(빠르게 두 번 눌러 두 번 이동하지 않게).
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
    val countsFlow = remember(container) { container.review.counts() }
    val counts by countsFlow.collectAsStateWithLifecycle(initialValue = ReviewCounts())
    var showUpdate by rememberSaveable { mutableStateOf(false) }
    val reviewEvents = rememberReviewEvents()
    val activity = LocalActivity.current

    // 필수 권한이 없으면 홈 위에 시작 화면을 한 번 올린다(회전·재생성 때 다시 올리지 않음)
    var onboardingPushed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(startOnboarding) {
        if (startOnboarding && !onboardingPushed) {
            onboardingPushed = true
            navController.navigate(OnboardingRoute(firstRun = true)) { launchSingleTop = true }
        }
    }

    val onOnboarding = current?.isRoute(OnboardingRoute::class) == true
    LaunchedEffect(pendingDestination, onOnboarding) {
        val destination = pendingDestination ?: return@LaunchedEffect
        // 첫 실행 권한 안내 중이면 마칠 때까지 미룬다(업데이트 시트는 바로)
        if (onOnboarding && destination != AppDestinations.UPDATE) return@LaunchedEffect
        when (destination) {
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
        NavHost(navController = navController, startDestination = HomeRoute) {
            composable<HomeRoute> {
                val owner = LocalLifecycleOwner.current
                HomeScreen(
                    onOpenImport = dropUnlessResumed { navController.navigate(ImportRoute) { launchSingleTop = true } },
                    onOpenReview = { tab ->
                        whenResumed(owner) { navController.openReview(reviewEvents, tab) }
                    },
                    onOpenTrash = dropUnlessResumed { navController.navigateTopLevel(TrashRoute) },
                    onOpenUpdate = { showUpdate = true },
                    onOpenOnboarding = dropUnlessResumed { navController.navigate(OnboardingRoute()) { launchSingleTop = true } },
                )
            }
            composable<ReviewRoute> { entry ->
                val owner = LocalLifecycleOwner.current
                val tab = runCatching { ReviewTab.valueOf(entry.toRoute<ReviewRoute>().tab) }.getOrDefault(ReviewTab.BLUR)
                ReviewScreen(
                    initialTab = tab,
                    onOpenGroup = { id ->
                        whenResumed(owner) { navController.navigate(GroupRoute(id)) { launchSingleTop = true } }
                    },
                    onOpenHome = dropUnlessResumed { navController.navigateTopLevel(HomeRoute) },
                )
            }
            composable<GroupRoute> { entry ->
                GroupScreen(
                    groupId = entry.toRoute<GroupRoute>().groupId,
                    // 뒤로 화살표를 두 번 눌러 검토 화면까지 닫히지 않게
                    onBack = dropUnlessResumed { navController.popBackStack() },
                    // 정리를 마치면 닫는다(그사이 다른 화면으로 갔으면 그대로 둔다)
                    onDone = { navController.popIfOnTop(entry) },
                )
            }
            composable<TrashRoute> {
                TrashScreen(onOpenReview = dropUnlessResumed { navController.navigateTopLevel(ReviewRoute()) })
            }
            composable<SettingsRoute> {
                SettingsScreen(
                    onOpenOnboarding = dropUnlessResumed { navController.navigate(OnboardingRoute()) { launchSingleTop = true } },
                    onOpenUpdate = { showUpdate = true },
                )
            }
            composable<ImportRoute> {
                ImportScreen(
                    onBack = dropUnlessResumed { if (!navController.popBackStack()) navController.navigateTopLevel(HomeRoute) },
                    onOpenReview = dropUnlessResumed {
                        // 가져오기 화면은 기록에 남기지 않고, 검토 화면은 보던 상태가 있으면 되살린다
                        navController.navigate(ReviewRoute()) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = false }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable<OnboardingRoute> { entry ->
                val firstRun = entry.toRoute<OnboardingRoute>().firstRun
                // 첫 실행에서 권한 없이 뒤로 가면 쓸 수 없는 홈 대신 앱을 닫는다
                BackHandler(enabled = firstRun) {
                    if (Permissions.hasAllFilesAccess()) navController.popIfOnTop(entry) else activity?.finish()
                }
                OnboardingScreen(onDone = { navController.popIfOnTop(entry) })
            }
        }
    }

    UpdateSheetHost(visible = showUpdate, onDismiss = { showUpdate = false })
}

private fun NavDestination.isRoute(routeClass: KClass<*>): Boolean = hierarchy.any { it.hasRoute(routeClass) }

/** 하단 탭 이동: 각 탭의 상태(스크롤·선택 등)를 저장하고 되살린다 */
private fun NavHostController.navigateTopLevel(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** 특정 검토 탭 열기: 이미 있는 검토 화면(되살린 화면 포함)이 요청을 받아 탭만 바꾼다 */
private fun NavHostController.openReview(events: ReviewEvents, tab: ReviewTab) {
    events.requestTab(tab)
    navigateTopLevel(ReviewRoute(tab.name))
}

/** [entry]가 맨 위일 때만 닫는다(두 번 불려도 그 아래 화면까지 닫지 않게) */
private fun NavHostController.popIfOnTop(entry: NavBackStackEntry) {
    if (currentBackStackEntry?.id == entry.id) popBackStack()
}

/** 화면이 완전히 보일 때만 실행([dropUnlessResumed]의 인자 있는 판) */
private inline fun whenResumed(owner: LifecycleOwner, block: () -> Unit) {
    if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) block()
}
