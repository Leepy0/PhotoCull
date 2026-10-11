package com.leepy0.photocull.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.leepy0.photocull.R
import com.leepy0.photocull.data.db.GroupKind
import com.leepy0.photocull.data.review.GroupCard
import com.leepy0.photocull.ui.components.MediaThumbnail
import com.leepy0.photocull.ui.components.StatusKind
import com.leepy0.photocull.ui.components.StatusLine
import com.leepy0.photocull.ui.theme.Spacing
import com.leepy0.photocull.util.Format

/** 연사 탭: 맨 위 일괄 정리 + 그룹 카드 목록(펼친 화면에서는 2열) */
@Composable
internal fun BurstTab(
    state: ReviewUiState,
    actions: ReviewActions,
    empty: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.groups.isEmpty()) {
        Box(modifier.fillMaxSize()) { empty() }
        return
    }
    val plan = state.burstPlan
    LazyVerticalGrid(
        columns = GridCells.Adaptive(Spacing.gridCellMin * GROUP_CARD_MIN_CELLS),
        contentPadding = PaddingValues(Spacing.m),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
        modifier = modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
            BurstHeader(
                groupCount = state.groups.size,
                photoCount = state.groups.sumOf { it.members.size },
                plan = plan,
                busy = state.busy,
                onKeepBestAll = actions.onKeepBestAll,
            )
        }
        items(state.groups, key = { it.id }) { group ->
            GroupCardItem(group = group, onClick = { actions.onOpenGroup(group.id) })
        }
    }
}

@Composable
private fun BurstHeader(
    groupCount: Int,
    photoCount: Int,
    plan: BurstPlan,
    busy: Boolean,
    onKeepBestAll: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                stringResource(R.string.review_burst_header_title, groupCount, photoCount),
                style = MaterialTheme.typography.titleMedium,
            )
            if (!plan.isEmpty) {
                Text(
                    stringResource(R.string.review_burst_header_body, plan.deleteIds.size, bytesText(plan.deleteBytes)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onKeepBestAll,
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier.widthIn(max = ActionMaxWidth).fillMaxWidth().heightIn(min = Spacing.touchTarget).padding(top = Spacing.xxs),
                ) {
                    Text(stringResource(R.string.review_burst_best_only, plan.deleteIds.size), maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun GroupCardItem(group: GroupCard, onClick: () -> Unit) {
    val title = groupTitle(group)
    val openLabel = stringResource(R.string.review_group_open, title)
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = openLabel },
    ) {
        Row(
            Modifier.padding(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Box(Modifier.size(Spacing.gridCellMin).clip(MaterialTheme.shapes.small)) {
                MediaThumbnail(path = group.best.path, contentDescription = null, modifier = Modifier.fillMaxSize())
                StatusBadge(
                    text = stringResource(R.string.review_group_best),
                    kind = BadgeKind.BEST,
                    modifier = Modifier.align(Alignment.TopStart).padding(Spacing.xxs),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${Format.monthDay(group.startAt)} ${Format.time(group.startAt)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    stringResource(R.string.review_group_meta, groupSpan(group), bytesText(group.totalBytes)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (group.allBlurry) {
                    StatusLine(StatusKind.WARNING, stringResource(R.string.review_group_all_blurry))
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun groupTitle(group: GroupCard): String = stringResource(
    if (group.kind == GroupKind.BURST) R.string.review_group_kind_burst else R.string.review_group_kind_similar,
    group.members.size,
)

/** 그룹 촬영 길이: "3초 동안" / "1초 안에" */
@Composable
internal fun groupSpan(group: GroupCard): String {
    val seconds = ((group.endAt - group.startAt) / MS_PER_SECOND).toInt()
    return if (seconds < 1) stringResource(R.string.review_group_span_short) else stringResource(R.string.review_group_span_seconds, seconds)
}

private const val GROUP_CARD_MIN_CELLS = 3
private const val MS_PER_SECOND = 1000L
