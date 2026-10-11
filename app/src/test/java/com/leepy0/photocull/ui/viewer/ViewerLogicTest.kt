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
