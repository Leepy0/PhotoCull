package com.leepy0.photocull.ui.importer

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Eject
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.leepy0.photocull.R
import com.leepy0.photocull.analysis.AnalysisStatus
import com.leepy0.photocull.importer.CardScanSummary
import com.leepy0.photocull.importer.ImportError
import com.leepy0.photocull.importer.ImportFailure
import com.leepy0.photocull.importer.ImportProgress
import com.leepy0.photocull.importer.ImportResult
import com.leepy0.photocull.ui.components.ErrorState
import com.leepy0.photocull.ui.components.StatusKind
import com.leepy0.photocull.ui.components.StatusLine
import com.leepy0.photocull.ui.components.statusColor
import com.leepy0.photocull.ui.theme.Spacing
import com.leepy0.photocull.util.Format

// ---------------------------------------------------------------- 공통 틀

/** 스크롤되는 내용 영역. 넓은 화면에서는 가운데 정렬 + 최대 폭 */
@Composable
private fun ScrollingPage(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .padding(horizontal = Spacing.m, vertical = Spacing.m),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
            content = content,
        )
    }
}

/** 배경색 차이로 묶는 그룹(테두리 없음) */
@Composable
internal fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s), content = content)
    }
}

// ---------------------------------------------------------------- 준비(요약)

@Composable
internal fun ReadyContent(
    summary: CardScanSummary,
    moveMode: Boolean,
    wide: Boolean,
    onMoveModeChange: (Boolean) -> Unit,
    onOpenStorageSettings: () -> Unit,
) {
    if (wide) {
        // 펼친 화면: 왼쪽 = 카드에 무엇이 있나, 오른쪽 = 어디에 어떻게
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Row(
                modifier = Modifier.widthIn(max = ContentMaxWidth * 2).fillMaxWidth().padding(Spacing.l),
                horizontalArrangement = Arrangement.spacedBy(Spacing.l),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
                    CardOverview(summary, moveMode)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
                    DestinationAndSpace(summary, onOpenStorageSettings)
                    MoveModeChoice(moveMode, onMoveModeChange)
                }
            }
        }
    } else {
        ScrollingPage {
            CardOverview(summary, moveMode)
            DestinationAndSpace(summary, onOpenStorageSettings)
            MoveModeChoice(moveMode, onMoveModeChange)
        }
    }
}

