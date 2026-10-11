package com.leepy0.photocull.ui.review

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.leepy0.photocull.ui.components.PrimaryActionButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import com.leepy0.photocull.R
import com.leepy0.photocull.analysis.AnalysisStatus
import com.leepy0.photocull.ui.theme.LocalStatusColors
import com.leepy0.photocull.ui.theme.Spacing
import com.leepy0.photocull.ui.theme.ViewerColors
import com.leepy0.photocull.util.Format

/** 톤 버튼으로 된 하단 주요 행동(되돌릴 수 있고 덜 강한 행동: '남은 n장 모두 유지' 등) */
@Composable
fun TonalActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = Spacing.primaryButtonHeight),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 분석이 도는 동안: 목록이 비어 보여도 '없음'으로 오해하지 않게 진행을 알린다 */
@Composable
fun AnalysisBanner(status: AnalysisStatus, modifier: Modifier = Modifier) {
    if (!status.running) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(
                stringResource(R.string.review_analysis_running, status.done, status.total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (status.total > 0) {
                LinearProgressIndicator(
                    progress = { (status.done.toFloat() / status.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * 뷰어 하단의 큰 토글: [삭제 대상으로 표시] ↔ [삭제 대상 해제]. 현재 상태는 아이콘·글자로도 보인다.
 * 어두운 뷰어 위에서 쓰므로 뷰어 색을 쓴다.
 */
@Composable
fun MarkForDeletionToggle(
    marked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** true: 화면 안 미리보기처럼 주요 행동 옆에 놓일 때(덜 강하게, 48dp) */
    compact: Boolean = false,
) {
    val stateText = stringResource(R.string.review_viewer_marked)
    val label = stringResource(if (marked) R.string.review_viewer_unmark else R.string.review_viewer_mark)
    val icon = if (marked) Icons.Filled.CheckCircle else Icons.Outlined.Delete
    val semanticsModifier = Modifier.semantics {
        role = Role.Switch
        if (marked) stateDescription = stateText
    }
    if (compact && !marked) {
        OutlinedButton(
            onClick = onToggle,
            enabled = enabled,
            border = BorderStroke(ButtonDefaults.outlinedButtonBorder(enabled).width, ViewerColors.onScrimMuted),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = ViewerColors.onScrim),
            modifier = modifier.fillMaxWidth().heightIn(min = Spacing.touchTarget).then(semanticsModifier),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(Spacing.iconSmall + Spacing.xxs))
            Spacer(Modifier.size(Spacing.xs))
            Text(label)
        }
        return
    }
    val colors = when {
        marked && compact -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
        marked -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        )
        else -> ButtonDefaults.buttonColors(
            containerColor = ViewerColors.onScrim,
            contentColor = ViewerColors.background,
        )
    }
    Button(
        onClick = onToggle,
        enabled = enabled,
        colors = colors,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) Spacing.touchTarget else Spacing.primaryButtonHeight)
            .then(semanticsModifier),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(if (compact) Spacing.iconSmall + Spacing.xxs else Spacing.iconMedium))
        Spacer(Modifier.size(Spacing.xs))
        Text(label, style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium)
    }
}

/** 작은 상태 배지(아이콘 + 글자). 색만으로 뜻을 전하지 않는다. */
@Composable
fun StatusBadge(
    text: String,
    kind: BadgeKind,
    modifier: Modifier = Modifier,
) {
    val status = LocalStatusColors.current
    val (container, content, icon) = when (kind) {
        BadgeKind.BEST -> Triple(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, Icons.Filled.Star)
        BadgeKind.BLUR -> Triple(status.warningContainer, status.onWarningContainer, Icons.Outlined.WarningAmber)
        BadgeKind.MARKED -> Triple(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError, Icons.Filled.CheckCircle)
        BadgeKind.NEUTRAL -> Triple(ViewerColors.scrim, ViewerColors.onScrim, null)
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.extraSmall, modifier = modifier) {
        Row(
            Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(Spacing.iconSmall))
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

enum class BadgeKind { BEST, BLUR, MARKED, NEUTRAL }

/** 썸네일 구석용 아이콘 배지(좁은 칸에서 선택 체크와 겹치지 않게). 뜻은 [description]으로 읽어 준다. */
@Composable
fun CompactBadge(kind: BadgeKind, description: String, modifier: Modifier = Modifier) {
    val status = LocalStatusColors.current
    val (container, content, icon) = when (kind) {
        BadgeKind.BEST -> Triple(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, Icons.Filled.Star)
        BadgeKind.BLUR -> Triple(status.warningContainer, status.onWarningContainer, Icons.Outlined.WarningAmber)
        BadgeKind.MARKED -> Triple(MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError, Icons.Filled.CheckCircle)
        BadgeKind.NEUTRAL -> Triple(ViewerColors.scrim, ViewerColors.onScrim, Icons.Filled.Star)
    }
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = modifier.semantics { contentDescription = description },
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(Spacing.xxs).size(Spacing.iconSmall))
    }
}

/** 화면 표시용 용량. 숫자와 단위 사이에서 줄이 바뀌지 않게 */
fun bytesText(bytes: Long): String = Format.bytes(bytes).replace(' ', '\u00A0')

/** 메시지 → 화면 문구 */
fun ReviewMessage.text(context: Context): String = when (this) {
    is ReviewMessage.Trashed -> when {
        failed > 0 && unit == CountUnit.VIDEO -> context.getString(R.string.review_snack_trashed_partial_videos, moved, failed)
        failed > 0 -> context.getString(R.string.review_snack_trashed_partial_photos, moved, failed)
        unit == CountUnit.VIDEO -> context.getString(R.string.review_snack_trashed_videos, moved)
        else -> context.getString(R.string.review_snack_trashed_photos, moved)
    }
    ReviewMessage.TrashFailed -> context.getString(R.string.review_snack_trash_failed)
    is ReviewMessage.Kept ->
        if (unit == CountUnit.VIDEO) context.getString(R.string.review_snack_kept_videos, ids.size)
        else context.getString(R.string.review_snack_kept_photos, ids.size)
    ReviewMessage.ActionFailed -> context.getString(R.string.review_snack_action_failed)
    ReviewMessage.UndoFailed -> context.getString(R.string.review_snack_undo_failed)
}

/** 스낵바를 띄우고, [실행 취소]를 누르면 [onUndo] */
suspend fun SnackbarHostState.showReviewMessage(
    context: Context,
    message: ReviewMessage,
    onUndo: (ReviewMessage) -> Unit,
) {
    val result = showSnackbar(
        message = message.text(context),
        actionLabel = if (message.undoable) context.getString(R.string.action_undo) else null,
        withDismissAction = message.undoable,
        duration = if (message.undoable) SnackbarDuration.Long else SnackbarDuration.Short,
    )
    if (result == SnackbarResult.ActionPerformed) onUndo(message)
}

/** "12장 삭제 · 1.2 GB 확보" */
@Composable
fun deleteLabel(count: Int, bytes: Long, unit: CountUnit): String =
    if (unit == CountUnit.VIDEO) stringResource(R.string.review_delete_videos, count, bytesText(bytes))
    else stringResource(R.string.review_delete_photos, count, bytesText(bytes))

@Composable
fun keepRestLabel(count: Int, unit: CountUnit): String =
    if (unit == CountUnit.VIDEO) stringResource(R.string.review_keep_rest_videos, count)
    else stringResource(R.string.review_keep_rest_photos, count)

/**
 * 하단 주요 행동 줄. 좁은 화면은 버튼을 가로 꽉 채우고(필요하면 위에 안내 한 줄),
 * 넓은 화면(펼침·가로)은 왼쪽에 안내, 오른쪽(엄지 쪽)에 적당한 폭의 버튼을 둔다.
 */
@Composable
fun AdaptiveActionRow(
    hint: String?,
    showHintWhenNarrow: Boolean,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth >= WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND.dp
        if (wide) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Text(
                    hint.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Box(Modifier.widthIn(max = ActionMaxWidth)) { action() }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (hint != null && showHintWhenNarrow) {
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                action()
            }
        }
    }
}

/** 넓은 화면에서 주요 버튼의 최대 폭 */
val ActionMaxWidth = Spacing.gridCellMin * 4

/** 하단 주요 행동 한 줄 */
@Composable
fun ReviewPrimaryAction(
    selectedCount: Int,
    selectedBytes: Long,
    remainingCount: Int,
    unit: CountUnit,
    busy: Boolean,
    onDelete: () -> Unit,
    onKeepRest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selectedCount == 0 && remainingCount == 0) return
    val hint = stringResource(if (selectedCount > 0) R.string.review_hint_delete else R.string.review_hint_keep)
    AdaptiveActionRow(hint = hint, showHintWhenNarrow = false, modifier = modifier) {
        if (selectedCount > 0) {
            PrimaryActionButton(
                text = if (busy) stringResource(R.string.review_working) else deleteLabel(selectedCount, selectedBytes, unit),
                onClick = onDelete,
                enabled = !busy,
                destructive = true,
            )
        } else {
            TonalActionButton(
                text = if (busy) stringResource(R.string.review_working) else keepRestLabel(remainingCount, unit),
                onClick = onKeepRest,
                enabled = !busy,
            )
        }
    }
}
