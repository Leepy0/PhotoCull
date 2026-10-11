package com.leepy0.photocull.ui.viewer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.leepy0.photocull.R
import com.leepy0.photocull.data.db.MediaType
import com.leepy0.photocull.data.review.MediaCard
import com.leepy0.photocull.ui.theme.Spacing
import com.leepy0.photocull.ui.theme.ViewerColors
import com.leepy0.photocull.util.Format
import kotlin.math.roundToInt

/** 뷰어 상·하단 바가 피할 인셋(상태 표시줄·내비게이션 바·카메라 컷아웃) */
internal val ViewerTopInsets: WindowInsets
    @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

internal val ViewerBottomInsets: WindowInsets
    @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)

/** 정보 줄: "3 / 12 · 선명도 23" 또는 영상 "1 / 5 · 0:03 · 45 MB" */
@Composable
internal fun viewerMetaLine(card: MediaCard, position: Int?, total: Int?): String {
    val parts = mutableListOf<String>()
    if (position != null && total != null && total > 1) parts += stringResource(R.string.viewer_position, position, total)
    if (card.type == MediaType.VIDEO) {
        card.durationMs?.let { parts += Format.duration(it) }
        parts += Format.bytes(card.bytes)
    } else {
        card.sharpness?.let { parts += stringResource(R.string.viewer_sharpness, it.roundToInt()) }
    }
    return parts.joinToString(META_SEPARATOR) { it.replace(' ', NBSP) }
}

/** 촬영 정보 줄: "ISO 400 · 1/250 · f/5.6 · 50mm" (사진만) */
internal fun viewerExifLine(card: MediaCard): String? =
    if (card.type == MediaType.VIDEO) null else ExifFormat.line(card)

private const val META_SEPARATOR = " · "
private const val NBSP = '\u00A0'

/**
 * 뷰어 상단 바: 뒤로 · 파일명 · 정보 줄 · 오른쪽 동작(확대 토글 등).
 * 사진 위에 반투명 검정 바를 깔아 어떤 사진에서도 글자가 보이게 한다.
 */
@Composable
internal fun ViewerTopBar(
    title: String,
    meta: String?,
    onBack: (() -> Unit)?,
    detail: String? = null,
    modifier: Modifier = Modifier,
    applyInsets: Boolean = true,
    badges: @Composable RowScope.() -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(color = ViewerColors.scrim, contentColor = ViewerColors.onScrim, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .then(if (applyInsets) Modifier.windowInsetsPadding(ViewerTopInsets) else Modifier)
                .heightIn(min = Spacing.touchTarget + Spacing.m)
                .padding(horizontal = Spacing.xxs, vertical = Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = ViewerColors.onScrim),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            } else {
                Spacer(Modifier.size(Spacing.s))
            }
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = Spacing.xxs),
                verticalArrangement = Arrangement.spacedBy(Spacing.zero),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = ViewerColors.onScrim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    badges()
                }
                if (!meta.isNullOrBlank()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ViewerColors.onScrim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!detail.isNullOrBlank()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = ViewerColors.onScrimMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                actions()
            }
            Spacer(Modifier.size(Spacing.xxs))
        }
    }
}

/** 하단 바: 호출 측 행동(삭제 표시 등)을 반투명 검정 위에 */
@Composable
internal fun ViewerBottomArea(
    modifier: Modifier = Modifier,
    applyInsets: Boolean = true,
    content: @Composable () -> Unit,
) {
    // 호출 측이 아무것도 그리지 않으면(하단 행동 없음) 빈 띠를 남기지 않고 접힌다.
    val insets = ViewerBottomInsets
    val layoutDirection = LocalLayoutDirection.current
    CompositionLocalProvider(LocalContentColor provides ViewerColors.onScrim) {
        Layout(
            content = content,
            modifier = modifier.fillMaxWidth().background(ViewerColors.scrim),
        ) { measurables, constraints ->
            if (measurables.isEmpty()) return@Layout layout(constraints.minWidth, 0) {}
            val left = Spacing.m.roundToPx() + if (applyInsets) insets.getLeft(this, layoutDirection) else 0
            val right = Spacing.m.roundToPx() + if (applyInsets) insets.getRight(this, layoutDirection) else 0
            val top = Spacing.s.roundToPx()
            val bottom = Spacing.s.roundToPx() + if (applyInsets) insets.getBottom(this) else 0
            val gap = Spacing.xs.roundToPx()
            val innerWidth = (constraints.maxWidth - left - right).coerceAtLeast(0)
            val placeables = measurables.map { it.measure(Constraints(maxWidth = innerWidth)) }
            val height = top + bottom + placeables.sumOf { it.height } + gap * (placeables.size - 1)
            layout(constraints.maxWidth, height) {
                var y = top
                placeables.forEach {
                    it.place(left, y)
                    y += it.height + gap
                }
            }
        }
    }
}

