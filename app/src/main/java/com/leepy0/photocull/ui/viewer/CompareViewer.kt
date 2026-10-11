package com.leepy0.photocull.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import com.leepy0.photocull.data.review.MediaCard
import com.leepy0.photocull.ui.theme.ViewerColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 같은 위치 비교 상태. 다른 장으로 바꿔도 원본 픽셀 기준 배율과 위치([view])를 옮겨 적용한다
 * (해상도가 달라도 같은 100%, 가로/세로 비가 다르면 위치 대신 그 장의 가장 선명한 곳).
 * [view]가 null이면 아직 아무 장도 준비되지 않은 상태(첫 장은 가장 선명한 위치 100%로 연다).
 */
@Stable
class CompareViewerState internal constructor(startAtFocus: Boolean) {
    internal val startAtFocus: Boolean = startAtFocus

    var view: SharedView? by mutableStateOf(null)
        internal set

    /** 지금 보이고 조작 가능한 장의 확대 상태 */
    var active: PhotoZoomState? by mutableStateOf(null)
        internal set

    val isZoomedIn: Boolean get() = active?.isZoomedIn ?: (view?.isZoomedIn == true)

    val canToggleZoom: Boolean get() = active?.canToggleZoom == true

    suspend fun toggleZoom() {
        active?.toggleZoom()
    }
}

@Composable
fun rememberCompareViewerState(startAtFocus: Boolean = true): CompareViewerState =
    remember { CompareViewerState(startAtFocus) }

/**
 * 연사 비교용 확대 뷰어. [card]를 바꾸면
 * 1) 새 장을 보이지 않게 아래에서 준비하고(원본 영역 디코딩)
 * 2) 직전 장의 확대 배율·위치를 그대로 적용한 뒤
 * 3) 준비가 끝나면 한 번에 바꿔 보여준다(직전 장은 그때까지 그대로 보인다).
 * 그래서 눈·초점처럼 같은 부분을 번갈아 보며 비교할 수 있다.
 */
@Composable
fun CompareViewer(
    card: MediaCard,
    state: CompareViewerState,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
) {
    if (LocalInspectionMode.current) {
        val zoom = rememberPhotoZoomState(card)
        DisposableEffect(zoom) {
            state.active = zoom
            onDispose { if (state.active === zoom) state.active = null }
        }
        ZoomablePhoto(
            state = zoom,
            initialView = { s -> state.view?.let(s::viewFor) ?: if (state.startAtFocus) s.focusView() else null },
            modifier = modifier.fillMaxSize().background(ViewerColors.background),
        )
        return
    }

    // shown = 지금 보이는 장, pending = 준비 중인 새 장(준비되면 shown이 된다)
    var shown by remember { mutableStateOf(card) }
    var pending by remember { mutableStateOf<MediaCard?>(null) }
    LaunchedEffect(card.id) {
        if (card.id == shown.id) {
            shown = card
            pending = null
        } else {
            pending = card
        }
    }
    // 목록 갱신(선명도·베스트 등)으로 같은 장의 정보가 바뀌면 최신 정보를 쓴다
    val layers = listOfNotNull(shown, pending).map { if (it.id == card.id) card else it }
    Box(modifier.clipToBounds().background(ViewerColors.background)) {
        layers.forEach { layerCard ->
            key(layerCard.id) {
                val isPending = pending?.id == layerCard.id
                CompareLayer(
                    card = layerCard,
                    compare = state,
                    isPending = isPending,
                    interactive = pending == null,
                    onTap = onTap,
                    onReady = {
                        if (pending?.id == layerCard.id) {
                            shown = layerCard
                            pending = null
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun CompareLayer(
    card: MediaCard,
    compare: CompareViewerState,
    isPending: Boolean,
    interactive: Boolean,
    onTap: (() -> Unit)?,
    onReady: () -> Unit,
) {
    val zoom = rememberPhotoZoomState(card)
    var applied by remember { mutableStateOf(false) }
    val currentOnReady by rememberUpdatedState(onReady)
    val currentIsPending by rememberUpdatedState(isPending)

    ZoomablePhoto(
        state = zoom,
        crossfade = false,
        gesturesEnabled = interactive && applied,
        // 처음 열 때만 가장 선명한 위치, 그 뒤로는 직전 장의 상태를 원본 픽셀 기준으로 옮겨서
        initialView = { s -> compare.view?.let(s::viewFor) ?: if (compare.startAtFocus) s.focusView() else null },
        onInitialViewApplied = { applied = true },
        onTap = onTap,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = if (isPending && !applied && !zoom.hasFailed) 0f else 1f },
    )

    // 준비 중인 장: 확대 적용 + 고해상도 타일이 그려지면(또는 잠시 뒤) 교체.
    // 파일이 없거나 손상돼 열 수 없으면 기다리지 않고 바꿔 그 장의 안내 문구를 보여준다
    // (빈 화면에서 계속 도는 대신). 실패한 장은 비교 상태를 바꾸지 않는다.
    LaunchedEffect(zoom) {
        withTimeoutOrNull(READY_TIMEOUT_MS) { snapshotFlow { applied || zoom.hasFailed }.first { it } }
        if (currentIsPending) {
            if (!zoom.hasFailed) {
                withTimeoutOrNull(SWAP_TIMEOUT_MS) { snapshotFlow { zoom.isFullQuality || zoom.hasFailed }.first { it } }
                // 한 프레임 쉬어 타일이 실제로 그려진 뒤 바꾼다
                delay(FRAME_MS)
            }
            currentOnReady()
        }
    }

    // 보이는 장만 비교 상태를 갱신한다(사용자가 확대·이동한 결과). 열지 못한 장은 조작 대상이 아니다.
    if (!isPending && !zoom.hasFailed) {
        DisposableEffect(zoom) {
            compare.active = zoom
            onDispose { if (compare.active === zoom) compare.active = null }
        }
        LaunchedEffect(zoom) {
            snapshotFlow { applied }.first { it }
            snapshotFlow { zoom.sharedView() }.collect { v -> if (v != null) compare.view = v }
        }
    }
}

private const val SWAP_TIMEOUT_MS = 1_500L
private const val READY_TIMEOUT_MS = 3_000L
private const val FRAME_MS = 16L
