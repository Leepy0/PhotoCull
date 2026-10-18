package com.leepy0.photocull.ui.trash

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.leepy0.photocull.R
import com.leepy0.photocull.ui.components.IosLargeTopBar
import com.leepy0.photocull.ui.components.iosScroll
import com.leepy0.photocull.ui.components.rememberIosScrollBehavior
import com.leepy0.photocull.ui.LocalAppContainer
import com.leepy0.photocull.ui.components.BottomActionBar
import com.leepy0.photocull.ui.components.EmptyState
import com.leepy0.photocull.ui.components.ErrorState
import com.leepy0.photocull.ui.components.LoadingState
import com.leepy0.photocull.ui.components.PrimaryActionButton
import com.leepy0.photocull.ui.components.StatusKind
import com.leepy0.photocull.ui.components.StatusLine
import com.leepy0.photocull.ui.review.AdaptiveActionRow
import com.leepy0.photocull.ui.review.bytesText
import com.leepy0.photocull.ui.theme.Spacing
import com.leepy0.photocull.ui.viewer.PhotoViewer
import kotlinx.coroutines.launch

/** 휴지통 화면이 부르는 동작 모음(스크린샷 테스트에서는 기본값 = 아무것도 안 함) */
@Immutable
data class TrashActions(
    val onToggle: (Long) -> Unit = {},
    val onToggleAll: () -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onRestoreSelected: () -> Unit = {},
    val onRestoreOne: (Long) -> Unit = {},
    val onAskDeleteSelected: () -> Unit = {},
    val onAskEmptyTrash: () -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onDismissConfirm: () -> Unit = {},
    val onRetry: () -> Unit = {},
    /** 비었을 때 다음 행동. null이면 버튼을 숨긴다 */
    val onOpenReview: (() -> Unit)? = null,
)

/**
 * 휴지통. 주요 행동은 하단 하나: "선택한 n개 복원"(선택이 없으면 보관 안내).
 * 영구 삭제는 상단 메뉴(선택 영구 삭제 / 휴지통 비우기) → 확인 대화상자.
 */
@Composable
fun TrashScreen(onOpenReview: (() -> Unit)? = null) {
    val container = LocalAppContainer.current
    val vm: TrashViewModel = viewModel { TrashViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(vm) {
        vm.messages.collect { message ->
            snackbar.currentSnackbarData?.dismiss()
            launch { snackbar.showSnackbar(message.text(context), duration = SnackbarDuration.Short) }
        }
    }

    TrashContent(
        state = state,
        snackbarHostState = snackbar,
        actions = TrashActions(
            onToggle = vm::toggle,
            onToggleAll = vm::toggleAll,
            onClearSelection = vm::clearSelection,
            onRestoreSelected = vm::restoreSelected,
            onRestoreOne = vm::restoreOne,
            onAskDeleteSelected = vm::askDeleteSelected,
            onAskEmptyTrash = vm::askEmptyTrash,
            onConfirmDelete = vm::confirmDelete,
            onDismissConfirm = vm::dismissConfirm,
            onRetry = vm::retry,
            onOpenReview = onOpenReview,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashContent(
    state: TrashUiState,
    actions: TrashActions,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    /** 스크린샷 테스트용: 메뉴를 펼친 채로 그린다 */
    initialMenuOpen: Boolean = false,
) {
    // 오버레이 뷰어 — 회전·접기에도 유지
    var viewerId by rememberSaveable { mutableStateOf<Long?>(null) }
    val ready = state.phase == TrashPhase.READY

    // 선택 중 뒤로 가기 = 선택 해제
    BackHandler(enabled = ready && state.selection.isNotEmpty() && viewerId == null) { actions.onClearSelection() }

    val scroll = rememberIosScrollBehavior()
    Scaffold(
        modifier = modifier.iosScroll(scroll),
        topBar = {
            IosLargeTopBar(
                title = stringResource(R.string.trash_title),
                scrollBehavior = scroll,
                actions = {
                    if (ready && !state.isEmpty) {
                        TrashMenu(
                            selectedCount = state.selection.size,
                            enabled = !state.busy,
                            initialOpen = initialMenuOpen,
                            onDeleteSelected = actions.onAskDeleteSelected,
                            onEmptyTrash = actions.onAskEmptyTrash,
                        )
                    }
                },
            )
        },
        bottomBar = { TrashBottomBar(state, actions) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state.phase) {
                TrashPhase.LOADING -> LoadingState()
                TrashPhase.ERROR -> ErrorState(
                    title = stringResource(R.string.trash_error_title),
                    cause = stringResource(R.string.trash_error_cause),
                    fix = stringResource(R.string.trash_error_fix),
                    onRetry = actions.onRetry,
                )
                TrashPhase.READY -> if (state.isEmpty) {
                    EmptyState(
                        icon = Icons.Outlined.Delete,
                        title = stringResource(R.string.trash_empty_title),
                        body = if (state.retentionDays > 0) {
                            stringResource(R.string.trash_empty_body, state.retentionDays)
                        } else {
                            stringResource(R.string.trash_empty_body_forever)
                        },
                        actionLabel = actions.onOpenReview?.let { stringResource(R.string.trash_empty_action) },
                        onAction = actions.onOpenReview,
                    )
                } else {
                    TrashGrid(state = state, actions = actions, onOpen = { viewerId = it })
                }
            }
        }
    }

    val openId = viewerId
    if (openId != null && ready) {
        val items = state.items
        PhotoViewer(
            items = items,
            initialIndex = items.indexOfFirst { it.id == openId }.coerceAtLeast(0),
            onDismiss = { viewerId = null },
            startAtFocus = false,
            onPageChanged = { viewerId = it.id },
            bottomBar = { card ->
                TrashViewerBar(
                    daysLeft = state.daysLeft(card),
                    busy = state.busy,
                    onRestore = {
                        // 뷰어를 닫고 복원 결과는 휴지통 화면 스낵바로 알린다(뷰어 창이 스낵바를 가리지 않게)
                        viewerId = null
                        actions.onRestoreOne(card.id)
                    },
                )
            },
        )
    }

    state.confirm?.let { confirm ->
        DeleteConfirmDialog(confirm = confirm, onConfirm = actions.onConfirmDelete, onDismiss = actions.onDismissConfirm)
    }
}

@Composable
private fun TrashGrid(state: TrashUiState, actions: TrashActions, onOpen: (Long) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TrashHeader(state, actions.onToggleAll)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(Spacing.gridCellMin),
            contentPadding = PaddingValues(start = Spacing.m, end = Spacing.m, bottom = Spacing.m),
            horizontalArrangement = Arrangement.spacedBy(Spacing.gridGap),
            verticalArrangement = Arrangement.spacedBy(Spacing.gridGap),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.items, key = { it.id }) { card ->
                TrashCell(
                    card = card,
                    selected = card.id in state.selection,
                    daysLeft = state.daysLeft(card),
                    onToggle = { actions.onToggle(card.id) },
                    onOpen = { onOpen(card.id) },
                )
            }
        }
    }
}

