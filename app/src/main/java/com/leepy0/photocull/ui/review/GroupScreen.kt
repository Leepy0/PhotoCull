package com.leepy0.photocull.ui.review

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import com.leepy0.photocull.R
import com.leepy0.photocull.data.db.GroupKind
import com.leepy0.photocull.data.review.GroupCard
import com.leepy0.photocull.data.review.MediaCard
import com.leepy0.photocull.ui.LocalAppContainer
import com.leepy0.photocull.ui.components.BottomActionBar
import com.leepy0.photocull.ui.components.EmptyState
import com.leepy0.photocull.ui.components.ErrorState
import com.leepy0.photocull.ui.components.LoadingState
import com.leepy0.photocull.ui.components.PrimaryActionButton
import com.leepy0.photocull.ui.components.SelectableThumbnail
import com.leepy0.photocull.ui.theme.Spacing
import com.leepy0.photocull.ui.viewer.CompareViewer
import com.leepy0.photocull.ui.viewer.CompareViewerState
import com.leepy0.photocull.ui.viewer.ViewerTopBar
import com.leepy0.photocull.ui.viewer.ZoomToggleButton
import com.leepy0.photocull.ui.viewer.rememberCompareViewerState
import com.leepy0.photocull.ui.viewer.viewerExifLine
import com.leepy0.photocull.ui.viewer.viewerMetaLine
import com.leepy0.photocull.util.Format
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Immutable
data class GroupActions(
    val onBack: () -> Unit = {},
    val onShow: (Long) -> Unit = {},
    val onToggle: (Long) -> Unit = {},
    val onSetMarked: (Long, Boolean) -> Unit = { _, _ -> },
    val onPinBest: (Long) -> Unit = {},
    val onDelete: () -> Unit = {},
    val onKeepAll: () -> Unit = {},
)

/**
 * 연사/유사 그룹 정리: 큰 확대 뷰어(같은 위치 비교) + 필름스트립(접힘: 아래 가로, 펼침: 오른쪽 그리드).
 * 기본 선택 = 베스트 외 전부(베스트는 지울 수 없다). 삭제하면 나머지는 '유지'로 처리하고 검토 화면으로 돌아간다.
 * 결과(실행 취소 스낵바)는 검토 화면과 함께 쓰는 [ReviewEvents]로 알려, 어느 화면에서든 되돌릴 수 있다.
 * [onDone]: 정리를 마쳐 화면을 닫을 때(기본은 [onBack]).
 */
