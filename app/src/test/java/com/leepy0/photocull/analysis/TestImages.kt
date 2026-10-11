package com.leepy0.photocull.analysis

import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.random.Random

/** 분석 테스트용 합성 그레이 영상(부호 없는 8비트, 행 우선) */
internal object TestImages {

    fun flat(w: Int, h: Int, value: Int = 128): ByteArray = ByteArray(w * h) { value.toByte() }

    /** 한 픽셀 단위 0/255 체커보드: Laplacian = ±1020이 모든 내부 픽셀에서 나온다. */
    fun pixelCheckerboard(w: Int, h: Int): ByteArray =
        ByteArray(w * h) { i -> if (((i % w) + (i / w)) % 2 == 0) 0 else 255.toByte() }

    /** 크기가 [cell]인 체커보드 */
    fun checkerboard(w: Int, h: Int, cell: Int, lo: Int = 40, hi: Int = 220): ByteArray =
        ByteArray(w * h) { i -> (if (((i % w) / cell + (i / w) / cell) % 2 == 0) lo else hi).toByte() }

    /** 고른 무작위 질감(값 범위 [lo, hi]) */
    fun noiseTexture(w: Int, h: Int, seed: Int, lo: Int = 30, hi: Int = 225): ByteArray {
        val r = Random(seed)
        return ByteArray(w * h) { (lo + r.nextInt(hi - lo + 1)).toByte() }
    }

    /** 부드러운 무작위 장면: 큰 가우시안 덩어리 몇 개의 합(dHash 비교용) */
    fun blobs(w: Int, h: Int, seed: Int, count: Int = 6): ByteArray {
        val r = Random(seed)
        val cx = DoubleArray(count) { r.nextDouble() * w }
        val cy = DoubleArray(count) { r.nextDouble() * h }
        val rad = DoubleArray(count) { (0.1 + r.nextDouble() * 0.3) * minOf(w, h) }
        val amp = DoubleArray(count) { (if (r.nextBoolean()) 1 else -1) * (40 + r.nextDouble() * 60) }
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var v = 128.0
            for (k in 0 until count) {
                val dx = x - cx[k]
                val dy = y - cy[k]
                v += amp[k] * exp(-(dx * dx + dy * dy) / (2 * rad[k] * rad[k]))
            }
            out[y * w + x] = v.roundToInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    /** 분리형 박스 블러(반지름 r) — 흐린 사진 흉내 */
    fun boxBlur(src: ByteArray, w: Int, h: Int, radius: Int): ByteArray {
        if (radius <= 0) return src.copyOf()
        val tmp = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0
            var n = 0
            for (k in -radius..radius) {
                val xx = x + k
                if (xx in 0 until w) { s += src[y * w + xx].toInt() and 0xFF; n++ }
            }
            tmp[y * w + x] = s / n
        }
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0
            var n = 0
            for (k in -radius..radius) {
                val yy = y + k
                if (yy in 0 until h) { s += tmp[yy * w + x]; n++ }
            }
            out[y * w + x] = (s / n).toByte()
        }
        return out
    }

    /** [src] 위에 (x0, y0)부터 패치를 덮어쓴다 */
    fun paste(dst: ByteArray, w: Int, patch: ByteArray, pw: Int, ph: Int, x0: Int, y0: Int) {
        for (y in 0 until ph) for (x in 0 until pw) dst[(y0 + y) * w + x0 + x] = patch[y * pw + x]
    }

    fun brighten(src: ByteArray, delta: Int): ByteArray =
        ByteArray(src.size) { i -> ((src[i].toInt() and 0xFF) + delta).coerceIn(0, 255).toByte() }
}
