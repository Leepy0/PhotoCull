package com.leepy0.photocull.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import com.leepy0.photocull.R
import com.leepy0.photocull.ui.components.IosSwitch
import com.leepy0.photocull.ui.components.StatusKind
import com.leepy0.photocull.ui.components.statusColor
import com.leepy0.photocull.ui.theme.Spacing

/** 한국어 문장이 낱자 단위가 아니라 어절 단위로 줄바꿈되게("알려줍니/다" 방지) */
internal fun TextStyle.phraseBreak(): TextStyle =
    copy(lineBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase))

/** 목록 글자 시작 위치(가장자리 16 + 아이콘 24 + 간격 16). 구역 제목과 선택 버튼을 글자에 맞춘다. */
internal val SettingsTextStart = Spacing.m + Spacing.iconMedium + Spacing.m

/** 구역 제목(iOS 그룹 목록의 작은 보조색 제목). 구역 사이는 간격으로 나눈다. */
@Composable
internal fun SettingsSectionHeader(title: String, first: Boolean = false) {
    Text(
        title,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.l, end = Spacing.m, top = if (first) Spacing.xs else Spacing.l, bottom = Spacing.xs)
            .semantics { heading() },
    )
}

/** iOS inset grouped 목록: 10dp 둥근 흰 셀 묶음, 항목 사이는 글자에 맞춘 0.5dp 구분선 */
@Composable
internal fun SettingsGroup(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.m),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        // 마지막 항목의 구분선 0.5dp는 높이를 줄여 둥근 모서리 밖으로 잘라낸다
        Column(
            Modifier.layout { measurable, constraints ->
                val cut = 1
                val placeable = measurable.measure(constraints)
                layout(placeable.width, (placeable.height - cut).coerceAtLeast(0)) { placeable.place(0, 0) }
            },
        ) { content() }
    }
}

@Composable
private fun cellColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
private fun Modifier.cellDivider(): Modifier {
    val color = MaterialTheme.colorScheme.outlineVariant
    val start = with(LocalDensity.current) { SettingsTextStart.toPx() }
    return drawBehind {
        val y = size.height - 1f
        drawLine(color, Offset(start, y), Offset(size.width, y), strokeWidth = 1f)
    }
}

@Composable
private fun SupportingText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium.phraseBreak(), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 누르면 대화상자·다른 화면을 여는 항목 */
@Composable
internal fun SettingsClickItem(
    icon: ImageVector,
    title: String,
    supporting: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        colors = cellColors(),
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { SupportingText(it) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = trailing,
        modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).cellDivider(),
    )
}

/** 켜고 끄는 항목. 줄 전체가 눌린다(48dp 이상). */
@Composable
internal fun SettingsSwitchItem(
    icon: ImageVector,
    title: String,
    supporting: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        colors = cellColors(),
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { SupportingText(it) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { IosSwitch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange).cellDivider(),
    )
}

/**
 * 몇 개 중 하나를 고르는 항목. 대화상자를 열지 않고 선택지를 모두 보여준다(한 번에 바꾸고 결과를 바로 본다).
 * 설명 줄은 지금 고른 값이 무엇을 하는지 알려준다.
 */
@Composable
internal fun <T> SettingsChoiceItem(
    icon: ImageVector,
    title: String,
    supporting: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().cellDivider().padding(bottom = Spacing.s)) {
        ListItem(
            colors = cellColors(),
            headlineContent = { Text(title) },
            supportingContent = { SupportingText(supporting) },
            leadingContent = { Icon(icon, contentDescription = null) },
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = SettingsTextStart, end = Spacing.m)) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    label = { Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}

/** 권한이 앱에 얼마나 필요한지. 온보딩의 "필수 / 권장"과 같은 말을 쓴다 */
enum class PermissionNeed { REQUIRED, RECOMMENDED, OPTIONAL }

/** 권한 항목: 설명 아래 상태(아이콘 + 글자) + 오른쪽에 보이는 [설정 열기]. 줄 전체가 눌린다(48dp 이상). */
@Composable
internal fun SettingsPermissionItem(
    icon: ImageVector,
    title: String,
    supporting: String,
    granted: Boolean,
    need: PermissionNeed,
    onClick: () -> Unit,
) {
    val label = stringResource(R.string.settings_perm_open, title)
    ListItem(
        colors = cellColors(),
        headlineContent = { Text(title) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                SupportingText(supporting)
                when {
                    granted -> PermissionState(StatusKind.SUCCESS, stringResource(R.string.settings_perm_granted))
                    need == PermissionNeed.REQUIRED -> PermissionState(StatusKind.ERROR, stringResource(R.string.settings_perm_needed))
                    need == PermissionNeed.RECOMMENDED -> PermissionState(StatusKind.INFO, stringResource(R.string.settings_perm_recommended))
                    else -> PermissionState(StatusKind.INFO, stringResource(R.string.settings_perm_optional))
                }
            }
        },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(stringResource(R.string.settings_perm_open_short), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Icon(
                    Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Spacing.iconSmall + Spacing.xxs),
                )
            }
        },
        modifier = Modifier.clickable(onClickLabel = label, role = Role.Button, onClick = onClick).cellDivider(),
    )
}

@Composable
private fun PermissionState(kind: StatusKind, text: String) {
    val color = statusColor(kind)
    val icon = when (kind) {
        StatusKind.SUCCESS -> Icons.Outlined.CheckCircle
        StatusKind.ERROR -> Icons.Outlined.ErrorOutline
        StatusKind.WARNING -> Icons.Outlined.WarningAmber
        StatusKind.INFO -> Icons.Outlined.Info
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(Spacing.iconSmall + Spacing.xxs))
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** 눌리지 않는 정보 줄(버전 등) */
@Composable
internal fun SettingsInfoItem(icon: ImageVector, title: String, value: String) {
    ListItem(
        colors = cellColors(),
        headlineContent = { Text(title) },
        supportingContent = { SupportingText(value) },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.cellDivider(),
    )
}
