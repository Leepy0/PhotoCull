@file:OptIn(ExperimentalTelephotoApi::class)

package com.leepy0.photocull.ui.viewer

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.leepy0.photocull.R
import com.leepy0.photocull.ui.theme.Spacing
import com.leepy0.photocull.ui.theme.ViewerColors
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.leepy0.photocull.data.review.MediaCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.saket.telephoto.ExperimentalTelephotoApi
import me.saket.telephoto.subsamplingimage.SubSamplingImageSource
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.DynamicZoomSpec
import me.saket.telephoto.zoomable.DynamicZoomSpecInputs
import me.saket.telephoto.zoomable.DynamicZoomSpecScope
import me.saket.telephoto.zoomable.EnabledZoomGestures
import me.saket.telephoto.zoomable.Viewport
import me.saket.telephoto.zoomable.ZoomFocalPoint
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableContent
import me.saket.telephoto.zoomable.ZoomableImage
import me.saket.telephoto.zoomable.ZoomableImageSource
import me.saket.telephoto.zoomable.ZoomableImageState
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.coil3.coil
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.spatial.CoordinateSpace
import me.saket.telephoto.zoomable.spatial.SpatialOffset
import okio.Path.Companion.toPath
import java.io.File
import kotlin.math.max

/**
 * 사진 1장의 확대 상태. 확대·이동은 telephoto가 처리하고, 여기서는
 * - 원본 100%(사진 1px = 화면 1px) 배율 계산
 * - 크기와 무관한 확대 상태([NormalizedView]) 읽기/적용(같은 위치 비교, 미리보기→원본 전환 시 위치 유지)
 * 를 맡는다.
 */
