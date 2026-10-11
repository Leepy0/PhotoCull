package com.leepy0.photocull.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.leepy0.photocull.ui.theme.Spacing
import com.leepy0.photocull.ui.theme.ViewerColors
import java.io.File

/** 파일 경로의 사진/영상 썸네일. 영상은 첫 프레임 + 재생 아이콘. */
@Composable
fun MediaThumbnail(
    path: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    isVideo: Boolean = false,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val request = remember(path) { ImageRequest.Builder(context).data(File(path)).build() }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
        )
        if (isVideo) {
            Icon(
                Icons.Filled.PlayCircle,
                contentDescription = null,
                tint = ViewerColors.onScrim,
                modifier = Modifier.align(Alignment.Center).size(Spacing.iconLarge),
            )
        }
    }
}

/**
 * 선택 가능한 그리드 칸. 사진을 누르면 [onOpen](상세 보기), 오른쪽 위 체크 영역(48dp)을 누르면 [onToggle].
 * 삭제 대상으로 선택되면 오류 색 테두리 + 체크 아이콘으로 표시한다(색만으로 구분하지 않음).
 */
@Composable
fun SelectableThumbnail(
    path: String,
    selected: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    isVideo: Boolean = false,
    /** 왼쪽 아래 작은 라벨(예: "선명도 12", "0:03") */
    label: String? = null,
    /** 왼쪽 위 배지(예: 베스트) */
    badge: (@Composable () -> Unit)? = null,
) {
    val shape = MaterialTheme.shapes.extraSmall
    val selectedColor = MaterialTheme.colorScheme.error
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .then(if (selected) Modifier.border(Spacing.xxs, selectedColor, shape) else Modifier)
            .semantics { this.selected = selected },
    ) {
        MediaThumbnail(
            path = path,
            contentDescription = contentDescription,
            isVideo = isVideo,
            modifier = Modifier.fillMaxSize().clickable(onClickLabel = "크게 보기", role = Role.Image, onClick = onOpen),
        )
        if (badge != null) {
            Box(Modifier.align(Alignment.TopStart).padding(Spacing.xxs)) { badge() }
        }
        if (label != null) {
            Surface(
                color = ViewerColors.scrim,
                contentColor = ViewerColors.onScrim,
                shape = shape,
                modifier = Modifier.align(Alignment.BottomStart).padding(Spacing.xxs),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = Spacing.xxs),
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(Spacing.touchTarget)
                .clickable(role = Role.Checkbox, onClick = onToggle)
                .semantics { this.contentDescription = if (selected) "삭제 선택 해제" else "삭제 대상으로 선택" },
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = selectedColor,
                    modifier = Modifier.size(Spacing.iconMedium).background(MaterialTheme.colorScheme.onError, CircleShape),
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
}
