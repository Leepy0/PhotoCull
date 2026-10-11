package com.leepy0.photocull.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SharpnessTest {

    @Test
    fun pixelCheckerboard_hasExactLaplacianVariance() {
        val r = Sharpness.compute(TestImages.pixelCheckerboard(120, 80), 120, 80)
        // 내부 픽셀마다 Laplacian = ±1020 → 분산 ≈ 1020²(타일 안 +/- 개수가 홀수면 평균이 0에서 아주 조금 벗어난다)
        val expected = 1020.0 * 1020.0
        assertThat(r.score).isWithin(expected * 1e-4).of(expected)
        r.tileVariances.forEach { assertThat(it).isWithin(expected * 1e-4).of(expected) }
    }

    @Test
    fun sharpTexture_scoresHigherThanBlurredVersions() {
        val w = 600
        val h = 400
        val sharp = TestImages.checkerboard(w, h, cell = 3)
        val s0 = Sharpness.compute(sharp, w, h).score
        val s1 = Sharpness.compute(TestImages.boxBlur(sharp, w, h, 1), w, h).score
        val s3 = Sharpness.compute(TestImages.boxBlur(sharp, w, h, 3), w, h).score
        assertThat(s0).isGreaterThan(s1)
        assertThat(s1).isGreaterThan(s3)
        assertThat(s3).isLessThan(s0 * 0.1)

        val noise = TestImages.noiseTexture(w, h, seed = 1)
        val n0 = Sharpness.compute(noise, w, h).score
        val n2 = Sharpness.compute(TestImages.boxBlur(noise, w, h, 2), w, h).score
        assertThat(n0).isGreaterThan(n2 * 10)
    }

    @Test
    fun flatImage_scoresZero() {
        val r = Sharpness.compute(TestImages.flat(300, 200, 77), 300, 200)
        assertThat(r.score).isWithin(1e-9).of(0.0)
        r.tileVariances.forEach { assertThat(it).isWithin(1e-9).of(0.0) }
    }

    @Test
    fun smoothGradient_scoresNearZero() {
        val w = 300
        val h = 200
        val g = ByteArray(w * h) { i -> ((i % w) * 255 / (w - 1)).toByte() }
        // 1차 함수의 Laplacian은 0(정수 반올림 잔차만 남는다)
        assertThat(Sharpness.compute(g, w, h).score).isLessThan(1.0)
    }

    @Test
    fun gridIsSixByFourForLandscape_fourBySixForPortrait() {
        val land = Sharpness.compute(TestImages.flat(60, 40), 60, 40)
        assertThat(land.cols).isEqualTo(6)
        assertThat(land.rows).isEqualTo(4)
        assertThat(land.tileVariances.size).isEqualTo(24)

        val port = Sharpness.compute(TestImages.flat(40, 60), 40, 60)
        assertThat(port.cols).isEqualTo(4)
        assertThat(port.rows).isEqualTo(6)

        val square = Sharpness.compute(TestImages.flat(50, 50), 50, 50)
        assertThat(square.cols).isEqualTo(6)
        assertThat(square.rows).isEqualTo(4)
    }

    @Test
    fun focusPointsAtTileWithDetail_landscape() {
        val w = 600
        val h = 400 // 타일 100×100
        val img = TestImages.flat(w, h)
        val patch = TestImages.checkerboard(60, 60, cell = 2)
        TestImages.paste(img, w, patch, 60, 60, x0 = 420, y0 = 120) // 열 4, 행 1
        val r = Sharpness.compute(img, w, h)
        assertThat(r.bestCol).isEqualTo(4)
        assertThat(r.bestRow).isEqualTo(1)
        assertThat(r.focusX.toDouble()).isWithin(1e-6).of(4.5 / 6)
        assertThat(r.focusY.toDouble()).isWithin(1e-6).of(1.5 / 4)
        // 디테일이 한 타일뿐이면 상위 3개 평균 = 그 타일 분산 / 3
        assertThat(r.score).isWithin(1e-6).of(r.tileVariances[r.bestTile] / 3)
    }

    @Test
    fun focusPointsAtTileWithDetail_portrait() {
        val w = 400
        val h = 600 // 4×6, 타일 100×100
        val img = TestImages.flat(w, h)
        TestImages.paste(img, w, TestImages.checkerboard(50, 50, cell = 2), 50, 50, x0 = 20, y0 = 520) // 열 0, 행 5
        val r = Sharpness.compute(img, w, h)
        assertThat(r.bestCol).isEqualTo(0)
        assertThat(r.bestRow).isEqualTo(5)
        assertThat(r.focusX.toDouble()).isWithin(1e-6).of(0.5 / 4)
        assertThat(r.focusY.toDouble()).isWithin(1e-6).of(5.5 / 6)
    }

    @Test
    fun subjectSharpBackgroundBlurred_staysSharp() {
        // 아웃포커스 배경 + 선명한 피사체(3개 타일): 상위 3개 평균이라 배경에 끌려 내려가지 않는다.
        val w = 600
        val h = 400
        val img = TestImages.boxBlur(TestImages.noiseTexture(w, h, seed = 3), w, h, 4)
        val sharpOnly = TestImages.noiseTexture(300, 100, seed = 4)
        TestImages.paste(img, w, sharpOnly, 300, 100, x0 = 0, y0 = 100) // 행 1의 열 0..2
        val r = Sharpness.compute(img, w, h)
        val sharpFull = Sharpness.compute(TestImages.noiseTexture(w, h, seed = 4), w, h).score
        assertThat(r.score).isGreaterThan(sharpFull * 0.8)
    }

    @Test
    fun excludesOnePixelBorder() {
        // 3×3 영상에서 커널 중심이 될 수 있는 픽셀은 가운데 하나뿐 → 표본 1개라 분산 0.
        // 가장자리를 (복제·0 채움 등으로) 포함했다면 값이 섞여 분산이 0보다 커진다.
        val img = ByteArray(9).also { it[4] = 255.toByte() }
        val r = Sharpness.compute(img, 3, 3)
        assertThat(r.score).isEqualTo(0.0)
        r.tileVariances.forEach { assertThat(it).isEqualTo(0.0) }
    }

    @Test
    fun tinyImages_doNotCrash() {
        for ((w, h) in listOf(1 to 1, 2 to 2, 3 to 3, 7 to 2, 2 to 9, 10 to 6)) {
            val r = Sharpness.compute(TestImages.noiseTexture(w, h, seed = w * 31 + h), w, h)
            assertThat(r.score).isAtLeast(0.0)
            assertThat(r.focusX).isIn(com.google.common.collect.Range.closed(0f, 1f))
            assertThat(r.focusY).isIn(com.google.common.collect.Range.closed(0f, 1f))
        }
    }

    @Test
    fun throughput_3000x2000() {
        val w = 3000
        val h = 2000
        val img = TestImages.noiseTexture(w, h, seed = 7)
        repeat(5) { Sharpness.compute(img, w, h) } // JIT 예열
        val times = LongArray(9) {
            val t0 = System.nanoTime()
            Sharpness.compute(img, w, h)
            System.nanoTime() - t0
        }
        times.sort()
        val medianMs = times[times.size / 2] / 1e6
        val dhashTimes = LongArray(9) {
            val t0 = System.nanoTime()
            DHash.compute(img, w, h)
            System.nanoTime() - t0
        }
        dhashTimes.sort()
        println("Sharpness 3000x2000: median %.1f ms (min %.1f ms); DHash: median %.1f ms".format(
            medianMs, times[0] / 1e6, dhashTimes[dhashTimes.size / 2] / 1e6,
        ))
        // 목표는 60ms 미만. CPU를 다른 작업과 나눠 쓰는 CI에서도 깨지지 않게 여유 있게 확인한다.
        assertThat(medianMs).isLessThan(400.0)
    }
}
