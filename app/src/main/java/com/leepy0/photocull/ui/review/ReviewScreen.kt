package com.leepy0.photocull.ui.review

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.BurstMode
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import com.leepy0.photocull.R
import com.leepy0.photocull.data.review.MediaCard
import com.leepy0.photocull.data.settings.BlurSensitivity
import com.leepy0.photocull.ui.LocalAppContainer
import com.leepy0.photocull.ui.components.BottomActionBar
import com.leepy0.photocull.ui.components.EmptyState
import com.leepy0.photocull.ui.components.ErrorState
import com.leepy0.photocull.ui.components.LoadingState
import com.leepy0.photocull.ui.nav.ReviewTab
import com.leepy0.photocull.ui.viewer.PhotoViewer
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** 검토 화면이 부르는 동작 모음(스크린샷 테스트에서는 기본값 = 아무것도 안 함) */
@Immutable
data class ReviewActions(
    val onSelectTab: (ReviewTab) -> Unit = {},
    val onSensitivity: (BlurSensitivity) -> Unit = {},
    val onToggle: (ReviewTab, Long) -> Unit = { _, _ -> },
    val onSetMarked: (ReviewTab, Long, Boolean) -> Unit = { _, _, _ -> },
    val onToggleAll: (ReviewTab) -> Unit = {},
    val onToggleIds: (ReviewTab, Collection<Long>) -> Unit = { _, _ -> },
    val onDelete: (ReviewTab) -> Unit = {},
    val onKeepRest: (ReviewTab) -> Unit = {},
    val onKeepBestAll: () -> Unit = {},
    val onOpenGroup: (Long) -> Unit = {},
    val onOpenHome: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * 검토(흐림·연사·영상). 주요 행동은 하단 하나: 선택이 있으면 "n장 삭제 · 용량 확보", 없으면 "남은 n장 모두 유지".
 * 휴지통 이동은 확인 없이 하고 스낵바 [실행 취소]로 되돌린다.
 */
@Composable
fun ReviewScreen(initialTab: ReviewTab, onOpenGroup: (Long) -> Unit, onOpenHome: () -> Unit) {
    val container = LocalAppContainer.current
    val vm: ReviewViewModel = viewModel { ReviewViewModel(container, initialTab) }
    // 같은 화면을 다른 탭으로 다시 열라는 요청(홈의 '연사 n그룹' 등)만 반영하고, 돌아올 때는 보던 탭 유지
    var appliedInitialTab by rememberSaveable { mutableStateOf(initialTab) }
    LaunchedEffect(initialTab) {
        if (appliedInitialTab != initialTab) {
            vm.selectTab(initialTab)
            appliedInitialTab = initialTab
        }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val events = rememberReviewEvents()

    LaunchedEffect(vm) {
        vm.messages.collect { message ->
            // 새 결과가 오면 이전 스낵바는 닫고 바로 보여준다(실행 취소는 가장 최근 작업 기준)
            snackbar.currentSnackbarData?.dismiss()
            launch { snackbar.showReviewMessage(context, message, vm::undo) }
        }
    }
    // 그룹 화면에서 정리하고 돌아온 결과(실행 취소 스낵바)
    LaunchedEffect(events) {
        events.pending.filterNotNull().collect { events.consume()?.let(vm::showExternal) }
    }

    ReviewContent(
        state = state,
        snackbarHostState = snackbar,
        actions = ReviewActions(
            onSelectTab = vm::selectTab,
            onSensitivity = vm::setSensitivity,
            onToggle = vm::toggle,
            onSetMarked = vm::setMarked,
            onToggleAll = vm::toggleAll,
            onToggleIds = vm::toggleGroupOfIds,
            onDelete = vm::deleteSelected,
            onKeepRest = vm::keepRemaining,
            onKeepBestAll = vm::keepBestOfAllGroups,
            onOpenGroup = onOpenGroup,
            onOpenHome = onOpenHome,
            onRetry = vm::retry,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewContent(
    state: ReviewUiState,
    actions: ReviewActions,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    // 오버레이 뷰어(접힌 화면의 흐림·영상) — 회전·접기에도 유지
    var viewerTab by rememberSaveable { mutableStateOf<ReviewTab?>(null) }
    var viewerId by rememberSaveable { mutableStateOf<Long?>(null) }
    // 펼친 화면 흐림 탭의 오른쪽 미리보기
    var previewId by rememberSaveable { mutableStateOf<Long?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                TopAppBar(title = { Text(stringResource(R.string.review_title)) })
                ReviewTabs(state, actions.onSelectTab)
                AnalysisBanner(state.analysis)
            }
        },
        bottomBar = { ReviewBottomBar(state, actions) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val twoPane = maxWidth >= WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND.dp
            when (state.phase) {
                LoadPhase.LOADING -> LoadingState()
                LoadPhase.ERROR -> ErrorState(
                    title = stringResource(R.string.review_error_title),
                    cause = stringResource(R.string.review_error_cause),
                    fix = stringResource(R.string.review_error_fix),
                    onRetry = actions.onRetry,
                )
                LoadPhase.READY -> {
                    val empty: @Composable () -> Unit = { TabEmpty(state.tab, state, actions) }
                    val openOverlay: (ReviewTab, MediaCard) -> Unit = { tab, card ->
                        viewerTab = tab
                        viewerId = card.id
                    }
                    when (state.tab) {
                        ReviewTab.BLUR -> BlurTab(
                            state = state,
                            twoPane = twoPane,
                            previewId = previewId,
                            actions = actions,
                            onOpen = { card -> if (twoPane) previewId = card.id else openOverlay(ReviewTab.BLUR, card) },
                            empty = empty,
                        )
                        ReviewTab.BURST -> BurstTab(state = state, actions = actions, empty = empty)
                        ReviewTab.VIDEO -> VideoTab(
                            state = state,
                            actions = actions,
                            onOpen = { card -> openOverlay(ReviewTab.VIDEO, card) },
                            empty = empty,
                        )
                    }
                }
            }
        }
    }

    val tab = viewerTab
    val openId = viewerId
    if (tab != null && openId != null && state.phase == LoadPhase.READY) {
        val items = state.items(tab)
        val selection = state.selection(tab)
        PhotoViewer(
            items = items,
            initialIndex = items.indexOfFirst { it.id == openId }.coerceAtLeast(0),
            onDismiss = {
                viewerTab = null
                viewerId = null
            },
            startAtFocus = tab == ReviewTab.BLUR,
            onPageChanged = { viewerId = it.id },
            bottomBar = { card ->
                val marked = card.id in selection
                MarkForDeletionToggle(
                    marked = marked,
                    onToggle = { actions.onSetMarked(tab, card.id, !marked) },
                )
            },
        )
    }
}

@Composable
private fun ReviewTabs(state: ReviewUiState, onSelect: (ReviewTab) -> Unit) {
    PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
        ReviewTab.entries.forEach { t ->
            Tab(
                selected = t == state.tab,
                onClick = { onSelect(t) },
                text = { Text(tabLabel(t, state.count(t).takeIf { state.phase == LoadPhase.READY }), maxLines = 1) },
            )
        }
    }
}

@Composable
private fun tabLabel(tab: ReviewTab, count: Int?): String = when (tab) {
    ReviewTab.BLUR -> if (count == null) stringResource(R.string.review_tab_blur_plain) else stringResource(R.string.review_tab_blur, count)
    ReviewTab.BURST -> if (count == null) stringResource(R.string.review_tab_burst_plain) else stringResource(R.string.review_tab_burst, count)
    ReviewTab.VIDEO -> if (count == null) stringResource(R.string.review_tab_video_plain) else stringResource(R.string.review_tab_video, count)
}

@Composable
private fun ReviewBottomBar(state: ReviewUiState, actions: ReviewActions) {
    if (state.phase != LoadPhase.READY) return
    val tab = state.tab
    val selected = state.selection(tab).size
    val remaining = state.remainingCount(tab)
    if (selected == 0 && remaining == 0) return
    BottomActionBar {
        ReviewPrimaryAction(
            selectedCount = selected,
            selectedBytes = state.selectedBytes(tab),
            remainingCount = remaining,
            unit = state.unit(tab),
            busy = state.busy,
            onDelete = { actions.onDelete(tab) },
            onKeepRest = { actions.onKeepRest(tab) },
        )
    }
}

/** 탭이 비었을 때: 왜 비었는지 + 다음 행동(다음 탭 / 홈) */
@Composable
private fun TabEmpty(tab: ReviewTab, state: ReviewUiState, actions: ReviewActions) {
    val next = state.nextTabWithItems(tab)
    val nextLabel = next?.let { goToLabel(it, state.count(it)) }
    val goNext: (() -> Unit)? = next?.let { { actions.onSelectTab(it) } }
    when {
        state.analysis.running -> EmptyState(
            icon = Icons.Outlined.HourglassTop,
            title = stringResource(R.string.review_analyzing_title),
            body = stringResource(R.string.review_analyzing_body, state.analysis.done, state.analysis.total),
            actionLabel = nextLabel,
            onAction = goNext,
        )
        state.allDone -> EmptyState(
            icon = Icons.Outlined.TaskAlt,
            title = stringResource(R.string.review_all_done_title),
            body = stringResource(R.string.review_all_done_body),
            actionLabel = stringResource(R.string.review_go_home),
            onAction = actions.onOpenHome,
        )
        else -> {
            val (icon, title, body) = when (tab) {
                ReviewTab.BLUR -> Triple(
                    Icons.Outlined.BlurOn,
                    stringResource(R.string.review_blur_empty_title),
                    stringResource(
                        if (state.sensitivity == BlurSensitivity.HIGH) R.string.review_blur_empty_body_high
                        else R.string.review_blur_empty_body_raise,
                    ),
                )
                ReviewTab.BURST -> Triple(
                    Icons.Outlined.BurstMode,
                    stringResource(R.string.review_burst_empty_title),
                    stringResource(R.string.review_burst_empty_body),
                )
                ReviewTab.VIDEO -> Triple(
                    Icons.Outlined.VideoLibrary,
                    stringResource(R.string.review_video_empty_title),
                    stringResource(R.string.review_video_empty_body),
                )
            }
            EmptyState(icon = icon, title = title, body = body, actionLabel = nextLabel, onAction = goNext)
        }
    }
}

@Composable
private fun goToLabel(tab: ReviewTab, count: Int): String = when (tab) {
    ReviewTab.BLUR -> stringResource(R.string.review_go_blur, count)
    ReviewTab.BURST -> stringResource(R.string.review_go_burst, count)
    ReviewTab.VIDEO -> stringResource(R.string.review_go_video, count)
}