@Stable
class PhotoZoomState internal constructor(
    card: MediaCard,
    internal val zoomable: ZoomableState,
    internal val image: ZoomableImageState,
) {
    var card: MediaCard by mutableStateOf(card)
        internal set

    /** 파일이 없음(옮겨졌거나 삭제됨). 기다려도 준비되지 않는다 */
    var isMissing: Boolean by mutableStateOf(false)
        internal set

    /** 스크린샷·미리보기(실제 디코딩 없음)에서 그린 확대 상태 */
    internal var previewMode: Boolean = false
    internal var previewView: NormalizedView? by mutableStateOf(null)

    /** 현재 콘텐츠(디코딩 기준) 크기. 아직 측정 전이면 null */
    internal val contentSize: Size? by derivedStateOf {
        val t = zoomable.contentTransformation
        if (!t.isSpecified) return@derivedStateOf null
        val rect = with(zoomable.coordinateSystem) {
            contentBounds(clipToViewport = false).rectIn(CoordinateSpace.ZoomableContent)
        }
        if (rect.width > 0f && rect.height > 0f) rect.size else null
    }

    /** 확대·이동을 적용할 준비가 됐는지(사진 크기와 화면 크기를 앎) */
    val isReady: Boolean get() = contentSize != null

    /** [전체 보기 ↔ 100%] 버튼을 누를 수 있는지 */
    val canToggleZoom: Boolean get() = previewMode || isReady

    /** 전체 보기 대비 배율 */
    val userZoom: Float by derivedStateOf {
        val t = zoomable.contentTransformation
        if (t.isSpecified) t.scaleMetadata.userZoom else 1f
    }

    val isZoomedIn: Boolean
        get() = if (previewMode) previewView?.isZoomedIn == true else userZoom > ZoomMath.ZOOMED_IN_THRESHOLD

    /** 고해상도 타일까지 모두 그려졌는지 */
    val isFullQuality: Boolean get() = image.isImageDisplayedInFullQuality

    val isDisplayed: Boolean get() = image.isImageDisplayed

    private val fitScale: Float
        get() {
            val t = zoomable.contentTransformation
            return if (t.isSpecified) max(t.scaleMetadata.initialScale.scaleX, t.scaleMetadata.initialScale.scaleY) else 1f
        }

    /** 지금 보이는 상태를 크기와 무관한 값으로 */
    fun currentView(): NormalizedView? {
        val size = contentSize ?: return null
        val viewport = zoomable.coordinateSystem.viewportSize
        if (viewport.width <= 0f || viewport.height <= 0f) return null
        val center = with(zoomable.coordinateSystem) {
            SpatialOffset(viewport.center, CoordinateSpace.Viewport).offsetIn(CoordinateSpace.ZoomableContent)
        }
        if (!center.isSpecified) return null
        return NormalizedView(
            userZoom = userZoom,
            centerX = (center.x / size.width).coerceIn(0f, 1f),
            centerY = (center.y / size.height).coerceIn(0f, 1f),
        )
    }

    /** 원본 100% 확대 상태(화면 가운데 = [centerX], [centerY]) */
    fun originalView(centerX: Float, centerY: Float): NormalizedView? {
        val size = contentSize
            ?: return if (previewMode) NormalizedView(INSPECTION_ZOOM, centerX, centerY) else null
        val fit = fitScale
        val original = ZoomMath.originalFinalZoom(card.originalMaxPx(), max(size.width, size.height))
        val target = ZoomMath.originalTargetFinalZoom(original, fit)
        return NormalizedView(userZoom = target / fit, centerX = centerX, centerY = centerY)
    }

    /** 가장 선명한 위치를 100%로 */
    fun focusView(): NormalizedView? =
        originalView(ZoomMath.focusOrCenter(card.focusX), ZoomMath.focusOrCenter(card.focusY))

    suspend fun awaitReady() {
        if (!isReady) snapshotFlow { isReady }.first { it }
    }

    /** [view]를 적용한다. 기본은 애니메이션 없이 즉시(같은 위치 비교용) */
    suspend fun applyView(view: NormalizedView, animationSpec: AnimationSpec<Float> = snap()) {
        awaitReady()
        val size = contentSize ?: return
        if (!view.isZoomedIn) {
            zoomable.resetZoom(animationSpec)
            return
        }
        val point = Offset(view.centerX.coerceIn(0f, 1f) * size.width, view.centerY.coerceIn(0f, 1f) * size.height)
        zoomable.zoomTo(
            zoomFactor = view.userZoom * fitScale,
            focal = ZoomFocalPoint.moveToViewportCenter(SpatialOffset(point, CoordinateSpace.ZoomableContent)),
            animationSpec = animationSpec,
        )
    }

    suspend fun zoomToFit(animate: Boolean = true) {
        awaitReady()
        if (animate) zoomable.resetZoom() else zoomable.resetZoom(snap())
    }

    /** 100%로 확대. 위치를 주지 않으면 가장 선명한 위치(없으면 가운데) */
    suspend fun zoomToOriginal(centerX: Float? = null, centerY: Float? = null, animate: Boolean = true) {
        awaitReady()
        val view = if (centerX != null && centerY != null) originalView(centerX, centerY) else focusView()
        view ?: return
        applyView(view, if (animate) ZoomableState.DefaultZoomAnimationSpec else snap())
    }

    /** 전체 보기 ↔ 100% */
    suspend fun toggleZoom() {
        if (isZoomedIn) zoomToFit() else zoomToOriginal()
    }

    /** 더블 탭: 확대돼 있으면 전체 보기, 아니면 누른 곳을 100%로 */
    internal suspend fun onDoubleTap(centroid: Offset) {
        if (!isReady) return
        if (isZoomedIn) {
            zoomable.resetZoom()
            return
        }
        val size = contentSize ?: return
        val original = ZoomMath.originalFinalZoom(card.originalMaxPx(), max(size.width, size.height))
        zoomable.zoomTo(zoomFactor = ZoomMath.originalTargetFinalZoom(original, fitScale), centroid = centroid)
    }

    /**
     * 콘텐츠 크기가 바뀌면(예: 미리보기 비트맵 → 원본 영역 디코더) telephoto는 이동량을 다시 계산하지 않아
     * 보던 위치가 어긋난다. 바뀌기 직전의 상태를 새 크기에 다시 적용해 위치를 유지한다.
     * 화면에 있는 동안 계속 돌아야 한다.
     */
    internal suspend fun retainViewAcrossContentChanges(
        appliedSize: Size? = null,
        appliedView: NormalizedView? = null,
    ) {
        // 처음 적용한 크기·상태에서 시작: 적용 직후 바로 크기가 바뀐 경우에도 그 상태를 다시 적용한다
        var lastSize: Size? = appliedSize
        var lastView: NormalizedView? = appliedView
        snapshotFlow { contentSize to currentView() }.collect { (size, view) ->
            if (size == null) return@collect
            val previous = lastSize
            val keep = lastView
            if (previous != null && keep != null &&
                !ZoomMath.sameSize(previous.width, previous.height, size.width, size.height) &&
                ZoomMath.sameAspect(previous.width, previous.height, size.width, size.height)
            ) {
                lastSize = size
                applyView(keep)
            } else {
                lastSize = size
                if (view != null) lastView = view
            }
        }
    }
}

