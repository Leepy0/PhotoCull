package com.leepy0.photocull.ui.viewer

import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.ui.review.photo
import com.leepy0.photocull.ui.review.video
import org.junit.Test

class ViewerLogicTest {

    @Test fun originalZoomIsRelativeToDecodedContent() {
        // 원본 6000px을 그대로 디코딩: 100% = 배율 1
        assertThat(ZoomMath.originalFinalZoom(6000f, 6000f)).isEqualTo(1f)
        // 미리보기(1500px)가 콘텐츠일 때 100%는 4배
        assertThat(ZoomMath.originalFinalZoom(6000f, 1500f)).isEqualTo(4f)
        // 원본 크기를 모르면 콘텐츠가 원본이라고 본다
        assertThat(ZoomMath.originalFinalZoom(null, 1500f)).isEqualTo(1f)
    }

    @Test fun maxZoomReachesTwiceOriginal() {
        // 6000px 사진을 1080px 화면에 맞춤(0.18배) → 최대는 원본의 2배
        assertThat(ZoomMath.maxFinalZoom(originalFinalZoom = 1f, fitScale = 0.18f)).isEqualTo(2f)
        // 화면보다 작은 사진은 화면 맞춤의 2배까지
        assertThat(ZoomMath.maxFinalZoom(originalFinalZoom = 1f, fitScale = 3f)).isEqualTo(6f)
    }

    @Test fun originalTargetFallsBackForSmallImages() {
        assertThat(ZoomMath.originalTargetFinalZoom(originalFinalZoom = 1f, fitScale = 0.18f)).isEqualTo(1f)
        assertThat(ZoomMath.originalTargetFinalZoom(originalFinalZoom = 1f, fitScale = 2f)).isEqualTo(4f)
    }

    @Test fun sizeAndAspectComparisons() {
        assertThat(ZoomMath.sameSize(6000f, 4000f, 6000f, 4000f)).isTrue()
        assertThat(ZoomMath.sameSize(1500f, 1000f, 6000f, 4000f)).isFalse()
        assertThat(ZoomMath.sameAspect(1500f, 1000f, 6000f, 4000f)).isTrue()
        assertThat(ZoomMath.sameAspect(4000f, 6000f, 6000f, 4000f)).isFalse()
    }

    @Test fun compareKeepsSamePixelZoomAcrossResolutions() {
        // 6000px 장을 100%(원본 대비 1)로 보던 중 3000px 장으로: 그 장도 원본 100%
        val shared = SharedView(originalZoom = 1f, centerX = 0.3f, centerY = 0.6f, aspect = 1.5f)
        // 3000px 원본을 그대로 디코딩(원본 100% = 최종 배율 1), 화면 맞춤 0.36
        val view = ZoomMath.transfer(shared, targetAspect = 1.5f, focusX = 0.9f, focusY = 0.9f, fitScale = 0.36f, originalFinalZoom = 1f)
        assertThat(view.userZoom).isWithin(1e-4f).of(1f / 0.36f)
        // 가로/세로 비가 같으면 같은 위치
        assertThat(view.centerX).isEqualTo(0.3f)
        assertThat(view.centerY).isEqualTo(0.6f)
    }

    @Test fun compareDoesNotMovePositionAcrossOrientations() {
        val shared = SharedView(originalZoom = 1f, centerX = 0.1f, centerY = 0.2f, aspect = 1.5f)
        // 세로 사진: 같은 비율 위치는 다른 곳을 가리키므로 그 장의 가장 선명한 곳으로
        val view = ZoomMath.transfer(shared, targetAspect = 2f / 3f, focusX = 0.7f, focusY = 0.4f, fitScale = 0.18f, originalFinalZoom = 1f)
        assertThat(view.centerX).isEqualTo(0.7f)
        assertThat(view.centerY).isEqualTo(0.4f)
        assertThat(view.isZoomedIn).isTrue()
    }

