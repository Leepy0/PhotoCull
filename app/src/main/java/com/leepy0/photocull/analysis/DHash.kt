package com.leepy0.photocull.analysis

/**
 * 64비트 difference hash(DESIGN.md §4.4). 비슷한 구도의 사진을 찾는 데 쓴다.
 *
 * 그레이 영상을 9×8 칸으로 영역 평균 축소한 뒤, 행마다 왼쪽 칸이 오른쪽 칸보다 밝으면 1.
 * 밝기 전체가 조금 바뀌어도 이웃 칸의 대소 관계는 거의 그대로라 거리가 작게 유지된다.
 * 비트 순서: 행 r(0..7), 열 c(0..7)의 비교 결과가 비트 (63 - (r*8 + c)) — 왼쪽 위가 최상위 비트.
 */
object DHash {
    private const val COLS = 9
    private const val ROWS = 8

    fun compute(gray: ByteArray, width: Int, height: Int): Long {
        require(width > 0 && height > 0) { "invalid size ${width}x$height" }
        require(gray.size >= width * height) { "gray buffer too small" }

        // 칸 경계(영역 평균). 영상이 9×8보다 작으면 칸이 비지 않게 최소 1px을 보장한다.
        val xFrom = IntArray(COLS)
        val xTo = IntArray(COLS)
        for (c in 0 until COLS) {
            val a = (c.toLong() * width / COLS).toInt().coerceAtMost(width - 1)
            xFrom[c] = a
            xTo[c] = maxOf(a + 1, ((c + 1).toLong() * width / COLS).toInt())
        }
        val sums = LongArray(COLS * ROWS)
        val counts = LongArray(COLS * ROWS)
        for (r in 0 until ROWS) {
            val y0 = (r.toLong() * height / ROWS).toInt().coerceAtMost(height - 1)
            val y1 = maxOf(y0 + 1, ((r + 1).toLong() * height / ROWS).toInt())
            for (y in y0 until y1) {
                val base = y * width
                for (c in 0 until COLS) {
                    var s = 0L
                    for (x in xFrom[c] until xTo[c]) s += gray[base + x].toInt() and 0xFF
                    sums[r * COLS + c] += s
                    counts[r * COLS + c] += (xTo[c] - xFrom[c]).toLong()
                }
            }
        }

        var hash = 0L
        for (r in 0 until ROWS) {
            for (c in 0 until COLS - 1) {
                val a = r * COLS + c
                val b = a + 1
                // 평균 비교를 정수로: sumA/countA > sumB/countB ⇔ sumA*countB > sumB*countA
                if (sums[a] * counts[b] > sums[b] * counts[a]) {
                    hash = hash or (1L shl (63 - (r * 8 + c)))
                }
            }
        }
        return hash
    }

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)
}
