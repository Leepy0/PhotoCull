package com.leepy0.photocull.ui.viewer

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.leepy0.photocull.R
import com.leepy0.photocull.data.db.MediaType
import com.leepy0.photocull.data.review.MediaCard
import com.leepy0.photocull.ui.theme.Spacing
import com.leepy0.photocull.ui.theme.ViewerColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 전체 화면 사진/영상 뷰어(검토·그룹·휴지통 화면이 오버레이로 띄운다).
 * - 좌우로 넘기기, 핀치/더블탭 확대(원본 100%의 2배까지), 큰 사진은 영역 단위 고해상도 디코딩
 * - [startAtFocus]면 처음에 가장 선명한 위치(focusX/focusY)를 100%로 확대해 연다
 * - 영상은 앱 안에서 재생하고 [다른 앱으로 열기]를 둔다
 * - [items]가 바뀌어도(삭제·복원) 보고 있던 항목을 유지한다. 지금 항목이 사라지면 같은 자리의 다음 항목을 보여준다
 * - 하단에 [‹ 이전] [다음 ›] 버튼(48dp): 100%로 확대된 사진은 좌우로 밀면 사진 안에서 움직이므로 버튼으로 넘긴다
 * [bottomBar]는 현재 보이는 항목을 받아 하단 행동(삭제 표시, 복원 등)을 그린다. 그 안에서
 * [LocalViewerNavigation]으로 다음 항목으로 넘길 수 있다(예: 삭제 대상으로 표시한 뒤 다음 사진).
 * [fullScreenWindow]가 true면 별도 창으로 띄워 하단 탭·상태 표시줄까지 덮는다(뒤로 가기로 닫힘).
 */
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
    fullScreenWindow: Boolean = true,
) {
    if (fullScreenWindow) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
                dismissOnClickOutside = false,
            ),
        ) {
            FullScreenDialogWindow()
            PhotoViewerContent(items, initialIndex, onDismiss, modifier, startAtFocus, onPageChanged, overlay, bottomBar)
        }
    } else {
        BackHandler(onBack = onDismiss)
        PhotoViewerContent(items, initialIndex, onDismiss, modifier, startAtFocus, onPageChanged, overlay, bottomBar)
    }
}

/** 대화상자 창을 화면 전체(상태 표시줄·내비게이션 바 아래까지)로, 시스템 아이콘은 밝게 */
@Composable
private fun FullScreenDialogWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            window.setDimAmount(0f)
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        onDispose { }
    }
}

/** 뷰어의 이전/다음 넘기기. 하단 바([PhotoViewer]의 bottomBar) 안에서 [LocalViewerNavigation]으로 쓴다. */
@Stable
class ViewerNavigation internal constructor(
    private val pager: PagerState,
    private val scope: CoroutineScope,
) {
    /** 넘기는 중이면 넘어갈 쪽 기준(빠르게 두 번 눌러도 두 장 넘어간다) */
    private val basePage: Int get() = if (pager.isScrollInProgress) pager.targetPage else pager.currentPage

    val canGoPrevious: Boolean get() = basePage > 0
    val canGoNext: Boolean get() = basePage < pager.pageCount - 1

    fun previous() {
        val target = basePage - 1
        if (target >= 0) scope.launch { pager.animateScrollToPage(target) }
    }

    fun next() {
        val target = basePage + 1
        if (target < pager.pageCount) scope.launch { pager.animateScrollToPage(target) }
    }
}

/** 뷰어 하단 바 안에서만 값이 있다 */
val LocalViewerNavigation = compositionLocalOf<ViewerNavigation?> { null }

/** 페이지마다 만든 확대·재생 상태를 하단·상단 바에서 쓰기 위한 모음 */
@Stable
internal class ViewerPageControllers {
    val photos = mutableStateMapOf<Long, PhotoZoomState>()
    val videos = mutableStateMapOf<Long, VideoPlaybackState>()
}

