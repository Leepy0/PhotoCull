package com.leepy0.photocull.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.leepy0.photocull.R
import com.leepy0.photocull.data.review.MediaCard
import com.leepy0.photocull.ui.nav.ReviewTab
import com.leepy0.photocull.ui.theme.Spacing
import com.leepy0.photocull.util.Format

/** 영상 탭: '실수 촬영 후보(짧은 영상)' 먼저, 그 아래 나머지(용량 큰 순). 섹션마다 모두 선택/해제. */
@Composable
internal fun VideoTab(
    state: ReviewUiState,
    actions: ReviewActions,
    onOpen: (MediaCard) -> Unit,
    empty: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.videos.isEmpty()) {
        Box(modifier.fillMaxSize()) { empty() }
        return
    }
    val short = state.shortVideos
    val others = state.otherVideos
    val shortLimit = stringResource(R.string.review_video_seconds, (state.shortVideoMs / MS_PER_SECOND).toInt())
    LazyVerticalGrid(
        columns = GridCells.Adaptive(Spacing.gridCellMin),
        contentPadding = PaddingValues(start = Spacing.m, end = Spacing.m, bottom = Spacing.m),
        horizontalArrangement = Arrangement.spacedBy(Spacing.gridGap),
        verticalArrangement = Arrangement.spacedBy(Spacing.gridGap),
        modifier = modifier.fillMaxSize(),
    ) {
        if (short.isNotEmpty()) {
            videoSection(
                key = "short",
                title = { stringResource(R.string.review_video_short_section, shortLimit) },
                items = short,
                selection = state.videoSelection,
                actions = actions,
                onOpen = onOpen,
            )
        }
        if (others.isNotEmpty()) {
            videoSection(
                key = "others",
                title = { stringResource(R.string.review_video_rest_section) },
                items = others,
                selection = state.videoSelection,
                actions = actions,
                onOpen = onOpen,
            )
        }
    }
}

private fun LazyGridScope.videoSection(
    key: String,
    title: @Composable () -> String,
    items: List<MediaCard>,
    selection: Set<Long>,
    actions: ReviewActions,
    onOpen: (MediaCard) -> Unit,
) {
    item(key = "header-$key", span = { GridItemSpan(maxLineSpan) }) {
        val ids = items.map { it.id }
        val allSelected = ids.all { it in selection }
        SectionHeader(
            title = title(),
            summary = stringResource(R.string.review_video_section_summary, items.size, bytesText(items.sumOf { it.bytes })),
            toggleLabel = stringResource(if (allSelected) R.string.review_clear_selection else R.string.review_select_all),
            onToggle = { actions.onToggleIds(ReviewTab.VIDEO, ids) },
        )
    }
    items(items, key = { it.id }) { card ->
        PhotoCell(
            card = card,
            selected = card.id in selection,
            previewing = false,
            isVideo = true,
            label = videoLabel(card),
            onToggle = { actions.onToggle(ReviewTab.VIDEO, card.id) },
            onOpen = { onOpen(card) },
        )
    }
}

@Composable
private fun SectionHeader(title: String, summary: String, toggleLabel: String, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onToggle) { Text(toggleLabel) }
    }
}

@Composable
private fun videoLabel(card: MediaCard): String {
    val size = bytesText(card.bytes)
    val duration = card.durationMs ?: return size
    return stringResource(R.string.review_video_label, Format.duration(duration), size)
}

private const val MS_PER_SECOND = 1000L
