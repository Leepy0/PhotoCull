package com.leepy0.photocull.analysis

/**
 * 선명도 계산 결과.
 * - [score]: 타일별 Laplacian 분산 중 상위 [Sharpness.TOP_TILES]개의 평균. 클수록 선명하다.
 * - [tileVariances]: 행 우선(row-major) 순서, 크기 = [cols] × [rows]
 * - [focusX]/[focusY]: 가장 선명한 타일의 중심(0..1, 입력 영상 좌표. 회전 반영 전)
 */
class SharpnessResult(
    val score: Double,
    val cols: Int,
    val rows: Int,
    val tileVariances: DoubleArray,
    val bestTile: Int,
    val focusX: Float,
    val focusY: Float,
) {
    val bestCol: Int get() = bestTile % cols
    val bestRow: Int get() = bestTile / cols

    override fun toString(): String =
        "SharpnessResult(score=$score, grid=${cols}x$rows, best=($bestCol,$bestRow), focus=($focusX,$focusY))"
}

/**
 * 3×3 Laplacian `[0,1,0; 1,-4,1; 0,1,0]` 응답의 분산을 타일별로 구한다(DESIGN.md §4.2).
 *
 * - 가로(또는 정사각) 영상은 6×4, 세로 영상은 4×6 타일. 가장자리 1px은 커널이 영상 밖으로 나가므로 제외한다.
 * - 점수 = 분산 상위 3개 타일의 평균: 피사체만 선명하고 배경은 흐린(아웃포커스) 사진도 선명하게 본다.
 * - 한 번의 순회로 계산하고 픽셀마다 객체를 만들지 않는다(3000×2000 기준 JVM에서 수십 ms).
 */
object Sharpness {
    const val LANDSCAPE_COLS = 6
    const val LANDSCAPE_ROWS = 4
    const val TOP_TILES = 3

    /** 가로 영상이면 (6, 4), 세로 영상이면 (4, 6) */
    fun grid(width: Int, height: Int): Pair<Int, Int> =
        if (width >= height) LANDSCAPE_COLS to LANDSCAPE_ROWS else LANDSCAPE_ROWS to LANDSCAPE_COLS

    /**
     * @param gray 부호 없는 8비트 휘도(행 우선, 길이 ≥ width×height)
     */
    fun compute(gray: ByteArray, width: Int, height: Int): SharpnessResult {
        require(width > 0 && height > 0) { "invalid size ${width}x$height" }
        require(gray.size >= width * height) { "gray buffer too small: ${gray.size} < ${width * height}" }
        val (cols, rows) = grid(width, height)
        val tiles = cols * rows
        val sum = LongArray(tiles)
        val sumSq = LongArray(tiles)
        val count = LongArray(tiles)

        // 타일 열 경계: 열 c는 x ∈ [xStart[c], xStart[c+1]) — 가장자리 1px 제외 구간과 교차시킨다.
        val xStart = IntArray(cols + 1) { c -> (c.toLong() * width / cols).toInt() }
        val segFrom = IntArray(cols) { c -> maxOf(xStart[c], 1) }
        val segTo = IntArray(cols) { c -> minOf(xStart[c + 1], width - 1) }

        for (y in 1 until height - 1) {
            val tileRowBase = ((y.toLong() * rows) / height).toInt() * cols
            val mid = y * width
            val up = mid - width
            val down = mid + width
            for (c in 0 until cols) {
                val from = segFrom[c]
                val to = segTo[c]
                if (from >= to) continue
                var s = 0L
                var sq = 0L
                var left = gray[mid + from - 1].toInt() and 0xFF
                var center = gray[mid + from].toInt() and 0xFF
                var x = from
                while (x < to) {
                    val right = gray[mid + x + 1].toInt() and 0xFF
                    val lap = (gray[up + x].toInt() and 0xFF) + (gray[down + x].toInt() and 0xFF) +
                        left + right - 4 * center
                    s += lap
                    sq += (lap * lap).toLong()
                    left = center
                    center = right
                    x++
                }
                val t = tileRowBase + c
                sum[t] += s
                sumSq[t] += sq
                count[t] += (to - from).toLong()
            }
        }

        val variances = DoubleArray(tiles) { t ->
            val n = count[t]
            if (n == 0L) 0.0 else {
                val mean = sum[t].toDouble() / n
                (sumSq[t].toDouble() / n - mean * mean).coerceAtLeast(0.0)
            }
        }

        var best = 0
        for (t in 1 until tiles) if (variances[t] > variances[best]) best = t
        val top = variances.sortedDescending()
        val k = minOf(TOP_TILES, top.size)
        val score = if (k == 0) 0.0 else top.take(k).sum() / k

        val bestCol = best % cols
        val bestRow = best / cols
        return SharpnessResult(
            score = score,
            cols = cols,
            rows = rows,
            tileVariances = variances,
            bestTile = best,
            focusX = ((bestCol + 0.5) / cols).toFloat(),
            focusY = ((bestRow + 0.5) / rows).toFloat(),
        )
    }
}