@Composable
fun GroupScreen(groupId: Long, onBack: () -> Unit, onDone: () -> Unit = onBack) {
    val container = LocalAppContainer.current
    val events = rememberReviewEvents()
    val vm: GroupViewModel = viewModel(key = "group-$groupId") { GroupViewModel(container, groupId, events) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val currentOnDone by rememberUpdatedState(onDone)

    LaunchedEffect(vm) {
        vm.finished.collect { currentOnDone() }
    }
    ReviewMessagesEffect(events, snackbar, onUndo = vm::undo)

    GroupContent(
        state = state,
        snackbarHostState = snackbar,
        actions = GroupActions(
            onBack = onBack,
            onShow = vm::show,
            onToggle = vm::toggle,
            onSetMarked = vm::setMarked,
            onPinBest = vm::pinBest,
            onDelete = vm::deleteSelected,
            onKeepAll = vm::keepAll,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupContent(
    state: GroupUiState,
    actions: GroupActions,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    compareState: CompareViewerState = rememberCompareViewerState(),
) {
    val content = state as? GroupUiState.Content
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = {
                    if (content != null) {
                        Column {
                            Text(groupScreenTitle(content.group), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                stringResource(
                                    R.string.review_group_meta,
                                    "${Format.monthDay(content.group.startAt)} ${Format.time(content.group.startAt)}",
                                    groupSpan(content.group),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                },
            )
        },
        bottomBar = { if (content != null) GroupBottomBar(content, actions) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state) {
                GroupUiState.Loading -> LoadingState()
                GroupUiState.Missing -> EmptyState(
                    icon = Icons.Outlined.TaskAlt,
                    title = stringResource(R.string.review_group_missing_title),
                    body = stringResource(R.string.review_group_missing_body),
                    actionLabel = stringResource(R.string.review_group_back),
                    onAction = actions.onBack,
                )
                GroupUiState.Error -> ErrorState(
                    title = stringResource(R.string.review_error_title),
                    cause = stringResource(R.string.review_error_cause),
                    fix = stringResource(R.string.review_error_fix),
                    onRetry = actions.onBack,
                    retryLabel = stringResource(R.string.review_group_back),
                )
                is GroupUiState.Content -> GroupBody(state, actions, compareState)
            }
        }
    }
}

@Composable
private fun GroupBody(state: GroupUiState.Content, actions: GroupActions, compareState: CompareViewerState) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val twoPane = maxWidth >= WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND.dp
        if (twoPane) {
            Row(
                Modifier.fillMaxSize().padding(start = Spacing.m, end = Spacing.m, bottom = Spacing.m),
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                FrameViewer(state, compareState, Modifier.weight(VIEWER_WEIGHT).fillMaxHeight())
                Column(Modifier.weight(SIDE_WEIGHT).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    FrameActions(state, actions)
                    FrameGrid(state, actions, Modifier.weight(1f))
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                FrameViewer(state, compareState, Modifier.weight(1f).fillMaxWidth())
                FrameActions(state, actions, Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs))
                FrameStrip(state, actions)
            }
        }
    }
}

/** 큰 확대 뷰어 + 위쪽 정보(파일명·순서·선명도·배지·확대 토글) */
@Composable
private fun FrameViewer(state: GroupUiState.Content, compareState: CompareViewerState, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val current = state.current
    Box(modifier) {
        CompareViewer(card = current, state = compareState, modifier = Modifier.fillMaxSize())
        FrameBadges(
            group = state.group,
            card = current,
            marked = current.id in state.selection,
            modifier = Modifier.align(Alignment.BottomStart).padding(Spacing.s),
        )
        ViewerTopBar(
            title = current.fileName,
            meta = viewerMetaLine(current, state.currentIndex + 1, state.group.members.size),
            detail = viewerExifLine(current),
            onBack = null,
            applyInsets = false,
            modifier = Modifier.align(Alignment.TopCenter),
            actions = {
                ZoomToggleButton(
                    isZoomedIn = compareState.isZoomedIn,
                    enabled = compareState.canToggleZoom,
                    onClick = { scope.launch { compareState.toggleZoom() } },
                )
            },
        )
    }
}

@Composable
private fun FrameBadges(group: GroupCard, card: MediaCard, marked: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (card.id == group.bestId) StatusBadge(stringResource(R.string.review_group_best), BadgeKind.BEST)
        if (card.id in group.relativeBlurIds) StatusBadge(stringResource(R.string.review_group_relative_blur), BadgeKind.BLUR)
        if (marked) StatusBadge(stringResource(R.string.review_group_mark_delete), BadgeKind.MARKED)
    }
}

/** 지금 보는 장: [삭제 대상] 토글 · [베스트로 지정] */
@Composable
private fun FrameActions(state: GroupUiState.Content, actions: GroupActions, modifier: Modifier = Modifier) {
    val current = state.current
    val marked = current.id in state.selection
    val isBest = current.id == state.group.bestId
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        // 베스트는 지울 수 없다(지우려면 다른 사진을 먼저 베스트로 지정)
        FilterChip(
            selected = marked,
            onClick = { actions.onSetMarked(current.id, !marked) },
            enabled = !isBest && !state.busy,
            label = { Text(stringResource(if (isBest) R.string.review_group_best_locked else R.string.review_group_mark_delete)) },
            leadingIcon = when {
                isBest -> {
                    { Icon(Icons.Outlined.Star, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                }
                marked -> {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                }
                else -> null
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer,
                selectedLeadingIconColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
        )
        Spacer(Modifier.weight(1f))
        OutlinedButton(
            onClick = { actions.onPinBest(current.id) },
            enabled = !isBest && !state.busy,
            modifier = Modifier.heightIn(min = Spacing.touchTarget),
        ) {
            Icon(Icons.Outlined.Star, contentDescription = null, modifier = Modifier.size(Spacing.iconSmall + Spacing.xxs))
            Spacer(Modifier.size(Spacing.xs))
            Text(stringResource(R.string.review_group_set_best))
        }
    }
}

/** 접힌 화면: 아래 가로 필름스트립 */
@Composable
private fun FrameStrip(state: GroupUiState.Content, actions: GroupActions) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.currentIndex) {
        val visible = listState.layoutInfo.visibleItemsInfo
        val fully = visible.any { it.index == state.currentIndex && it.offset >= 0 && it.offset + it.size <= listState.layoutInfo.viewportEndOffset }
        if (!fully) listState.animateScrollToItem((state.currentIndex - 1).coerceAtLeast(0))
    }
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(start = Spacing.m, end = Spacing.m, bottom = Spacing.s),
        horizontalArrangement = Arrangement.spacedBy(Spacing.gridGap),
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(state.group.members, key = { _, m -> m.id }) { _, member ->
            FrameCell(state, member, actions, Modifier.size(Spacing.gridCellMin))
        }
    }
}

