package com.leepy0.photocull.ui.trash

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.leepy0.photocull.R
import com.leepy0.photocull.data.db.MediaType
import com.leepy0.photocull.data.review.MediaCard
import com.leepy0.photocull.ui.components.MediaThumbnail
import com.leepy0.photocull.ui.review.BadgeKind
import com.leepy0.photocull.ui.review.StatusBadge
import com.leepy0.photocull.ui.review.bytesText
import com.leepy0.photocull.ui.settings.phraseBreak
import com.leepy0.photocull.ui.theme.LocalStatusColors
import com.leepy0.photocull.ui.theme.Spacing
import com.leepy0.photocull.ui.theme.ViewerColors

/**
 * 휴지통 그리드 칸. 사진을 누르면 [onOpen](크게 보기), 오른쪽 위 체크 영역(48dp)을 누르면 [onToggle].
 * 휴지통의 선택은 '복원할 것'이 기본이라 삭제를 뜻하는 오류 색 대신 강조 색 테두리 + 체크 아이콘으로 표시한다.
 */
@Composable
internal fun TrashCell(
    card: MediaCard,
    selected: Boolean,
    daysLeft: Int?,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.extraSmall
    val selectedColor = MaterialTheme.colorScheme.primary
    val daysText = daysLeft?.let { daysLeftText(it) }
    val description = if (daysText != null) {
        stringResource(R.string.trash_open_item_days, stringResource(R.string.trash_open_item, card.fileName), daysText)
    } else {
        stringResource(R.string.trash_open_item, card.fileName)
    }
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .then(if (selected) Modifier.border(Spacing.xxs, selectedColor, shape) else Modifier)
            .semantics { this.selected = selected },
    ) {
        MediaThumbnail(
            path = card.path,
            contentDescription = description,
            isVideo = card.type == MediaType.VIDEO,
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClickLabel = stringResource(R.string.trash_open_label), role = Role.Image, onClick = onOpen),
        )
        if (card.hasRaw) {
            Box(Modifier.align(Alignment.TopStart).padding(Spacing.xxs)) {
                StatusBadge(stringResource(R.string.trash_raw_badge), BadgeKind.NEUTRAL)
            }
        }
        if (daysLeft != null && daysText != null) {
            DaysLeftLabel(daysLeft, daysText, Modifier.align(Alignment.BottomStart).padding(Spacing.xxs))
        }
        SelectionCheck(selected = selected, onToggle = onToggle, modifier = Modifier.align(Alignment.TopEnd))
    }
}

/** "28일 남음", 24시간이 안 남았으면 "곧 영구 삭제" */
@Composable
internal fun daysLeftText(days: Int): String =
    if (days <= 0) stringResource(R.string.trash_days_left_soon) else stringResource(R.string.trash_days_left, days)

/** "28일 남음". 곧 지워질 항목(3일 이하)은 경고 바탕 + 모래시계 아이콘(색만으로 구분하지 않음) */
@Composable
private fun DaysLeftLabel(days: Int, text: String, modifier: Modifier = Modifier) {
    val soon = days <= TRASH_WARN_DAYS
    val status = LocalStatusColors.current
    Surface(
        color = if (soon) status.warningContainer else ViewerColors.scrim,
        contentColor = if (soon) status.onWarningContainer else ViewerColors.onScrim,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            if (soon) Icon(Icons.Outlined.HourglassBottom, contentDescription = null, modifier = Modifier.size(Spacing.iconSmall))
            Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/** 오른쪽 위 선택 체크(48dp 터치 영역) */
@Composable
private fun SelectionCheck(selected: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.trash_select_item)
    Box(
        modifier = modifier
            .size(Spacing.touchTarget)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Spacing.iconMedium).background(MaterialTheme.colorScheme.onPrimary, CircleShape),
            )
        } else {
            Icon(
                Icons.Outlined.Circle,
                contentDescription = null,
                tint = ViewerColors.onScrim,
                modifier = Modifier.size(Spacing.iconMedium).background(ViewerColors.scrim, CircleShape),
            )
        }
    }
}

/** 뷰어 하단: 남은 보관 기간 + [복원]. 어두운 뷰어 위라 뷰어 색을 쓴다. */
@Composable
internal fun TrashViewerBar(
    daysLeft: Int?,
    busy: Boolean,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            when {
                daysLeft == null -> stringResource(R.string.trash_viewer_forever)
                daysLeft <= 0 -> stringResource(R.string.trash_viewer_soon)
                else -> stringResource(R.string.trash_viewer_days_left, daysLeft)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = ViewerColors.onScrimMuted,
        )
        Button(
            onClick = onRestore,
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(containerColor = ViewerColors.onScrim, contentColor = ViewerColors.background),
            modifier = Modifier.fillMaxWidth().heightIn(min = Spacing.primaryButtonHeight),
        ) {
            Icon(Icons.Outlined.RestoreFromTrash, contentDescription = null, modifier = Modifier.size(Spacing.iconMedium))
            Spacer(Modifier.size(Spacing.xs))
            Text(stringResource(R.string.trash_viewer_restore), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * 영구 삭제 확인: 대상 수 · 확보 용량 · 되돌릴 수 없음. 버튼은 동사("12개 영구 삭제" / "취소"), 기본 포커스는 취소.
 */
@Composable
internal fun DeleteConfirmDialog(
    confirm: DeleteConfirm,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.trash_confirm_title, confirm.count)) },
        text = {
            Text(
                stringResource(
                    if (confirm.hasRaw) R.string.trash_confirm_body_raw else R.string.trash_confirm_body,
                    bytesText(confirm.bytes),
                ),
                style = MaterialTheme.typography.bodyMedium.phraseBreak(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.trash_confirm_action, confirm.count))
            }
        },
        dismissButton = {
            val focus = remember { FocusRequester() }
            TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(focus)) {
                Text(stringResource(R.string.action_cancel))
            }
            LaunchedEffect(focus) { runCatching { focus.requestFocus() } }
        },
    )
}

/** 메시지 → 화면 문구 */
fun TrashMessage.text(context: Context): String = when (this) {
    is TrashMessage.Restored -> listOfNotNull(
        when {
            count > 0 && failed > 0 -> context.getString(R.string.trash_snack_restored_partial, count, failed)
            count > 0 -> context.getString(R.string.trash_snack_restored, count)
            failed > 0 -> context.getString(R.string.trash_snack_restore_failed_count, failed)
            else -> null
        },
        if (gone > 0) context.getString(R.string.trash_snack_restore_gone, gone) else null,
    ).joinToString(". ")
    TrashMessage.RestoreFailed -> context.getString(R.string.trash_snack_restore_failed)
    is TrashMessage.Deleted ->
        if (failed > 0) context.getString(R.string.trash_snack_deleted_partial, count, failed)
        else context.getString(R.string.trash_snack_deleted, count, bytesText(bytes))
    TrashMessage.DeleteFailed -> context.getString(R.string.trash_snack_delete_failed)
}