/**
 * 하단 줄: [‹ 이전] · 호출 측 행동 · [다음 ›]. 확대한 사진은 좌우로 밀어도 사진 안에서만 움직이므로
 * 넘기기를 버튼으로도 둔다(48dp, 이름을 읽어 준다). 끝에 닿으면 버튼은 흐려지고 자리는 그대로 둔다.
 */
@Composable
internal fun ViewerNavRow(
    canPrevious: Boolean,
    canNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    /** 호출 측 맨 아래 버튼의 높이: 화살표를 그 버튼과 같은 줄 가운데에 맞춘다(위에 안내 글이 있어도) */
    actionHeight: Dp = Spacing.primaryButtonHeight,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Box(Modifier.heightIn(min = actionHeight), contentAlignment = Alignment.Center) {
            ViewerNavButton(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                label = stringResource(R.string.viewer_previous),
                enabled = canPrevious,
                onClick = onPrevious,
            )
        }
        Box(Modifier.weight(1f)) { content() }
        Box(Modifier.heightIn(min = actionHeight), contentAlignment = Alignment.Center) {
            ViewerNavButton(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                label = stringResource(R.string.viewer_next),
                enabled = canNext,
                onClick = onNext,
            )
        }
    }
}

@Composable
private fun ViewerNavButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedIconButton(
        onClick = onClick,
        enabled = enabled,
        border = BorderStroke(
            ButtonDefaults.outlinedButtonBorder(enabled).width,
            if (enabled) ViewerColors.onScrimMuted else ViewerColors.onScrimMuted.copy(alpha = DISABLED_ALPHA),
        ),
        colors = IconButtonDefaults.outlinedIconButtonColors(
            contentColor = ViewerColors.onScrim,
            disabledContentColor = ViewerColors.onScrimMuted.copy(alpha = DISABLED_ALPHA),
        ),
        modifier = Modifier.size(Spacing.touchTarget),
    ) {
        Icon(icon, contentDescription = label)
    }
}

private const val DISABLED_ALPHA = 0.38f

/** [전체 보기 ↔ 100%] 토글. 지금 할 수 있는 동작을 글자로 보여준다. */
@Composable
fun ZoomToggleButton(
    isZoomedIn: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val label = stringResource(if (isZoomedIn) R.string.viewer_zoom_fit else R.string.viewer_zoom_original)
    val description = stringResource(if (isZoomedIn) R.string.viewer_zoom_fit_desc else R.string.viewer_zoom_original_desc)
    ViewerOutlinedButton(
        text = label,
        icon = if (isZoomedIn) Icons.Outlined.ZoomOutMap else Icons.Outlined.ZoomIn,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics { contentDescription = description },
    )
}

/** 어두운 뷰어 위의 외곽선 버튼(흰 글자) */
@Composable
fun ViewerOutlinedButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = Spacing.touchTarget),
        border = BorderStroke(ButtonDefaults.outlinedButtonBorder(enabled).width, ViewerColors.onScrimMuted),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = ViewerColors.onScrim,
            disabledContentColor = ViewerColors.onScrimMuted,
        ),
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        }
        Text(text, maxLines = 1)
    }
}

/** 뷰어 안 오류·안내(검은 배경 위 흰 글자) */
@Composable
internal fun ViewerMessage(
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.BrokenImage,
    action: @Composable (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize().padding(Spacing.l), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = ViewerColors.onScrimMuted, modifier = Modifier.size(Spacing.iconLarge))
            Spacer(Modifier.height(Spacing.m))
            Text(title, style = MaterialTheme.typography.titleMedium, color = ViewerColors.onScrim, textAlign = TextAlign.Center)
            if (body != null) {
                Spacer(Modifier.height(Spacing.xs))
                Text(body, style = MaterialTheme.typography.bodyMedium, color = ViewerColors.onScrimMuted, textAlign = TextAlign.Center)
            }
            if (action != null) {
                Spacer(Modifier.height(Spacing.l))
                action()
            }
        }
    }
}