/** 펼친 화면: 오른쪽 그리드 */
@Composable
private fun FrameGrid(state: GroupUiState.Content, actions: GroupActions, modifier: Modifier = Modifier) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(state.currentIndex) {
        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == state.currentIndex }) {
            gridState.animateScrollToItem(state.currentIndex)
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(Spacing.gridCellMin),
        state = gridState,
        horizontalArrangement = Arrangement.spacedBy(Spacing.gridGap),
        verticalArrangement = Arrangement.spacedBy(Spacing.gridGap),
        modifier = modifier,
    ) {
        itemsIndexed(state.group.members, key = { _, m -> m.id }) { _, member ->
            FrameCell(state, member, actions)
        }
    }
}

@Composable
private fun FrameCell(state: GroupUiState.Content, member: MediaCard, actions: GroupActions, modifier: Modifier = Modifier) {
    val isCurrent = member.id == state.currentId
    val shape = MaterialTheme.shapes.small
    Box(
        modifier.then(
            if (isCurrent) Modifier.border(Spacing.xxs, MaterialTheme.colorScheme.primary, shape).padding(Spacing.xxs) else Modifier,
        ),
    ) {
        SelectableThumbnail(
            path = member.path,
            selected = member.id in state.selection,
            onToggle = { actions.onToggle(member.id) },
            onOpen = { actions.onShow(member.id) },
            contentDescription = stringResource(R.string.review_open_photo, member.fileName),
            selectable = member.id != state.group.bestId,
            label = member.sharpness?.let { stringResource(R.string.review_sharpness, it.roundToInt()) },
            badge = when {
                member.id == state.group.bestId -> { { CompactBadge(BadgeKind.BEST, stringResource(R.string.review_group_best)) } }
                member.id in state.group.relativeBlurIds -> { { CompactBadge(BadgeKind.BLUR, stringResource(R.string.review_group_relative_blur)) } }
                else -> null
            },
        )
    }
}

@Composable
private fun GroupBottomBar(state: GroupUiState.Content, actions: GroupActions) {
    BottomActionBar {
        val selected = state.selectedCount
        val hint = if (selected > 0) {
            stringResource(R.string.review_group_keep_rest_hint, state.group.members.size - selected)
        } else {
            stringResource(R.string.review_group_keep_all_hint, state.pendingIds.size)
        }
        AdaptiveActionRow(hint = hint, showHintWhenNarrow = true) {
            if (selected > 0) {
                PrimaryActionButton(
                    text = if (state.busy) stringResource(R.string.review_working) else deleteLabel(selected, state.selectedBytes, CountUnit.PHOTO),
                    onClick = actions.onDelete,
                    enabled = !state.busy,
                    destructive = true,
                )
            } else {
                TonalActionButton(
                    text = if (state.busy) stringResource(R.string.review_working) else stringResource(R.string.review_group_keep_all, state.pendingIds.size),
                    onClick = actions.onKeepAll,
                    enabled = !state.busy,
                )
            }
        }
    }
}

@Composable
private fun groupScreenTitle(group: GroupCard): String = stringResource(
    if (group.kind == GroupKind.BURST) R.string.review_group_title_burst else R.string.review_group_title_similar,
    group.members.size,
)

private const val VIEWER_WEIGHT = 0.62f
private const val SIDE_WEIGHT = 0.38f
