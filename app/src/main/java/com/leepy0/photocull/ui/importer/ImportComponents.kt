package com.leepy0.photocull.ui.importer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.text.style.TextOverflow
import com.leepy0.photocull.R
import com.leepy0.photocull.analysis.AnalysisStatus
import com.leepy0.photocull.data.review.ReviewCounts
import com.leepy0.photocull.importer.FailureReason
import com.leepy0.photocull.importer.ImportError
import com.leepy0.photocull.ui.theme.LocalStatusColors
import com.leepy0.photocull.ui.theme.Spacing
import com.leepy0.photocull.util.Format

/**
 * 한국어를 글자 단위가 아니라 어절(띄어쓰기) 단위로 줄바꿈한다(Android 13+, 한국어 로캘).
 * "확인하/세요"처럼 낱말 중간에서 끊기지 않게 화면 전체 글자 스타일에 적용한다.
 */
@Composable
fun KoreanLineBreaks(content: @Composable () -> Unit) {
    val base = MaterialTheme.typography
    val typography = remember(base) { base.withWordBreaks() }
    MaterialTheme(colorScheme = MaterialTheme.colorScheme, shapes = MaterialTheme.shapes, typography = typography, content = content)
}

private fun Typography.withWordBreaks(): Typography {
    val lineBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)
    fun TextStyle.words() = copy(lineBreak = lineBreak)
    return copy(
        displayLarge = displayLarge.words(),
        displayMedium = displayMedium.words(),
        displaySmall = displaySmall.words(),
        headlineLarge = headlineLarge.words(),
        headlineMedium = headlineMedium.words(),
        headlineSmall = headlineSmall.words(),
        titleLarge = titleLarge.words(),
        titleMedium = titleMedium.words(),
        titleSmall = titleSmall.words(),
        bodyLarge = bodyLarge.words(),
        bodyMedium = bodyMedium.words(),
        bodySmall = bodySmall.words(),
        labelLarge = labelLarge.words(),
        labelMedium = labelMedium.words(),
        labelSmall = labelSmall.words(),
    )
}

/** 아이콘 배지의 뜻. 색은 늘 아이콘·글자와 함께 쓴다. */
enum class BadgeTone { NEUTRAL, ACCENT, SUCCESS, WARNING, ERROR }

/** 원형 배경 위 아이콘(상태 카드·결과 머리글) */
@Composable
fun IconBadge(icon: ImageVector, tone: BadgeTone, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = Spacing.touchTarget) {
    val (container, content) = toneColors(tone)
    Surface(color = container, contentColor = content, shape = CircleShape, modifier = modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(Spacing.iconMedium))
        }
    }
}

@Composable
fun toneColors(tone: BadgeTone): Pair<Color, Color> {
    val status = LocalStatusColors.current
    val scheme = MaterialTheme.colorScheme
    return when (tone) {
        BadgeTone.NEUTRAL -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        BadgeTone.ACCENT -> scheme.primaryContainer to scheme.onPrimaryContainer
        BadgeTone.SUCCESS -> status.successContainer to status.onSuccessContainer
        BadgeTone.WARNING -> status.warningContainer to status.onWarningContainer
        BadgeTone.ERROR -> scheme.errorContainer to scheme.onErrorContainer
    }
}

/** 굵은 진행 막대(8dp). [fraction]이 null이면 끝없이 도는 막대 */
@Composable
fun ThickProgressBar(fraction: Float?, modifier: Modifier = Modifier, isError: Boolean = false, description: String? = null) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val barModifier = modifier
        .fillMaxWidth()
        .height(Spacing.xs)
        .then(
            if (description != null) {
                Modifier.clearAndSetSemantics {
                    contentDescription = description
                    if (fraction != null) progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                }
            } else {
                Modifier
            },
        )
    if (fraction == null) {
        LinearProgressIndicator(modifier = barModifier, color = color, trackColor = track, strokeCap = StrokeCap.Round)
    } else {
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = barModifier,
            color = color,
            trackColor = track,
            strokeCap = StrokeCap.Round,
        )
    }
}

