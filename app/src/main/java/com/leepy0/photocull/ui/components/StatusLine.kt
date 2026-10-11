package com.leepy0.photocull.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.leepy0.photocull.ui.theme.LocalStatusColors
import com.leepy0.photocull.ui.theme.Spacing

enum class StatusKind { INFO, SUCCESS, WARNING, ERROR }

@Composable
fun statusColor(kind: StatusKind): Color = when (kind) {
    StatusKind.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    StatusKind.SUCCESS -> LocalStatusColors.current.success
    StatusKind.WARNING -> LocalStatusColors.current.warning
    StatusKind.ERROR -> MaterialTheme.colorScheme.error
}

/** 아이콘 + 텍스트로 상태를 표시하는 한 줄 */
@Composable
fun StatusLine(kind: StatusKind, text: String, modifier: Modifier = Modifier) {
    val icon = when (kind) {
        StatusKind.INFO -> Icons.Outlined.Info
        StatusKind.SUCCESS -> Icons.Outlined.CheckCircle
        StatusKind.WARNING -> Icons.Outlined.WarningAmber
        StatusKind.ERROR -> Icons.Outlined.ErrorOutline
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = statusColor(kind), modifier = Modifier.size(Spacing.iconSmall + Spacing.xxs))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
