package com.leepy0.photocull.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import com.leepy0.photocull.R
import com.leepy0.photocull.ui.LocalAppContainer
import com.leepy0.photocull.ui.components.BottomActionBar
import com.leepy0.photocull.ui.components.PrimaryActionButton
import com.leepy0.photocull.ui.importer.ContentMaxWidth
import com.leepy0.photocull.ui.importer.KoreanLineBreaks
import com.leepy0.photocull.ui.importer.countText
import com.leepy0.photocull.ui.importer.rememberFolderAccessLauncher
import com.leepy0.photocull.ui.importer.reviewPartsText
import com.leepy0.photocull.ui.importer.sizeText
import com.leepy0.photocull.ui.nav.ReviewTab
import com.leepy0.photocull.ui.review.AdaptiveActionRow
import com.leepy0.photocull.ui.theme.Spacing

/** 홈 화면이 부르는 동작(스크린샷 테스트에서는 기본값 = 아무것도 안 함) */
@Immutable
data class HomeActions(
    val onOpenImport: () -> Unit = {},
    val onOpenReview: (ReviewTab) -> Unit = {},
    /** 방금 끝난 가져오기 결과를 닫고 검토로 */
    val onStartReview: (ReviewTab) -> Unit = {},
    val onOpenUpdate: () -> Unit = {},
    val onGrantPermission: () -> Unit = {},
    val onGrantFolderAccess: (volumeId: String) -> Unit = {},
    val onRescan: () -> Unit = {},
    val onDismissResult: () -> Unit = {},
    val onRetryReview: () -> Unit = {},
)

/**
 * 홈. 맨 위에 카드 상태(가장 크게), 아래에 검토 대기·마지막 가져오기.
 * 주요 행동(하단 하나): 권한·폴더 승인이 필요하면 그것, 카드에 새 파일이 있으면 [가져오기], 아니면 [검토 계속].
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun HomeScreen(
    onOpenImport: () -> Unit,
    onOpenReview: (ReviewTab) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenUpdate: () -> Unit,
    onOpenOnboarding: () -> Unit,
) {
    val container = LocalAppContainer.current
    val vm: HomeViewModel = viewModel { HomeViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val folderAccess = rememberFolderAccessLauncher(
        onGranted = vm::onFolderAccessGranted,
        onUnavailable = vm::refreshCard,
    )
    HomeContent(
        state = state,
        actions = HomeActions(
            onOpenImport = onOpenImport,
            onOpenReview = onOpenReview,
            onStartReview = { tab ->
                vm.dismissResult()
                onOpenReview(tab)
            },
            onOpenUpdate = onOpenUpdate,
            onGrantPermission = onOpenOnboarding,
            onGrantFolderAccess = { volumeId -> folderAccess(volumeId, vm.folderAccessIntent(volumeId)) },
            onRescan = vm::rescan,
            onDismissResult = vm::dismissResult,
            onRetryReview = vm::retryReview,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    state: HomeUiState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    KoreanLineBreaks {
        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        if (state.updateAvailable) {
                            AssistChip(
                                onClick = actions.onOpenUpdate,
                                label = { Text(stringResource(R.string.home_update_chip)) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.SystemUpdate, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
                                },
                                modifier = Modifier.padding(end = Spacing.xs),
                            )
                        }
                    },
                )
            },
            bottomBar = { HomeBottomBar(state, actions) },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
                val wide = maxWidth >= WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND.dp
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                    if (wide) {
                        // 펼친 화면: 왼쪽 카드 상태, 오른쪽 검토 대기·기록
                        Row(
                            modifier = Modifier.widthIn(max = ContentMaxWidth * 2).fillMaxWidth().padding(Spacing.l),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.l),
                        ) {
                            Column(Modifier.weight(1f)) { CardStatusCard(state.status, actions) }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
                                ReviewSection(state, actions)
                                LastImportSection(state.visibleLastImport)
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.xs),
                            verticalArrangement = Arrangement.spacedBy(Spacing.l),
                        ) {
                            CardStatusCard(state.status, actions)
                            ReviewSection(state, actions)
                            LastImportSection(state.visibleLastImport)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeBottomBar(state: HomeUiState, actions: HomeActions) {
    val primary = state.primary
    if (primary == HomePrimary.None) return
    val parts = reviewPartsText(state.counts, state.analysis)
    BottomActionBar {
        AdaptiveActionRow(hint = null, showHintWhenNarrow = false) {
            when (primary) {
                HomePrimary.GrantPermission ->
                    PrimaryActionButton(stringResource(R.string.home_action_grant), onClick = actions.onGrantPermission)
                is HomePrimary.GrantFolderAccess ->
                    PrimaryActionButton(
                        stringResource(R.string.home_action_grant_folder),
                        onClick = { actions.onGrantFolderAccess(primary.volumeId) },
                    )
                is HomePrimary.Import -> PrimaryActionButton(
                    if (primary.newFiles != null && primary.newBytes != null) {
                        stringResource(R.string.home_action_import_count, countText(primary.newFiles), sizeText(primary.newBytes))
                    } else {
                        stringResource(R.string.home_action_import)
                    },
                    onClick = actions.onOpenImport,
                )
                is HomePrimary.StartReview -> PrimaryActionButton(
                    if (parts != null) stringResource(R.string.home_action_review_start_with, parts) else stringResource(R.string.home_action_review_start),
                    onClick = { actions.onStartReview(primary.tab) },
                )
                is HomePrimary.ContinueReview -> PrimaryActionButton(
                    if (parts != null) stringResource(R.string.home_action_review_continue_with, parts) else stringResource(R.string.home_action_review_continue),
                    onClick = { actions.onOpenReview(primary.tab) },
                )
                HomePrimary.None -> Unit
            }
        }
    }
}