/** 왼쪽 라벨(보조) · 오른쪽 값(주요) 한 줄 */
@Composable
fun MetricRow(label: String, value: String, modifier: Modifier = Modifier, valueMaxLines: Int = 1, middleEllipsis: Boolean = false) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = valueMaxLines,
            overflow = if (middleEllipsis) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 흐린 사진·연사 분석이 도는 동안 보조 표시 */
@Composable
fun AnalysisHint(status: AnalysisStatus, modifier: Modifier = Modifier) {
    if (!status.running) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            if (status.total > 0) stringResource(R.string.import_analysis, status.done, status.total)
            else stringResource(R.string.import_analysis_waiting),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { if (status.total > 0) (status.done.toFloat() / status.total).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 화면 표시용 용량. 숫자와 단위 사이에서 줄이 바뀌지 않게 */
fun sizeText(bytes: Long): String = Format.bytes(bytes).replace(' ', ' ')

fun countText(n: Int): String = Format.count(n)

@Composable
fun durationText(ms: Long): String {
    val p = durationParts(ms)
    return when {
        p.hours > 0 -> stringResource(R.string.import_duration_hours, p.hours.toInt(), p.minutes.toInt())
        p.minutes > 0 -> stringResource(R.string.import_duration_minutes, p.minutes.toInt(), p.seconds.toInt())
        else -> stringResource(R.string.import_duration_seconds, p.seconds.toInt())
    }
}

@Composable
fun failureReasonText(reason: FailureReason): String = stringResource(
    when (reason) {
        FailureReason.VERIFY_FAILED -> R.string.import_reason_verify
        FailureReason.READ_ERROR -> R.string.import_reason_read
        FailureReason.WRITE_ERROR -> R.string.import_reason_write
        FailureReason.SOURCE_DELETE_FAILED -> R.string.import_reason_delete
    },
)

/** 오류: 무엇이 · 왜 · 어떻게 해결 */
data class ErrorTexts(val title: String, val cause: String, val fix: String)

@Composable
fun importErrorTexts(error: ImportError): ErrorTexts = when (error) {
    is ImportError.CardRemoved -> ErrorTexts(
        stringResource(R.string.import_error_card_removed_title),
        stringResource(R.string.import_error_card_removed_cause, countText(error.doneFiles), countText(error.totalFiles)),
        stringResource(R.string.import_error_card_removed_fix),
    )
    ImportError.NoCard -> ErrorTexts(
        stringResource(R.string.import_error_no_card_title),
        stringResource(R.string.import_error_no_card_cause),
        stringResource(R.string.import_error_no_card_fix),
    )
    ImportError.NoPermission -> ErrorTexts(
        stringResource(R.string.import_error_permission_title),
        stringResource(R.string.import_error_permission_cause),
        stringResource(R.string.import_error_permission_fix),
    )
    is ImportError.InsufficientSpace -> ErrorTexts(
        stringResource(R.string.import_error_space_title),
        stringResource(
            R.string.import_error_space_cause,
            sizeText((error.requiredBytes - error.freeBytes).coerceAtLeast(0)),
            sizeText(error.requiredBytes),
            sizeText(error.freeBytes),
        ),
        stringResource(R.string.import_error_space_fix),
    )
    // 예외 원문(message)은 보여주지 않는다
    is ImportError.Unexpected -> ErrorTexts(
        stringResource(R.string.import_error_unexpected_title),
        stringResource(R.string.import_error_unexpected_cause),
        stringResource(R.string.import_error_unexpected_fix),
    )
}

/** "흐림 12 · 연사 8그룹" (영상만 있으면 "영상 3"). 분석 중인데 아직 없으면 "분석 중". 아무것도 없으면 null */
@Composable
fun reviewPartsText(counts: ReviewCounts?, analysis: AnalysisStatus): String? {
    val parts = buildList {
        if (counts != null) {
            if (counts.blur > 0) add(stringResource(R.string.import_review_part_blur, counts.blur))
            if (counts.groups > 0) add(stringResource(R.string.import_review_part_burst, counts.groups))
            if (isEmpty() && counts.videos > 0) add(stringResource(R.string.import_review_part_video, counts.videos))
        }
        if (isEmpty() && analysis.running) add(stringResource(R.string.import_review_part_analyzing))
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
