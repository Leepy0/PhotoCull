package com.leepy0.photocull.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DHashTest {

    @Test
    fun identicalImages_distanceZero() {
        val img = TestImages.blobs(320, 240, seed = 1)
        assertThat(DHash.hamming(DHash.compute(img, 320, 240), DHash.compute(img.copyOf(), 320, 240))).isEqualTo(0)
    }

    @Test
    fun smallBrightnessChange_smallDistance() {
        val w = 320
        val h = 240
        val img = TestImages.blobs(w, h, seed = 2)
        val base = DHash.compute(img, w, h)
        assertThat(DHash.hamming(base, DHash.compute(TestImages.brighten(img, 8), w, h))).isAtMost(3)
        assertThat(DHash.hamming(base, DHash.compute(TestImages.brighten(img, -8), w, h))).isAtMost(3)
    }

    @Test
    fun slightBlur_smallDistance() {
        // 연사에서 흔들린 컷도 같은 장면이면 가깝게 나와야 한다.
        val w = 320
        val h = 240
        val img = TestImages.blobs(w, h, seed = 3)
        val blurred = TestImages.boxBlur(img, w, h, 3)
        assertThat(DHash.hamming(DHash.compute(img, w, h), DHash.compute(blurred, w, h))).isAtMost(4)
    }

    @Test
    fun differentImages_largeDistance() {
        val w = 320
        val h = 240
        val distances = (10..15).map { seed ->
            DHash.hamming(DHash.compute(TestImages.blobs(w, h, seed), w, h), DHash.compute(TestImages.blobs(w, h, seed + 100), w, h))
        }
        // 무작위 장면 둘은 평균적으로 비트 절반(32)쯤 다르다. 같은 장면 변형(≤4)과는 확실히 떨어져 있어야 한다.
        println("dHash distances between unrelated scenes: $distances")
        distances.forEach { assertThat(it).isGreaterThan(6) }
        assertThat(distances.average()).isGreaterThan(18.0)
    }

    @Test
    fun scaleInvariant() {
        // 같은 장면을 다른 해상도로 디코드해도(예: 2배 축소) 거의 같아야 한다.
        val img = TestImages.blobs(640, 480, seed = 5)
        val half = ByteArray(320 * 240) { i ->
            val x = (i % 320) * 2
            val y = (i / 320) * 2
            val s = (img[y * 640 + x].toInt() and 0xFF) + (img[y * 640 + x + 1].toInt() and 0xFF) +
                (img[(y + 1) * 640 + x].toInt() and 0xFF) + (img[(y + 1) * 640 + x + 1].toInt() and 0xFF)
            (s / 4).toByte()
        }
        assertThat(DHash.hamming(DHash.compute(img, 640, 480), DHash.compute(half, 320, 240))).isAtMost(2)
    }

    @Test
    fun knownBitLayout() {
        // 왼쪽이 밝고 오른쪽으로 갈수록 어두운 계단: 모든 비교가 '왼쪽 > 오른쪽' → 64비트 모두 1
        val w = 90
        val h = 80
        val img = ByteArray(w * h) { i -> (250 - (i % w) / 10 * 25).toByte() }
        assertThat(DHash.compute(img, w, h)).isEqualTo(-1L)
        // 반대 방향이면 모두 0
        val rev = ByteArray(w * h) { i -> (25 + (i % w) / 10 * 25).toByte() }
        assertThat(DHash.compute(rev, w, h)).isEqualTo(0L)
    }

    @Test
    fun flatImage_isZero_andTinyImagesDoNotCrash() {
        assertThat(DHash.compute(TestImages.flat(100, 100), 100, 100)).isEqualTo(0L)
        for ((w, h) in listOf(1 to 1, 3 to 2, 9 to 8, 5 to 20)) {
            DHash.compute(TestImages.noiseTexture(w, h, seed = w + h), w, h)
        }
    }

    @Test
    fun hamming_countsDifferingBits() {
        assertThat(DHash.hamming(0L, 0L)).isEqualTo(0)
        assertThat(DHash.hamming(0L, -1L)).isEqualTo(64)
        assertThat(DHash.hamming(0b1011L, 0b0001L)).isEqualTo(2)
        assertThat(DHash.hamming(Long.MIN_VALUE, 0L)).isEqualTo(1)
    }
}
