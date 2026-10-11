package com.leepy0.photocull.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.leepy0.photocull.data.review.MediaCard
import com.leepy0.photocull.ui.theme.ViewerColors
import kotlinx.coroutines.launch

/**
 * 펼친 화면의 오른쪽 미리보기(목록 옆에 붙는 확대 뷰어). 고른 사진을 가장 선명한 위치 100%로 연다.
 * 화면 안에 들어가므로 시스템 인셋은 바깥 Scaffold가 처리한다.
 */
@Composable
fun PhotoPreviewPane(
    card: MediaCard,
    position: Int?,
    total: Int?,
    modifier: Modifier = Modifier,
    startAtFocus: Boolean = true,
    bottomBar: @Composable (MediaCard) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    Box(modifier.clip(MaterialTheme.shapes.medium).background(ViewerColors.background)) {
        key(card.id) {
            val zoom = rememberPhotoZoomState(card)
            ZoomablePhoto(
                state = zoom,
                initialView = { s -> if (startAtFocus) s.focusView() else null },
                modifier = Modifier.fillMaxSize(),
            )
            ViewerTopBar(
                title = card.fileName,
                meta = viewerMetaLine(card, position, total),
                detail = viewerExifLine(card),
                onBack = null,
                applyInsets = false,
                modifier = Modifier.align(Alignment.TopCenter),
                actions = {
                    ZoomToggleButton(
                        isZoomedIn = zoom.isZoomedIn,
                        enabled = zoom.canToggleZoom,
                        onClick = { scope.launch { zoom.toggleZoom() } },
                    )
                },
            )
        }
        ViewerBottomArea(applyInsets = false, modifier = Modifier.align(Alignment.BottomCenter)) {
            bottomBar(card)
        }
    }
}