@Composable
internal fun PhotoViewerContent(
    items: List<MediaCard>,
    initialIndex: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    startAtFocus: Boolean = true,
    onPageChanged: (MediaCard) -> Unit = {},
    overlay: @Composable BoxScope.(MediaCard) -> Unit = {},
    bottomBar: @Composable (MediaCard) -> Unit = {},
) {
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnPageChanged by rememberUpdatedState(onPageChanged)
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { currentOnDismiss() }
        Box(modifier.fillMaxSize().background(ViewerColors.background))
        return
    }

    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, items.lastIndex)) { items.size }
    var currentId by rememberSaveable { mutableStateOf(items[initialIndex.coerceIn(0, items.lastIndex)].id) }
    val latestItems by rememberUpdatedState(items)

    // 목록이 바뀌면(삭제·복원·분석 결과 추가) 보던 항목에 머문다. 사라졌으면 같은 자리(마지막이면 이전) 항목.
    LaunchedEffect(items) {
        val index = items.indexOfFirst { it.id == currentId }
        val target = if (index >= 0) index else pagerState.currentPage.coerceIn(0, items.lastIndex)
        if (target != pagerState.currentPage) pagerState.scrollToPage(target)
        currentId = items[target].id
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            latestItems.getOrNull(page)?.let {
                currentId = it.id
                currentOnPageChanged(it)
            }
        }
    }

    val currentIndex = pagerState.currentPage.coerceIn(0, items.lastIndex)
    val current = items[currentIndex]
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    val controllers = remember { ViewerPageControllers() }
    val scope = rememberCoroutineScope()
    val navigation = remember(pagerState, scope) { ViewerNavigation(pagerState, scope) }
    val context = LocalContext.current

    Box(modifier.fillMaxSize().background(ViewerColors.background)) {
        HorizontalPager(
            state = pagerState,
            key = { page -> items[page].id },
            pageSpacing = Spacing.m,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val card = items[page]
            if (card.type == MediaType.VIDEO) {
                val playback = rememberVideoPlaybackState(card.id)
                DisposableEffect(card.id, playback) {
                    controllers.videos[card.id] = playback
                    onDispose { controllers.videos.remove(card.id) }
                }
                VideoPlayer(card = card, state = playback, isCurrent = page == pagerState.settledPage)
            } else {
                val zoom = rememberPhotoZoomState(card)
                DisposableEffect(card.id, zoom) {
                    controllers.photos[card.id] = zoom
                    onDispose { controllers.photos.remove(card.id) }
                }
                ZoomablePhoto(
                    state = zoom,
                    initialView = { s -> if (startAtFocus) s.focusView() else null },
                    onTap = { chromeVisible = !chromeVisible },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        overlay(current)

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ViewerTopBar(
                title = current.fileName,
                meta = viewerMetaLine(current, currentIndex + 1, items.size),
                detail = viewerExifLine(current),
                onBack = onDismiss,
                actions = {
                    if (current.type == MediaType.VIDEO) {
                        ViewerOutlinedButton(
                            text = stringResource(R.string.viewer_open_external),
                            icon = Icons.AutoMirrored.Outlined.OpenInNew,
                            onClick = { openExternally(context, current) },
                        )
                    } else {
                        val zoom = controllers.photos[current.id]
                        ZoomToggleButton(
                            isZoomedIn = zoom?.isZoomedIn == true,
                            enabled = zoom?.canToggleZoom == true,
                            onClick = { zoom?.let { scope.launch { it.toggleZoom() } } },
                        )
                    }
                },
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ViewerBottomArea {
                if (current.type == MediaType.VIDEO) {
                    controllers.videos[current.id]?.let { VideoControls(it, fallbackDurationMs = current.durationMs) }
                }
                CompositionLocalProvider(LocalViewerNavigation provides navigation) {
                    if (items.size > 1) {
                        ViewerNavRow(
                            canPrevious = navigation.canGoPrevious,
                            canNext = navigation.canGoNext,
                            onPrevious = navigation::previous,
                            onNext = navigation::next,
                        ) { bottomBar(current) }
                    } else {
                        bottomBar(current)
                    }
                }
            }
        }
    }
}