    @Test fun compareFitStaysFitAndTinyFramesStillZoomIn() {
        val fit = SharedView(originalZoom = null, centerX = 0.2f, centerY = 0.2f, aspect = 1.5f)
        assertThat(ZoomMath.transfer(fit, 1.5f, 0.5f, 0.5f, fitScale = 0.18f, originalFinalZoom = 1f).isZoomedIn).isFalse()
        // 화면보다 작은 장은 원본 100%가 전체 보기보다 작다 → 화면 맞춤의 2배
        val zoomed = SharedView(originalZoom = 1f, centerX = 0.5f, centerY = 0.5f, aspect = 1.5f)
        val small = ZoomMath.transfer(zoomed, 1.5f, 0.5f, 0.5f, fitScale = 2f, originalFinalZoom = 1f)
        assertThat(small.userZoom).isEqualTo(ZoomMath.MIN_STEP_OF_FIT)
    }

    @Test fun originalZoomRoundTrip() {
        // 미리보기(1500px)가 콘텐츠: 원본 100% = 최종 배율 4. 화면 맞춤 0.72에서 userZoom 5.555 = 최종 4 = 원본 100%
        val userZoom = 4f / 0.72f
        assertThat(ZoomMath.toOriginalZoom(userZoom, fitScale = 0.72f, originalFinalZoom = 4f)).isWithin(1e-4f).of(1f)
    }

    @Test fun focusFallsBackToCenter() {
        assertThat(ZoomMath.focusOrCenter(null)).isEqualTo(0.5f)
        assertThat(ZoomMath.focusOrCenter(Float.NaN)).isEqualTo(0.5f)
        assertThat(ZoomMath.focusOrCenter(1.4f)).isEqualTo(0.5f)
        assertThat(ZoomMath.focusOrCenter(0.25f)).isEqualTo(0.25f)
    }

    @Test fun normalizedViewZoomedInThreshold() {
        assertThat(NormalizedView(1f, 0.5f, 0.5f).isZoomedIn).isFalse()
        assertThat(NormalizedView(1.04f, 0.5f, 0.5f).isZoomedIn).isFalse()
        assertThat(NormalizedView(5.5f, 0.3f, 0.6f).isZoomedIn).isTrue()
    }

    @Test fun originalSizeAndJpegDetection() {
        assertThat(photo(1).originalMaxPx()).isEqualTo(6000f)
        assertThat(photo(1).copy(width = null).originalMaxPx()).isNull()
        assertThat(photo(1).isJpeg()).isTrue()
        assertThat(photo(1).copy(mimeType = "image/heif", fileName = "IMG_1.HIF", path = "/x/IMG_1.HIF").isJpeg()).isFalse()
        assertThat(photo(1).copy(mimeType = "", fileName = "IMG_1.jpeg").isJpeg()).isTrue()
        assertThat(video(1, 3000, 10).isJpeg()).isFalse()
    }

    @Test fun exifLineFormatsCommonValues() {
        assertThat(ExifFormat.line(photo(1))).isEqualTo("ISO 800 · 1/250 · f/5.6 · 135mm")
        assertThat(ExifFormat.line(video(1, 3000, 10))).isNull()
    }

    @Test fun exifPartsAcceptManyRawForms() {
        assertThat(ExifFormat.exposure("1/250")).isEqualTo("1/250")
        assertThat(ExifFormat.exposure("0.0005")).isEqualTo("1/2000")
        assertThat(ExifFormat.exposure("2")).isEqualTo("2s")
        assertThat(ExifFormat.exposure("1.5")).isEqualTo("1.5s")
        assertThat(ExifFormat.exposure("0")).isNull()
        assertThat(ExifFormat.aperture("f/2.8")).isEqualTo("f/2.8")
        assertThat(ExifFormat.aperture("8.0")).isEqualTo("f/8")
        assertThat(ExifFormat.aperture("F4")).isEqualTo("f/4")
        assertThat(ExifFormat.focalLength("50.0")).isEqualTo("50mm")
        assertThat(ExifFormat.focalLength("18 mm")).isEqualTo("18mm")
        assertThat(ExifFormat.focalLength("")).isNull()
    }
}