/** "12개 · 1.2 GB · 3개 선택" + [모두 선택 / 선택 해제] */
@Composable
private fun TrashHeader(state: TrashUiState, onToggleAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = Spacing.m, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val caption = buildString {
            append(stringResource(R.string.trash_summary, state.items.size, bytesText(state.totalBytes)))
            if (state.selection.isNotEmpty()) {
                append(" · ")
                append(stringResource(R.string.trash_selected_count, state.selection.size))
            }
        }
        Text(
            caption,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onToggleAll, modifier = Modifier.heightIn(min = Spacing.touchTarget)) {
            Text(stringResource(if (state.allSelected) R.string.trash_clear_selection else R.string.trash_select_all))
        }
    }
}

@Composable
private fun TrashBottomBar(state: TrashUiState, actions: TrashActions) {
    if (state.phase != TrashPhase.READY || state.isEmpty) return
    BottomActionBar {
        val selected = state.selection.size
        if (selected > 0) {
            AdaptiveActionRow(hint = stringResource(R.string.trash_restore_hint), showHintWhenNarrow = false) {
                PrimaryActionButton(
                    text = if (state.busy) stringResource(R.string.trash_working) else stringResource(R.string.trash_restore_selected, selected),
                    onClick = actions.onRestoreSelected,
                    enabled = !state.busy,
                )
            }
        } else {
            // 선택 전: 주요 버튼 자리에 보관 안내(같은 높이라 선택해도 화면이 흔들리지 않는다)
            StatusLine(
                kind = StatusKind.INFO,
                text = if (state.retentionDays > 0) {
                    stringResource(R.string.trash_info_retention, state.retentionDays)
                } else {
                    stringResource(R.string.trash_info_forever)
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryButtonHeight),
            )
        }
    }
}

/** 상단 앱 바 메뉴: 영구 삭제 동작은 주요 행동(복원)과 멀리 둔다 */
@Composable
private fun TrashMenu(
    selectedCount: Int,
    enabled: Boolean,
    initialOpen: Boolean,
    onDeleteSelected: () -> Unit,
    onEmptyTrash: () -> Unit,
) {
    var open by remember { mutableStateOf(initialOpen) }
    val danger = MenuDefaults.itemColors(
        textColor = MaterialTheme.colorScheme.error,
        leadingIconColor = MaterialTheme.colorScheme.error,
    )
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.trash_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (selectedCount > 0) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.trash_menu_delete_selected, selectedCount)) },
                    leadingIcon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null) },
                    colors = danger,
                    onClick = {
                        open = false
                        onDeleteSelected()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.trash_menu_empty)) },
                leadingIcon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
                colors = danger,
                onClick = {
                    open = false
                    onEmptyTrash()
                },
            )
        }
    }
}
