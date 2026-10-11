package com.leepy0.photocull.analysis

import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.data.settings.BlurSensitivity
import org.junit.Test

class BlurThresholdsTest {

    @Test
    fun higherSensitivity_meansHigherThreshold() {
        val low = BlurThresholds.threshold(BlurSensitivity.LOW)
        val normal = BlurThresholds.threshold(BlurSensitivity.NORMAL)
        val high = BlurThresholds.threshold(BlurSensitivity.HIGH)
        assertThat(low).isGreaterThan(0.0)
        assertThat(normal).isGreaterThan(low)
        assertThat(high).isGreaterThan(normal)
    }

    @Test
    fun groupRatio_isAFraction() {
        assertThat(BlurThresholds.GROUP_RELATIVE_RATIO).isGreaterThan(0.0)
        assertThat(BlurThresholds.GROUP_RELATIVE_RATIO).isLessThan(1.0)
    }

    @Test
    fun syntheticBlurLadder_isOrderedAgainstThresholds() {
        // 합성 질감으로도 경향이 맞는지: 선명 > 보통 임계값, 아주 흐림 < 낮음 임계값
        val w = 1200
        val h = 800
        val sharp = TestImages.blobs(w, h, seed = 4).also { img ->
            val tex = TestImages.noiseTexture(w, h, seed = 5, lo = 60, hi = 200)
            for (i in img.indices) img[i] = (((img[i].toInt() and 0xFF) + (tex[i].toInt() and 0xFF)) / 2).toByte()
        }
        val sharpScore = Sharpness.compute(sharp, w, h).score
        val veryBlurred = Sharpness.compute(TestImages.boxBlur(sharp, w, h, 6), w, h).score
        assertThat(sharpScore).isGreaterThan(BlurThresholds.threshold(BlurSensitivity.NORMAL))
        assertThat(veryBlurred).isLessThan(BlurThresholds.threshold(BlurSensitivity.LOW))
    }
}