/** 최대 확대를 '원본의 2배'로 맞추는 확대 범위(디코딩 크기가 바뀌어도 같은 기준) */
@Immutable
private data class PhotoZoomSpec(val originalMaxPx: Float?) : DynamicZoomSpec {
    override fun DynamicZoomSpecScope.compute(inputs: DynamicZoomSpecInputs): ZoomSpec {
        val contentMax = inputs.unscaledContentSize.maxDimension
        val fit = if (contentMax > 0f) inputs.scaledContentBounds.size.maxDimension / contentMax else 1f
        val original = ZoomMath.originalFinalZoom(originalMaxPx, contentMax)
        return ZoomSpec(maxZoomFactor = ZoomMath.maxFinalZoom(original, fit))
    }
}

private class PhotoDoubleTap(private val state: PhotoZoomState) : DoubleClickToZoomListener {
    override suspend fun onDoubleClick(state: ZoomableState, centroid: Offset) {
        this.state.onDoubleTap(centroid)
    }
}

@Composable
fun rememberPhotoZoomState(card: MediaCard): PhotoZoomState {
    val spec = remember(card.originalMaxPx()) { PhotoZoomSpec(card.originalMaxPx()) }
    val zoomable = rememberZoomableState(zoomSpec = spec)
    val image = rememberZoomableImageState(zoomable)
    val inspection = LocalInspectionMode.current
    val state = remember(zoomable, image) { PhotoZoomState(card, zoomable, image).also { it.previewMode = inspection } }
    SideEffect { state.card = card }
    return state
}

/**
 * 확대 가능한 사진. 큰 JPEG는 원본 파일에서 보이는 영역만 디코딩한다(24MP도 메모리 안전).
 * - [initialView]: 처음 준비됐을 때 적용할 상태(null이면 전체 보기). 페이지마다 한 번만 적용
 * - [onTap]: 한 번 탭(뷰어 도구 표시/숨김 등)
 */
