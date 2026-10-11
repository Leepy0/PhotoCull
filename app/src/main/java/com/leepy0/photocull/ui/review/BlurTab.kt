package com.leepy0.photocull.ui.review

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.leepy0.photocull.R
import com.leepy0.photocull.analysis.BlurThresholds
import com.leepy0.photocull.data.review.MediaCard
import com.leepy0.photocull.data.settings.BlurSensitivity
import com.leepy0.photocull.ui.components.SelectableThumbnail
import com.leepy0.photocull.ui.nav.ReviewTab
import com.leepy0.photocull.ui.theme.Spacing
import com.leepy0.photocull.ui.viewer.PhotoPreviewPane
import kotlin.math.roundToInt

/**
 * 흐림 탭: 민감도(즉시 반영) + 모두 선택/해제 + 흐린 순 그리드.
 * 펼친 화면([twoPane])에서는 오른쪽에 고른 사진을 100%로 보여준다(오버레이 대신).
 */
@Composable
internal fun BlurTab(
    state: ReviewUiState,
    twoPane: Boolean,
    previewId: Long?,
    actions: ReviewActions,
    onOpen: (MediaCard) -> Unit,
    empty: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val header: @Composable () -> Unit = {
        BlurHeader(
            sensitivity = state.sensitivity,
            itemCount = state.blur.size,
            selectedCount = state.blurSelection.size,
            onSensitivity = actions.onSensitivity,
            onToggleAll = { actions.onToggleAll(ReviewTab.BLUR) },
        )
    }
    if (!twoPane || state.blur.isEmpty()) {
        Column(modifier.fillMaxSize()) {
            header()
            if (state.blur.isEmpty()) {
                empty()
            } else {
                BlurGrid(
                    items = state.blur,
                    selection = state.blurSelection,
                    previewId = null,
                    onToggle = { actions.onToggle(ReviewTab.BLUR, it) },
                    onOpen = onOpen,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        return
    }
    // 펼친 화면: 왼쪽(민감도 + 그리드) · 오른쪽(고른 사진 100% 미리보기)
    val preview = state.blur.firstOrNull { it.id == previewId } ?: state.blur.first()
    Row(
        modifier.fillMaxSize().padding(end = Spacing.m, bottom = Spacing.m),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Column(Modifier.weight(GRID_WEIGHT).fillMaxHeight()) {
            header()
            BlurGrid(
                items = state.blur,
                selection = state.blurSelection,
                previewId = preview.id,
                onToggle = { actions.onToggle(ReviewTab.BLUR, it) },
                onOpen = onOpen,
                modifier = Modifier.fillMaxSize(),
            )
        }
        val marked = preview.id in state.blurSelection
        PhotoPreviewPane(
            card = preview,
            position = state.blur.indexOf(preview) + 1,
            total = state.blur.size,
            modifier = Modifier.weight(PREVIEW_WEIGHT).fillMaxHeight().padding(top = Spacing.s),
            bottomBar = { card ->
                MarkForDeletionToggle(
                    marked = marked,
                    compact = true,
                    onToggle = { actions.onSetMarked(ReviewTab.BLUR, card.id, !marked) },
                )
            },
        )
    }
}

@Composable
private fun BlurHeader(
    sensitivity: BlurSensitivity,
    itemCount: Int,
    selectedCount: Int,
    onSensitivity: (BlurSensitivity) -> Unit,
    onToggleAll: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(start = Spacing.m, end = Spacing.m, top = Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text(
                stringResource(R.string.review_sensitivity_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val options = BlurSensitivity.entries
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                options.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option == sensitivity,
                        onClick = { onSensitivity(option) },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        label = { Text(sensitivityLabel(option), maxLines = 1) },
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val caption = buildString {
                append(stringResource(R.string.review_blur_caption, BlurThresholds.threshold(sensitivity).roundToInt()))
                if (selectedCount > 0) {
                    append(" · ")
                    append(stringResource(R.string.review_selected_count, selectedCount))
                }
            }
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (itemCount > 0) {
                val allSelected = selectedCount == itemCount
                TextButton(onClick = onToggleAll) {
                    Text(stringResource(if (allSelected) R.string.review_clear_selection else R.string.review_select_all))
                }
            }
        }
    }
}

@Composable
private fun sensitivityLabel(value: BlurSensitivity): String = stringResource(
    when (value) {
        BlurSensitivity.LOW -> R.string.review_sensitivity_low
        BlurSensitivity.NORMAL -> R.string.review_sensitivity_normal
        BlurSensitivity.HIGH -> R.string.review_sensitivity_high
    },
)

@Composable
private fun BlurGrid(
    items: List<MediaCard>,
    selection: Set<Long>,
    previewId: Long?,
    onToggle: (Long) -> Unit,
    onOpen: (MediaCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(Spacing.gridCellMin),
        contentPadding = PaddingValues(start = Spacing.m, end = if (previewId != null) Spacing.zero else Spacing.m, bottom = Spacing.m),
        horizontalArrangement = Arrangement.spacedBy(Spacing.gridGap),
        verticalArrangement = Arrangement.spacedBy(Spacing.gridGap),
        modifier = modifier,
    ) {
        items(items, key = { it.id }) { card ->
            PhotoCell(
                card = card,
                selected = card.id in selection,
                previewing = card.id == previewId,
                label = card.sharpness?.let { stringResource(R.string.review_sharpness, it.roundToInt()) },
                onToggle = { onToggle(card.id) },
                onOpen = { onOpen(card) },
            )
        }
    }
}

/** 그리드 칸 1개: 선택 체크(48dp) + 라벨 + RAW 배지. [previewing]이면 강조 테두리(펼친 화면에서 지금 보는 사진). */
@Composable
internal fun PhotoCell(
    card: MediaCard,
    selected: Boolean,
    previewing: Boolean,
    label: String?,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    isVideo: Boolean = false,
) {
    val shape = MaterialTheme.shapes.small
    Box(
        if (previewing) {
            Modifier.border(Spacing.xxs, MaterialTheme.colorScheme.primary, shape).padding(Spacing.xxs)
        } else {
            Modifier
        },
    ) {
        SelectableThumbnail(
            path = card.path,
            selected = selected,
            onToggle = onToggle,
            onOpen = onOpen,
            contentDescription = stringResource(R.string.review_open_photo, card.fileName),
            isVideo = isVideo,
            label = label,
            badge = if (card.hasRaw) {
                { StatusBadge(stringResource(R.string.review_raw_badge), BadgeKind.NEUTRAL) }
            } else {
                null
            },
        )
    }
}

private const val GRID_WEIGHT = 0.42f
private const val PREVIEW_WEIGHT = 0.58f
