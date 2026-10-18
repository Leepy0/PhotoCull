package com.leepy0.photocull.ui.review

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import com.leepy0.photocull.ui.theme.Spacing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.BurstMode
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import com.leepy0.photocull.R
import com.leepy0.photocull.ui.components.IosLargeTopBar
import com.leepy0.photocull.ui.components.iosScroll
import com.leepy0.photocull.ui.components.rememberIosScrollBehavior
import com.leepy0.photocull.data.review.MediaCard
import com.leepy0.photocull.data.settings.BlurSensitivity
import com.leepy0.photocull.ui.LocalAppContainer
import com.leepy0.photocull.ui.components.BottomActionBar
import com.leepy0.photocull.ui.components.EmptyState
import com.leepy0.photocull.ui.components.ErrorState
import com.leepy0.photocull.ui.components.LoadingState
import com.leepy0.photocull.ui.components.PrimaryActionButton
import com.leepy0.photocull.ui.nav.ReviewTab
import com.leepy0.photocull.ui.viewer.LocalViewerNavigation
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
 * 검토(흐림·연사·영상). 주요 행동은 세 탭 모두 하단 같은 자리 하나:
 * 흐림·영상은 선택이 있으면 "n장 삭제 · 용량", 연사는 "베스트만 남기고 n장 삭제 · 용량",
 * 지울 것이 없으면 'n장 모두 유지'(보조 버튼).
 * 휴지통 이동은 확인 없이 하고 스낵바 [실행 취소]로 되돌린다(그룹 화면과 같은 결과 통로 [ReviewEvents]).
 */
@Composable
fun ReviewScreen(initialTab: ReviewTab, onOpenGroup: (Long) -> Unit, onOpenHome: () -> Unit) {
    val container = LocalAppContainer.current
    val events = rememberReviewEvents()
    val vm: ReviewViewModel = viewModel { ReviewViewModel(container, initialTab, createSavedStateHandle(), events) }
    // 홈·알림의 "연사 탭으로" 같은 요청: 이미 있는 화면의 탭만 바꾼다(선택·스크롤은 그대로)
    LaunchedEffect(events, vm) {
        events.tabRequest.filterNotNull().collect { events.consumeTabRequest()?.let(vm::selectTab) }
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    ReviewMessagesEffect(events, snackbar, onUndo = vm::undo)

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
    // 목록 스크롤은 1단/2단 배치·탭 전환과 무관하게 유지(폴드를 접고 펴도 보던 자리)
    val blurGrid = rememberLazyGridState()
    val burstGrid = rememberLazyGridState()
    val videoGrid = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    val scroll = rememberIosScrollBehavior()
    Scaffold(
        modifier = modifier.iosScroll(scroll),
        topBar = {
            Column {
                IosLargeTopBar(stringResource(R.string.review_title), scroll)
                ReviewTabs(state, actions.onSelectTab)
                // 탭이 비어 있으면 빈 화면이 분석 진행을 보여주므로 여기서는 한 번만
                if (state.phase == LoadPhase.READY && !state.isTabEmpty(state.tab)) AnalysisBanner(state.analysis)
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
                            gridState = blurGrid,
                            actions = actions,
                            onOpen = { card -> if (twoPane) previewId = card.id else openOverlay(ReviewTab.BLUR, card) },
                            onPreview = { card -> previewId = card.id },
                            empty = empty,
                        )
                        ReviewTab.BURST -> BurstTab(state = state, actions = actions, gridState = burstGrid, empty = empty)
                        ReviewTab.VIDEO -> VideoTab(
                            state = state,
                            actions = actions,
                            gridState = videoGrid,
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
                val lastId = viewerId
                viewerTab = null
                viewerId = null
                // 펼치면 오른쪽 미리보기가, 접힌 화면에서는 목록이 마지막으로 본 사진에서 이어진다
                if (tab == ReviewTab.BLUR && lastId != null) {
                    previewId = lastId
                    val index = state.blur.indexOfFirst { it.id == lastId }
                    if (index >= 0 && blurGrid.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                        scope.launch { blurGrid.scrollToItem(index) }
                    }
                }
            },
            startAtFocus = tab == ReviewTab.BLUR,
            onPageChanged = { viewerId = it.id },
            bottomBar = { card ->
                val marked = card.id in selection
                val navigation = LocalViewerNavigation.current
                MarkForDeletionToggle(
                    marked = marked,
                    onToggle = {
                        actions.onSetMarked(tab, card.id, !marked)
                        // 표시했으면 바로 다음 항목으로(100%로 확대된 사진은 밀어서 넘기기 어렵다)
                        if (!marked) navigation?.next()
                    },
                )
            },
        )
    }
}

@Composable
private fun ReviewTabs(state: ReviewUiState, onSelect: (ReviewTab) -> Unit) {
    // iOS segmented control
    val tabs = ReviewTab.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.xs)) {
        tabs.forEachIndexed { index, t ->
            SegmentedButton(
                selected = t == state.tab,
                onClick = { onSelect(t) },
                shape = SegmentedButtonDefaults.itemShape(index, tabs.size),
                icon = {},
                label = { Text(tabLabel(t, state.count(t).takeIf { state.phase == LoadPhase.READY }), maxLines = 1) },
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

/** 하단 주요 행동(세 탭 같은 자리): 지울 것이 있으면 삭제(빨강), 없으면 'n장 모두 유지'(보조) */
@Composable
private fun ReviewBottomBar(state: ReviewUiState, actions: ReviewActions) {
    if (state.phase != LoadPhase.READY) return
    val tab = state.tab
    val unit = state.unit(tab)
    val remaining = state.remainingCount(tab)
    val busyText = stringResource(R.string.review_working)
    val keep: @Composable () -> Unit = {
        AdaptiveActionRow(hint = stringResource(R.string.review_hint_keep), showHintWhenNarrow = true) {
            TonalActionButton(
                text = if (state.busy) busyText else keepRestLabel(remaining, unit, afterProcessing = tab in state.processedTabs),
                onClick = { actions.onKeepRest(tab) },
                enabled = !state.busy,
            )
        }
    }
    val delete: @Composable (hint: String, label: String, onClick: () -> Unit) -> Unit = { hint, label, onClick ->
        AdaptiveActionRow(hint = hint, showHintWhenNarrow = true) {
            PrimaryActionButton(
                text = if (state.busy) busyText else label,
                onClick = onClick,
                enabled = !state.busy,
                destructive = true,
            )
        }
    }
    when (tab) {
        ReviewTab.BURST -> {
            val plan = state.burstPlan
            when {
                !plan.isEmpty -> BottomActionBar {
                    delete(
                        stringResource(R.string.review_burst_hint),
                        stringResource(R.string.review_burst_best_only, plan.deleteIds.size, bytesText(plan.deleteBytes)),
                        actions.onKeepBestAll,
                    )
                }
                remaining > 0 -> BottomActionBar { keep() }
            }
        }
        else -> {
            val selected = state.selection(tab).size
            when {
                selected > 0 -> BottomActionBar {
                    delete(
                        stringResource(R.string.review_hint_delete),
                        deleteLabel(selected, state.selectedBytes(tab), unit),
                    ) { actions.onDelete(tab) }
                }
                remaining > 0 -> BottomActionBar { keep() }
            }
        }
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
