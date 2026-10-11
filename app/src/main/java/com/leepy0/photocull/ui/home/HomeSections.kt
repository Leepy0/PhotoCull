package com.leepy0.photocull.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.BurstMode
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.SdCardAlert
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.leepy0.photocull.R
import com.leepy0.photocull.analysis.AnalysisStatus
import com.leepy0.photocull.data.db.SessionState
import com.leepy0.photocull.data.review.ReviewCounts
import com.leepy0.photocull.importer.CardScanSummary
import com.leepy0.photocull.importer.ImportProgress
import com.leepy0.photocull.importer.ImportResult
import com.leepy0.photocull.ui.components.StatusKind
import com.leepy0.photocull.ui.components.StatusLine
import com.leepy0.photocull.ui.components.UiState
import com.leepy0.photocull.ui.importer.BadgeTone
import com.leepy0.photocull.ui.importer.IconBadge
import com.leepy0.photocull.ui.importer.ThickProgressBar
import com.leepy0.photocull.ui.importer.countText
import com.leepy0.photocull.ui.importer.hasProblems
import com.leepy0.photocull.ui.importer.importErrorTexts
import com.leepy0.photocull.ui.importer.isPreparing
import com.leepy0.photocull.ui.importer.percent
import com.leepy0.photocull.ui.importer.sizeText
import com.leepy0.photocull.ui.nav.ReviewTab
import com.leepy0.photocull.ui.theme.Spacing
import com.leepy0.photocull.util.Format

// ---------------------------------------------------------------- 카드 상태 카드

