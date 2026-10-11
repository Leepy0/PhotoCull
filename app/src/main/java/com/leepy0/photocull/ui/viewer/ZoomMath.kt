package com.leepy0.photocull.ui.viewer

import androidx.compose.runtime.Immutable
import com.leepy0.photocull.data.review.MediaCard
import kotlin.math.abs
import kotlin.math.max

/**
 * 사진 크기와 무관한 확대 상태.
 * - [userZoom]: 화면 맞춤(전체 보기) 대비 배율. 1 = 전체 보기
 * - [centerX]/[centerY]: 화면 가운데가 가리키는 사진 위치(0..1, 표시 방향 기준)
 * 같은 해상도의 연사 사진끼리는 이 값만 옮겨 적용하면 같은 부분이 같은 크기로 보인다.
 */
@Immutable
data class NormalizedView(
    val userZoom: Float,
    val centerX: Float,
    val centerY: Float,
) {
    val isZoomedIn: Boolean get() = userZoom > ZoomMath.ZOOMED_IN_THRESHOLD
}

/** 확대 배율 계산(순수 함수, JVM 테스트 대상) */
internal object ZoomMath {
    /** 이보다 크면 '확대된 상태'로 본다(전체 보기 대비) */
    const val ZOOMED_IN_THRESHOLD = 1.05f

    /** 최대 확대 = 원본 100%의 2배(픽셀 확인용) */
    const val MAX_OF_ORIGINAL = 2f

    /** 작은 사진도 최소한 화면 맞춤의 이 배수까지는 확대할 수 있게 */
    const val MIN_STEP_OF_FIT = 2f

    /** 같은 크기로 볼 오차 비율 */
    private const val SIZE_TOLERANCE = 0.01f

    /**
     * 원본 100%(사진 1px = 화면 1px)에 해당하는 최종 배율. 배율은 현재 콘텐츠(디코딩된 크기) 기준이다.
     * 원본 크기를 모르면 콘텐츠가 원본이라고 본다.
     */
    fun originalFinalZoom(originalMaxPx: Float?, contentMaxPx: Float): Float =
        if (originalMaxPx != null && originalMaxPx > 0f && contentMaxPx > 0f) originalMaxPx / contentMaxPx else 1f

    /** 최대 최종 배율: 원본의 2배, 단 화면 맞춤의 2배보다는 크게 */
    fun maxFinalZoom(originalFinalZoom: Float, fitScale: Float): Float =
        max(originalFinalZoom * MAX_OF_ORIGINAL, fitScale * MIN_STEP_OF_FIT)

    /**
     * '100%로 보기'의 목표 최종 배율. 화면보다 작은 사진은 100%가 전체 보기보다 작으므로
     * 화면 맞춤의 2배로 대신 확대한다.
     */
    fun originalTargetFinalZoom(originalFinalZoom: Float, fitScale: Float): Float =
        if (originalFinalZoom < fitScale * ZOOMED_IN_THRESHOLD) fitScale * MIN_STEP_OF_FIT else originalFinalZoom

    fun sameSize(aW: Float, aH: Float, bW: Float, bH: Float): Boolean =
        abs(aW - bW) <= max(aW, bW) * SIZE_TOLERANCE && abs(aH - bH) <= max(aH, bH) * SIZE_TOLERANCE

    fun sameAspect(aW: Float, aH: Float, bW: Float, bH: Float): Boolean {
        if (aH <= 0f || bH <= 0f) return false
        val ra = aW / aH
        val rb = bW / bH
        return abs(ra - rb) <= max(ra, rb) * SIZE_TOLERANCE
    }

    /** 초점 위치(0..1). 없거나 범위를 벗어나면 가운데 */
    fun focusOrCenter(v: Float?): Float = if (v == null || v.isNaN() || v < 0f || v > 1f) 0.5f else v
}

/** 원본 사진의 긴 변(px). 모르면 null */
internal fun MediaCard.originalMaxPx(): Float? {
    val w = width ?: return null
    val h = height ?: return null
    if (w <= 0 || h <= 0) return null
    return max(w, h).toFloat()
}

internal fun MediaCard.isJpeg(): Boolean {
    val mime = mimeType.lowercase()
    if (mime == "image/jpeg" || mime == "image/jpg") return true
    val name = (fileName.ifBlank { path }).lowercase()
    return name.endsWith(".jpg") || name.endsWith(".jpeg")
}
