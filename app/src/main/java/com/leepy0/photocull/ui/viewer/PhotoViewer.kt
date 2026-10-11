package com.leepy0.photocull.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.leepy0.photocull.data.db.MediaType
import com.leepy0.photocull.data.review.MediaCard
import com.leepy0.photocull.ui.components.MediaThumbnail
import com.leepy0.photocull.ui.theme.ViewerColors

/**
 * 전체 화면 사진/영상 뷰어(검토·그룹·휴지통 화면이 오버레이로 띄운다).
 * - 좌우로 넘기기, 핀치/더블탭 확대, 큰 사진은 영역 단위 고해상도 디코딩
 * - [startAtFocus]면 처음에 가장 선명한 위치(focusX/focusY)를 100%로 확대해 연다
 * - 영상은 재생
 * [bottomBar]는 현재 보이는 항목을 받아 하단 행동(삭제 표시, 베스트 지정 등)을 그린다.
 */
// TODO(review): 실제 구현으로 교체 (현재는 단순 표시)
@Composable
fun PhotoViewer(
    items: List<MediaCard>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    startAtFocus: Boolean = true,
    onPageChanged: (MediaCard) -> Unit = {},
    overlay: @Composable BoxScope.(MediaCard) -> Unit = {},
    bottomBar: @Composable (MediaCard) -> Unit = {},
) {
    BackHandler(onBack = onDismiss)
    val item = items.getOrNull(initialIndex) ?: return
    Box(modifier.fillMaxSize().background(ViewerColors.background)) {
        MediaThumbnail(item.path, item.fileName, Modifier.fillMaxSize(), isVideo = item.type == MediaType.VIDEO, contentScale = ContentScale.Fit)
        overlay(item)
    }
}
