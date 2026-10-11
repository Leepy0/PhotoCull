package com.leepy0.photocull.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import com.leepy0.photocull.R
import com.leepy0.photocull.storage.StoragePaths
import com.leepy0.photocull.ui.review.bytesText
import com.leepy0.photocull.ui.theme.Spacing

/** 저장 폴더 이름 편집: 미리보기 "DCIM/<이름>"과 검사 결과를 입력하는 대로 보여준다 */
@Composable
internal fun FolderNameDialog(
    current: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(current) }
    val valid = FolderNameRules.check(text) as? FolderNameRules.Check.Valid
    val save = { valid?.let { onSave(it.name) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
        title = { Text(stringResource(R.string.settings_folder_dialog_title)) },
        text = { FolderNameFields(text = text, onTextChange = { text = it }, onDone = { save() }) },
        confirmButton = {
            TextButton(onClick = { save() }, enabled = valid != null) { Text(stringResource(R.string.settings_folder_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** 저장 폴더 대화상자 본문: 입력란(검사 결과·미리보기) + 바뀐 글자 안내 + 적용 범위 안내 */
@Composable
internal fun FolderNameFields(
    text: String,
    onTextChange: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val check = FolderNameRules.check(text)
    val valid = check as? FolderNameRules.Check.Valid
    val focus = remember { FocusRequester() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        OutlinedTextField(
            value = text,
            onValueChange = { onTextChange(it.take(FolderNameRules.MAX_LENGTH * 2)) },
            label = { Text(stringResource(R.string.settings_folder_field)) },
            singleLine = true,
            isError = valid == null,
            supportingText = {
                val message = when (check) {
                    FolderNameRules.Check.Blank -> stringResource(R.string.settings_folder_blank)
                    FolderNameRules.Check.TooLong -> stringResource(R.string.settings_folder_too_long, FolderNameRules.MAX_LENGTH)
                    FolderNameRules.Check.OnlyDots -> stringResource(R.string.settings_folder_invalid)
                    is FolderNameRules.Check.Valid -> stringResource(
                        R.string.settings_folder_preview,
                        StoragePaths.destDisplayPath(check.name),
                    )
                }
                Text(message)
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        if (valid?.replaced == true) {
            Text(
                stringResource(R.string.settings_folder_replaced),
                style = MaterialTheme.typography.bodyMedium.phraseBreak(),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            stringResource(R.string.settings_folder_note),
            style = MaterialTheme.typography.bodyMedium.phraseBreak(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // 열자마자 입력할 수 있게(미리보기·스크린샷에서는 커서 깜빡임이 끝나지 않아 생략)
    if (!LocalInspectionMode.current) LaunchedEffect(focus) { runCatching { focus.requestFocus() } }
}

/** 마지막 비정상 종료 내용: 고정폭 글꼴, 스크롤, [복사] / [지우기] */
@Composable
internal fun CrashReportDialog(
    report: String,
    onCopy: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var copied by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_crash_dialog_title)) },
        text = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Text(
                        report,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.verticalScroll(rememberScrollState()).padding(Spacing.s),
                    )
                }
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onClear,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.settings_crash_clear))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
                TextButton(
                    onClick = {
                        onCopy()
                        copied = true
                    },
                ) {
                    if (copied) {
                        Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    }
                    Text(stringResource(if (copied) R.string.settings_crash_copied else R.string.settings_crash_copy))
                }
            }
        },
    )
}

/** 보관 기간을 줄이면 바로 영구 삭제될 항목이 있을 때: 대상 수·용량·되돌릴 수 없음, 기본 포커스는 취소 */
@Composable
internal fun RetentionConfirmDialog(
    confirm: RetentionConfirm,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.settings_retention_confirm_title, confirm.days)) },
        text = {
            Text(
                stringResource(R.string.settings_retention_confirm_body, confirm.days, confirm.count, bytesText(confirm.bytes)),
                style = MaterialTheme.typography.bodyMedium.phraseBreak(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.settings_retention_confirm_action, confirm.days))
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