@Composable
private fun CardOverview(summary: CardScanSummary, moveMode: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Outlined.SdCard, BadgeTone.ACCENT)
            Column(Modifier.weight(1f)) {
                Text(summary.volume.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val from = summary.oldestCapturedAt
                val to = summary.newestCapturedAt
                if (from != null && to != null) {
                    Text(
                        stringResource(R.string.import_ready_captured, Format.dateRange(from, to)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Column {
            Text(
                stringResource(R.string.import_ready_new_files, countText(summary.newFileCount)),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(sizeText(summary.newBytes), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                stringResource(R.string.import_ready_card_total, sizeText(summary.totalBytes)),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                StatTile(stringResource(R.string.import_ready_photos), summary.photoCount, Modifier.weight(1f))
                StatTile(stringResource(R.string.import_ready_raw), summary.rawCount, Modifier.weight(1f))
                StatTile(stringResource(R.string.import_ready_videos), summary.videoCount, Modifier.weight(1f))
            }
        }
        if (summary.alreadyImportedCount > 0) {
            StatusLine(
                StatusKind.INFO,
                stringResource(
                    if (moveMode) R.string.import_ready_skip_move else R.string.import_ready_skip_copy,
                    countText(summary.alreadyImportedCount),
                ),
            )
        }
    }
}

@Composable
private fun StatTile(label: String, value: Int, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Column(Modifier.padding(horizontal = Spacing.s, vertical = Spacing.s)) {
            Text(countText(value), style = MaterialTheme.typography.titleLarge, maxLines = 1)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun DestinationAndSpace(summary: CardScanSummary, onOpenStorageSettings: () -> Unit) {
    Group {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(R.string.import_ready_dest_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(summary.destDisplayPath, style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(Spacing.xxs))
        Text(stringResource(R.string.import_ready_storage_label), style = MaterialTheme.typography.titleSmall)
        val enough = summary.enoughSpace
        val fraction = if (summary.freeBytes <= 0) 1f else (summary.newBytes.toDouble() / summary.freeBytes).toFloat()
        val meterText = stringResource(R.string.import_ready_storage_text, sizeText(summary.newBytes), sizeText(summary.freeBytes))
        ThickProgressBar(fraction = fraction, isError = !enough, description = meterText)
        Text(meterText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!enough) {
            val margin = (summary.requiredBytes - summary.newBytes).coerceAtLeast(0)
            StatusLine(StatusKind.ERROR, stringResource(R.string.import_ready_storage_short, sizeText(summary.shortageBytes), sizeText(margin)))
            Text(
                stringResource(R.string.import_ready_storage_fix),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenStorageSettings, modifier = Modifier.heightIn(min = Spacing.touchTarget)) {
                Text(stringResource(R.string.import_storage_settings))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveModeChoice(moveMode: Boolean, onChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(stringResource(R.string.import_mode_label), style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val options = listOf(true to R.string.import_mode_move, false to R.string.import_mode_copy)
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = moveMode == value,
                    onClick = { onChange(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    modifier = Modifier.heightIn(min = Spacing.touchTarget),
                ) { Text(stringResource(label), maxLines = 1) }
            }
        }
        Text(
            stringResource(if (moveMode) R.string.import_mode_move_desc else R.string.import_mode_copy_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------- 진행

@Composable
internal fun RunningContent(progress: ImportProgress, analysis: AnalysisStatus, wide: Boolean) {
    ScrollingPage {
        val preparing = progress.isPreparing
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = if (wide) Spacing.l else Spacing.m),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                stringResource(if (preparing) R.string.import_preparing_title else R.string.import_running_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() },
            )
            if (!preparing) {
                Text(
                    stringResource(R.string.import_running_percent, progress.percent),
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            ThickProgressBar(
                fraction = if (preparing) null else progress.fraction,
                description = stringResource(R.string.import_progress_desc, progress.percent),
            )
            if (preparing) {
                Text(
                    stringResource(R.string.import_preparing_body, countText(progress.totalFiles), sizeText(progress.totalBytes)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.import_running_files, countText(progress.doneFiles), countText(progress.totalFiles)),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(R.string.import_running_bytes, sizeText(progress.doneBytes), sizeText(progress.totalBytes)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (!preparing) {
            Group {
                val measuring = stringResource(R.string.import_running_measuring)
                MetricRow(
                    stringResource(R.string.import_running_speed),
                    if (progress.bytesPerSecond > 0) Format.speed(progress.bytesPerSecond).replace(' ', ' ') else measuring,
                )
                MetricRow(
                    stringResource(R.string.import_running_eta),
                    progress.etaSeconds?.let { Format.eta(it) } ?: measuring,
                )
                progress.currentFileName?.let { name ->
                    MetricRow(stringResource(R.string.import_running_file), name, middleEllipsis = true)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            if (progress.failedCount > 0) {
                StatusLine(StatusKind.WARNING, stringResource(R.string.import_running_failed, countText(progress.failedCount)))
            }
            StatusLine(StatusKind.INFO, stringResource(R.string.import_running_keep_going))
        }
        AnalysisHint(analysis)
    }
}

// ---------------------------------------------------------------- 결과

@Composable
internal fun FinishedContent(result: ImportResult, analysis: AnalysisStatus, wide: Boolean, onOpenStorageSettings: () -> Unit) {
    ScrollingPage {
        FinishedHeader(result, wide)
        Group {
            if (result.savedCount > 0) {
                StatusLine(StatusKind.SUCCESS, stringResource(R.string.import_done_saved_row, countText(result.savedCount), sizeText(result.savedBytes)))
            } else {
                StatusLine(StatusKind.INFO, stringResource(R.string.import_done_nothing_saved))
            }
            if (result.duplicateCount > 0) {
                StatusLine(
                    StatusKind.INFO,
                    stringResource(
                        if (result.moveMode) R.string.import_done_duplicates_move else R.string.import_done_duplicates_copy,
                        countText(result.duplicateCount),
                    ),
                )
            }
            if (result.cancelled) {
                StatusLine(StatusKind.INFO, stringResource(R.string.import_done_cancelled_rest))
            }
            if (result.failedCount > 0) {
                FailureGroup(
                    kind = StatusKind.ERROR,
                    title = stringResource(R.string.import_done_failed, countText(result.failedCount)),
                    hint = stringResource(R.string.import_done_failed_hint),
                    failures = result.copyFailures,
                )
            }
            if (result.sourceDeleteFailedCount > 0) {
                FailureGroup(
                    kind = StatusKind.WARNING,
                    title = stringResource(R.string.import_done_delete_failed, countText(result.sourceDeleteFailedCount)),
                    hint = stringResource(R.string.import_done_delete_failed_hint),
                    failures = result.deleteFailures,
                )
            }
        }
        EjectGuide(onOpenStorageSettings)
        AnalysisHint(analysis)
    }
}

@Composable
private fun FinishedHeader(result: ImportResult, wide: Boolean) {
    val (icon, tone, title) = when {
        result.cancelled -> Triple(Icons.Outlined.Info, BadgeTone.NEUTRAL, R.string.import_done_title_cancelled)
        result.hasProblems -> Triple(Icons.Outlined.WarningAmber, BadgeTone.WARNING, R.string.import_done_title_problems)
        else -> Triple(Icons.Outlined.CheckCircle, BadgeTone.SUCCESS, R.string.import_done_title)
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = if (wide) Spacing.m else Spacing.zero),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        IconBadge(icon, tone, size = Spacing.primaryButtonHeight)
        Spacer(Modifier.height(Spacing.xxs))
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        val saved = when {
            result.savedCount <= 0 -> stringResource(R.string.import_done_nothing_saved)
            result.moveMode -> stringResource(R.string.import_done_moved, countText(result.savedCount), sizeText(result.savedBytes))
            else -> stringResource(R.string.import_done_copied, countText(result.savedCount), sizeText(result.savedBytes))
        }
        Text(saved, style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.import_done_meta, result.destDisplayPath, durationText(result.durationMs)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 실패 n개 + 펼치면 파일별 이유 */
@Composable
private fun FailureGroup(kind: StatusKind, title: String, hint: String, failures: List<ImportFailure>) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        StatusLine(kind, title)
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = StatusTextIndent),
        )
        if (failures.isNotEmpty()) {
            // 글자를 위 문단 시작선에 맞춘다(텍스트 버튼 안쪽 여백만큼 당김)
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
                modifier = Modifier.padding(start = StatusTextIndent).offset(x = -Spacing.s).heightIn(min = Spacing.touchTarget),
            ) {
                Text(stringResource(if (expanded) R.string.import_done_hide_files else R.string.import_done_show_files))
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(Spacing.iconMedium),
                )
            }
        }
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().padding(start = StatusTextIndent, bottom = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                failures.take(MAX_LISTED_FAILURES).forEach { f -> FailureItem(f, kind) }
                if (failures.size > MAX_LISTED_FAILURES) {
                    Text(
                        stringResource(R.string.import_done_more_files, countText(failures.size - MAX_LISTED_FAILURES)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FailureItem(failure: ImportFailure, kind: StatusKind) {
    Column {
        Text(failure.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        Text(failureReasonText(failure.reason), style = MaterialTheme.typography.bodySmall, color = statusColor(kind))
    }
}

private const val MAX_LISTED_FAILURES = 50

/** StatusLine의 글자 시작선(아이콘 + 간격)에 맞춘 들여쓰기 */
private val StatusTextIndent = Spacing.iconSmall + Spacing.xxs + Spacing.xs

@Composable
private fun EjectGuide(onOpenStorageSettings: () -> Unit) {
    Group {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Icon(Icons.Outlined.Eject, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(stringResource(R.string.import_eject), style = MaterialTheme.typography.bodyMedium)
                // 버튼 글자를 위 문단과 같은 줄에 맞춘다(텍스트 버튼 안쪽 여백만큼 당김)
                TextButton(
                    onClick = onOpenStorageSettings,
                    contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
                    modifier = Modifier.offset(x = -Spacing.s).heightIn(min = Spacing.touchTarget),
                ) { Text(stringResource(R.string.import_storage_settings)) }
            }
        }
    }
}

// ---------------------------------------------------------------- 오류

@Composable
internal fun FailedContent(error: ImportError) {
    val texts = importErrorTexts(error)
    ErrorState(title = texts.title, cause = texts.cause, fix = texts.fix)
}