@Composable
fun ZoomablePhoto(
    state: PhotoZoomState,
    modifier: Modifier = Modifier,
    initialView: (PhotoZoomState) -> NormalizedView? = { null },
    gesturesEnabled: Boolean = true,
    crossfade: Boolean = true,
    onTap: (() -> Unit)? = null,
    onInitialViewApplied: () -> Unit = {},
) {
    val card = state.card
    if (LocalInspectionMode.current) {
        val view = initialView(state)
        SideEffect { state.previewView = view }
        InspectionPhoto(card, view, modifier)
        return
    }
    var initialApplied by rememberSaveable(card.id) { mutableStateOf(false) }
    LaunchedEffect(state) {
        var appliedSize: Size? = null
        var appliedView: NormalizedView? = null
        if (!initialApplied) {
            state.awaitReady()
            appliedSize = state.contentSize
            appliedView = initialView(state)
            appliedView?.let { state.applyView(it) }
            initialApplied = true
        }
        onInitialViewApplied()
        state.retainViewAcrossContentChanges(appliedSize, appliedView)
    }

    LaunchedEffect(card.path) {
        state.isMissing = withContext(Dispatchers.IO) { runCatching { !File(card.path).isFile }.getOrDefault(true) }
    }
    if (state.isMissing) {
        ViewerMessage(
            title = stringResource(R.string.viewer_image_error_title),
            body = stringResource(R.string.viewer_image_error_body),
            modifier = modifier,
        )
        return
    }
    // 고해상도 타일이 늦으면(큰 사진 첫 디코딩) 잠시 뒤 진행 표시
    var slow by remember(card.path) { mutableStateOf(false) }
    val fullQuality = state.isFullQuality
    LaunchedEffect(fullQuality) {
        slow = false
        if (!fullQuality) {
            delay(SLOW_LOAD_MS)
            slow = true
        }
    }

    val context = LocalContext.current
    val useDirectFile = remember(card.path, card.mimeType) { card.isJpeg() }
    val coilModel = remember(card.path, crossfade) {
        ImageRequest.Builder(context).data(File(card.path)).crossfade(crossfade).build()
    }
    val source: ZoomableImageSource = if (useDirectFile) {
        remember(card.path) { DirectFileImageSource(card.path) }
    } else {
        ZoomableImageSource.coil(coilModel)
    }
    val doubleTap = remember(state) { PhotoDoubleTap(state) }

    Box(modifier.clipToBounds()) {
        // 원본 타일이 준비되기 전에는 화면 크기 썸네일을 같은 자리에(전체 보기) 보여준다.
        if (useDirectFile && !state.isFullQuality && !state.isZoomedIn) {
            AsyncImage(
                model = coilModel,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        ZoomableImage(
            image = source,
            contentDescription = card.fileName,
            gestures = if (gesturesEnabled) EnabledZoomGestures.ZoomAndPan else EnabledZoomGestures.None,
            state = state.image,
            onClick = onTap?.let { tap -> { _: Offset -> tap() } },
            onDoubleClick = doubleTap,
            modifier = Modifier.fillMaxSize(),
        )
        if (slow && !fullQuality) {
            CircularProgressIndicator(
                color = ViewerColors.onScrim,
                trackColor = ViewerColors.scrim,
                modifier = Modifier.align(Alignment.Center).size(Spacing.iconLarge),
            )
        }
    }
}

/** JPEG 원본 파일을 미리보기 없이 바로 영역 디코딩. 콘텐츠 크기가 처음부터 원본이라 확대 위치가 흔들리지 않는다. */
@Immutable
private data class DirectFileImageSource(val path: String) : ZoomableImageSource {
    @Composable
    override fun resolve(canvasSize: Flow<Size>): ZoomableImageSource.ResolveResult {
        var result by remember(path) { mutableStateOf(ZoomableImageSource.ResolveResult(delegate = null)) }
        LaunchedEffect(path) {
            val exists = withContext(Dispatchers.IO) { runCatching { File(path).isFile }.getOrDefault(false) }
            if (exists) {
                result = ZoomableImageSource.ResolveResult(
                    delegate = ZoomableImageSource.SubSamplingDelegate(SubSamplingImageSource.file(path.toPath())),
                )
            }
        }
        return result
    }
}

/** 스크린샷·미리보기용: 파일 대신 가짜 사진을 같은 배치로 그린다. */
@Composable
private fun InspectionPhoto(card: MediaCard, view: NormalizedView?, modifier: Modifier) {
    val scale = if (view != null && view.isZoomedIn) INSPECTION_ZOOM else 1f
    val cx = view?.centerX ?: 0.5f
    val cy = view?.centerY ?: 0.5f
    Box(modifier.clipToBounds()) {
        AsyncImage(
            model = File(card.path),
            contentDescription = card.fileName,
            contentScale = if (scale > 1f) ContentScale.Crop else ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = (0.5f - cx) * size.width * scale
                    translationY = (0.5f - cy) * size.height * scale
                },
        )
    }
}

private const val INSPECTION_ZOOM = 3f
private const val SLOW_LOAD_MS = 600L