/** 홈 맨 위, 가장 큰 카드. 상태마다 아이콘 + 제목 + 설명(색만으로 구분하지 않음) */
@Composable
internal fun CardStatusCard(status: HomeCardStatus, actions: HomeActions, modifier: Modifier = Modifier) {
    val openLabel = stringResource(R.string.home_card_open_import_desc)
    // 요약·진행은 눌러서 가져오기 화면으로(하단 주요 행동과 같은 곳, 보조 경로)
    val tappable = status is HomeCardStatus.CardReady || status is HomeCardStatus.Importing || status is HomeCardStatus.Checking
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (tappable) Modifier.clickable(onClickLabel = openLabel, role = Role.Button, onClick = actions.onOpenImport)
                else Modifier,
            ),
    ) {
        Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            when (status) {
                HomeCardStatus.NoPermission -> {
                    CardHeader(Icons.Outlined.Lock, BadgeTone.WARNING, stringResource(R.string.home_card_no_permission_title))
                    BodyText(stringResource(R.string.home_card_no_permission_body))
                }
                is HomeCardStatus.NeedsAccess -> {
                    CardHeader(
                        Icons.Outlined.FolderOpen,
                        BadgeTone.WARNING,
                        stringResource(R.string.home_card_needs_access_title),
                        overline = status.label,
                    )
                    BodyText(stringResource(R.string.home_card_needs_access_body))
                }
                is HomeCardStatus.NotConnected -> {
                    CardHeader(Icons.Outlined.Usb, BadgeTone.NEUTRAL, stringResource(R.string.home_card_not_connected_title))
                    BodyText(stringResource(R.string.home_card_not_connected_body))
                    status.interrupted?.let { stopped ->
                        StatusLine(
                            StatusKind.WARNING,
                            stringResource(R.string.home_card_interrupted, countText(stopped.doneFiles), countText(stopped.totalFiles)),
                        )
                    }
                }
                is HomeCardStatus.NotCameraCard -> {
                    CardHeader(
                        Icons.Outlined.SdCardAlert,
                        BadgeTone.NEUTRAL,
                        stringResource(R.string.home_card_not_camera_title),
                        overline = status.label,
                    )
                    BodyText(stringResource(R.string.home_card_not_camera_body))
                }
                is HomeCardStatus.Checking -> {
                    CardHeader(
                        Icons.Outlined.SdCard,
                        BadgeTone.ACCENT,
                        status.label,
                        overline = stringResource(R.string.home_card_connected),
                        chevron = true,
                    )
                    BodyText(stringResource(R.string.home_card_checking))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is HomeCardStatus.CardReady -> CardSummary(status.summary)
                is HomeCardStatus.Importing -> Importing(status.progress, status.label)
                is HomeCardStatus.Finished -> Finished(status.result, actions)
                is HomeCardStatus.Failed -> {
                    val texts = importErrorTexts(status.error)
                    CardHeader(Icons.Outlined.ErrorOutline, BadgeTone.ERROR, texts.title, overline = status.label)
                    BodyText(texts.cause)
                    BodyText(texts.fix)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = actions.onRescan, modifier = Modifier.heightIn(min = Spacing.touchTarget)) {
                            Text(stringResource(R.string.home_card_check_again))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardHeader(
    icon: ImageVector,
    tone: BadgeTone,
    title: String,
    overline: String? = null,
    chevron: Boolean = false,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, tone)
        Column(Modifier.weight(1f)) {
            if (overline != null) {
                Text(
                    overline,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        }
        if (chevron) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BodyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ColumnScope.CardSummary(summary: CardScanSummary) {
    CardHeader(
        Icons.Outlined.SdCard,
        BadgeTone.ACCENT,
        summary.volume.label,
        overline = stringResource(R.string.home_card_connected),
        chevron = true,
    )
    if (summary.newFileCount > 0) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                stringResource(R.string.home_card_new_files, countText(summary.newFileCount)),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.alignByBaseline(),
            )
            Text(
                sizeText(summary.newBytes),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.alignByBaseline(),
            )
        }
    } else {
        Text(stringResource(R.string.home_card_nothing_new), style = MaterialTheme.typography.titleMedium)
    }
    BodyText(
        stringResource(
            R.string.home_card_stats,
            countText(summary.photoCount),
            countText(summary.rawCount),
            countText(summary.videoCount),
            sizeText(summary.totalBytes),
        ),
    )
    if (summary.newFileCount > 0 && summary.alreadyImportedCount > 0) {
        StatusLine(StatusKind.INFO, stringResource(R.string.home_card_already, countText(summary.alreadyImportedCount)))
    }
    if (summary.newFileCount > 0 && !summary.enoughSpace) {
        StatusLine(StatusKind.WARNING, stringResource(R.string.home_card_low_space, sizeText(summary.shortageBytes)))
    }
}

@Composable
private fun ColumnScope.Importing(progress: ImportProgress, label: String?) {
    val preparing = progress.isPreparing
    CardHeader(
        Icons.Outlined.Downloading,
        BadgeTone.ACCENT,
        stringResource(if (preparing) R.string.home_card_preparing else R.string.home_card_importing),
        overline = label,
        chevron = true,
    )
    if (preparing) {
        ThickProgressBar(fraction = null)
        BodyText(stringResource(R.string.home_card_preparing_body, countText(progress.totalFiles), sizeText(progress.totalBytes)))
    } else {
        Text(
            stringResource(R.string.import_running_percent, progress.percent),
            style = MaterialTheme.typography.displaySmall,
        )
        ThickProgressBar(fraction = progress.fraction, description = stringResource(R.string.import_progress_desc, progress.percent))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.home_card_importing_files, countText(progress.doneFiles), countText(progress.totalFiles)),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            progress.etaSeconds?.let { eta ->
                Text(
                    stringResource(R.string.home_card_importing_eta, Format.eta(eta)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.Finished(result: ImportResult, actions: HomeActions) {
    val (icon, tone, title) = when {
        result.cancelled -> Triple(Icons.Outlined.Info, BadgeTone.NEUTRAL, R.string.home_card_finished_cancelled)
        result.hasProblems -> Triple(Icons.Outlined.WarningAmber, BadgeTone.WARNING, R.string.import_done_title_problems)
        else -> Triple(Icons.Outlined.CheckCircle, BadgeTone.SUCCESS, R.string.home_card_finished)
    }
    CardHeader(icon, tone, stringResource(title))
    Text(
        when {
            result.savedCount <= 0 -> stringResource(R.string.home_card_finished_nothing)
            result.moveMode -> stringResource(R.string.home_card_finished_moved, countText(result.savedCount), sizeText(result.savedBytes))
            else -> stringResource(R.string.home_card_finished_copied, countText(result.savedCount), sizeText(result.savedBytes))
        },
        style = MaterialTheme.typography.titleMedium,
    )
    if (result.failedCount > 0) {
        StatusLine(StatusKind.ERROR, stringResource(R.string.home_card_finished_failed, countText(result.failedCount)))
    }
    if (result.sourceDeleteFailedCount > 0) {
        StatusLine(StatusKind.WARNING, stringResource(R.string.home_card_finished_delete_failed, countText(result.sourceDeleteFailedCount)))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.End)) {
        TextButton(onClick = actions.onDismissResult, modifier = Modifier.heightIn(min = Spacing.touchTarget)) {
            Text(stringResource(R.string.home_card_dismiss))
        }
        TextButton(onClick = actions.onOpenImport, modifier = Modifier.heightIn(min = Spacing.touchTarget)) {
            Text(stringResource(R.string.home_card_view_result))
        }
    }
}

// ---------------------------------------------------------------- 검토 대기

@Composable
internal fun ReviewSection(state: HomeUiState, actions: HomeActions) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        SectionHeader(stringResource(R.string.home_review_title))
        when (val review = state.review) {
            UiState.Loading -> SectionSurface {
                Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    BodyText(stringResource(R.string.state_loading))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            is UiState.Error, is UiState.Empty -> SectionSurface {
                Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    StatusLine(StatusKind.ERROR, stringResource(R.string.home_review_error))
                    TextButton(onClick = actions.onRetryReview, modifier = Modifier.heightIn(min = Spacing.touchTarget)) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }
            is UiState.Content -> ReviewRows(review.data, state.analysis, state.lastImport != null || !state.lastImportLoaded, actions)
        }
    }
}

@Composable
private fun ReviewRows(counts: ReviewCounts, analysis: AnalysisStatus, hasHistory: Boolean, actions: HomeActions) {
    if (counts.total == 0 && !analysis.running) {
        // 비어 있음: 이유 + 다음 행동(카드 연결은 위 카드 상태가 안내한다)
        SectionSurface {
            Row(
                Modifier.padding(Spacing.m),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (hasHistory) Icons.Outlined.TaskAlt else Icons.Outlined.BlurOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.home_review_empty_title), style = MaterialTheme.typography.bodyLarge)
                    BodyText(stringResource(if (hasHistory) R.string.home_review_done_body else R.string.home_review_empty_body))
                }
            }
        }
        return
    }
    SectionSurface {
        Column {
            if (analysis.running) AnalysisRow(analysis)
            ReviewRow(
                icon = Icons.Outlined.BlurOn,
                label = stringResource(R.string.home_review_blur),
                value = if (counts.blur > 0) stringResource(R.string.home_review_blur_count, counts.blur) else null,
                onClick = { actions.onOpenReview(ReviewTab.BLUR) },
            )
            ReviewRow(
                icon = Icons.Outlined.BurstMode,
                label = stringResource(R.string.home_review_burst),
                value = if (counts.groups > 0) stringResource(R.string.home_review_burst_count, counts.groups, counts.groupPhotos) else null,
                onClick = { actions.onOpenReview(ReviewTab.BURST) },
            )
            ReviewRow(
                icon = Icons.Outlined.Videocam,
                label = stringResource(R.string.home_review_video),
                value = if (counts.videos > 0) stringResource(R.string.home_review_video_count, counts.videos) else null,
                supporting = if (counts.shortVideos > 0) stringResource(R.string.home_review_video_short, counts.shortVideos) else null,
                onClick = { actions.onOpenReview(ReviewTab.VIDEO) },
            )
        }
    }
}

@Composable
private fun AnalysisRow(status: AnalysisStatus) {
    Column(
        Modifier.fillMaxWidth().padding(start = Spacing.m, end = Spacing.m, top = Spacing.s, bottom = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (status.total > 0) stringResource(R.string.home_analysis_running, status.done, status.total)
                else stringResource(R.string.home_analysis_waiting),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.home_analysis_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { if (status.total > 0) (status.done.toFloat() / status.total).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 검토 대기 한 줄. 값이 없으면(0건) "없음"으로 보이고 누를 수 없다. */
@Composable
private fun ReviewRow(
    icon: ImageVector,
    label: String,
    value: String?,
    onClick: () -> Unit,
    supporting: String? = null,
) {
    val enabled = value != null
    val openLabel = stringResource(R.string.home_review_open, label, value.orEmpty())
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.primaryButtonHeight)
            .then(if (enabled) Modifier.clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.m, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Icon(icon, contentDescription = null, tint = if (enabled) MaterialTheme.colorScheme.onSurface else muted)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else muted)
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
        Text(
            value ?: stringResource(R.string.home_review_none),
            style = if (enabled) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else muted,
        )
        if (enabled) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = muted, modifier = Modifier.size(Spacing.iconMedium))
        } else {
            // 누를 수 없는 줄: 화살표 없이 자리만 맞춘다
            Spacer(Modifier.size(Spacing.iconMedium))
        }
    }
}

// ---------------------------------------------------------------- 마지막 가져오기

@Composable
internal fun LastImportSection(last: LastImport?) {
    if (last == null) return
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        SectionHeader(stringResource(R.string.home_last_title))
        SectionSurface {
            Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        val day = Format.relativeDay(last.finishedAt)
                        Text(
                            stringResource(
                                if (last.moveMode) R.string.home_last_moved else R.string.home_last_copied,
                                day,
                                countText(last.savedCount),
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        val detail = buildList {
                            add(last.volumeLabel)
                            if (last.duplicateCount > 0) add(stringResource(R.string.home_last_skipped, countText(last.duplicateCount)))
                        }.joinToString(" · ")
                        BodyText(detail)
                    }
                }
                when (last.state) {
                    SessionState.CANCELLED -> StatusLine(StatusKind.INFO, stringResource(R.string.home_last_cancelled))
                    SessionState.INTERRUPTED -> StatusLine(StatusKind.WARNING, stringResource(R.string.home_last_interrupted))
                    else -> Unit
                }
                if (last.failedCount > 0) {
                    StatusLine(StatusKind.ERROR, stringResource(R.string.home_last_failed, countText(last.failedCount)))
                }
                if (last.sourceDeleteFailedCount > 0) {
                    StatusLine(StatusKind.WARNING, stringResource(R.string.home_last_delete_failed, countText(last.sourceDeleteFailedCount)))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 공통

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = Spacing.xxs).semantics { heading() },
    )
}

@Composable
private fun SectionSurface(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
        content = content,
    )
}
