package com.leepy0.photocull.analysis

import com.leepy0.photocull.data.settings.BlurSensitivity
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 흐림 임계값 보정용 하네스(결과 요약은 BlurThresholds.kt 주석). 평소에는 건너뛴다.
 *
 * 실행:
 *   PHOTOCULL_CALIB_DIR=<사진 폴더> PHOTOCULL_CALIB_OUT=<결과 .md> \
 *     ./gradlew :app:testDebugUnitTest --tests '*CalibrationHarnessTest*'
 *
 * 폴더의 *.jpg를 javax.imageio로 디코드한다. 같은 폴더의 sizes.txt("파일명 원본너비 원본높이")가 있으면
 * 그 파일을 원본의 축소본(예: Wikimedia 3840px 썸네일)으로 보고 원본 화소 기준으로 환산한다:
 * - 열화(가우시안·모션 블러)는 원본 화소 단위 파라미터 × (썸네일/원본) 배율로 적용
 * - 앱 경로를 흉내 내 원본 기준 1/2 크기(6000px → 3000px, inSampleSize=2)로 영역 평균 축소
 * - 노이즈는 축소 뒤에 더한다(원본에서 σ인 백색 잡음은 2×2 평균 뒤 σ/2)
 * 그 뒤 앱과 똑같이 Sharpness.compute로 점수를 낸다.
 */
class CalibrationHarnessTest {

    private data class Condition(
        val key: String,
        val label: String,
        val gaussSigma: Double = 0.0,
        val motionLen: Double = 0.0,
        val noiseSigmaFull: Double = 0.0,
    )

    private val conditions = listOf(
        Condition("orig", "원본"),
        Condition("g1", "가우시안 σ1", gaussSigma = 1.0),
        Condition("g2", "가우시안 σ2", gaussSigma = 2.0),
        Condition("g4", "가우시안 σ4", gaussSigma = 4.0),
        Condition("m5", "모션 5px", motionLen = 5.0),
        Condition("m15", "모션 15px", motionLen = 15.0),
        Condition("n4", "노이즈 σ4", noiseSigmaFull = 4.0),
        Condition("n8", "노이즈 σ8", noiseSigmaFull = 8.0),
        Condition("n16", "노이즈 σ16", noiseSigmaFull = 16.0),
        Condition("g2n8", "σ2 + 노이즈 σ8", gaussSigma = 2.0, noiseSigmaFull = 8.0),
        Condition("g4n8", "σ4 + 노이즈 σ8", gaussSigma = 4.0, noiseSigmaFull = 8.0),
    )

    @Test
    fun calibrate() {
        val dirPath = System.getenv("PHOTOCULL_CALIB_DIR")
        assumeTrue("PHOTOCULL_CALIB_DIR not set", dirPath != null)
        val dir = File(dirPath!!)
        val sizes = File(dir, "sizes.txt").takeIf { it.isFile }?.readLines().orEmpty()
            .mapNotNull { l -> l.trim().split(Regex("\\s+")).takeIf { it.size >= 3 }?.let { it[0] to (it[1].toInt() to it[2].toInt()) } }
            .toMap()
        val files = dir.listFiles { f -> f.name.lowercase().endsWith(".jpg") }!!.sortedBy { it.name }
        val scores = LinkedHashMap<String, Map<String, Double>>()
        val floorSub = LinkedHashMap<String, Map<String, Double>>()
        val minAxis = LinkedHashMap<String, Map<String, Double>>()
        val meta = LinkedHashMap<String, String>()

        for (f in files) {
            val img = ImageIO.read(f) ?: continue
            val tw = img.width
            val th = img.height
            val (ow, oh) = sizes[f.name] ?: (tw to th)
            val k = tw.toDouble() / ow // 썸네일 화소 / 원본 화소
            val sample = ImageAnalyzer.sampleSizeFor(6000, 4000) // R10 L 크기 기준 경로(=2)
            val aw = max(3, (ow / sample.toDouble()).roundToInt())
            val ah = max(3, (oh / sample.toDouble()).roundToInt())
            val luma = FloatArray(tw * th)
            val row = IntArray(tw)
            for (y in 0 until th) {
                img.getRGB(0, y, tw, 1, row, 0, tw)
                for (x in 0 until tw) {
                    val p = row[x]
                    luma[y * tw + x] = ((77 * ((p ushr 16) and 0xFF) + 150 * ((p ushr 8) and 0xFF) + 29 * (p and 0xFF)) ushr 8).toFloat()
                }
            }
            val perCond = LinkedHashMap<String, Double>()
            val perFloor = LinkedHashMap<String, Double>()
            val perAxis = LinkedHashMap<String, Double>()
            var focus = ""
            var noiseFloor = 0.0
            val t0 = System.nanoTime()
            for (c in conditions) {
                var work = luma
                if (c.gaussSigma > 0) work = gaussian(work, tw, th, c.gaussSigma * k)
                if (c.motionLen > 0) work = motionX(work, tw, th, c.motionLen * k)
                val small = resampleArea(work, tw, th, aw, ah)
                if (c.noiseSigmaFull > 0) {
                    val r = java.util.Random((f.name.hashCode() * 31 + c.key.hashCode()).toLong())
                    val s = c.noiseSigmaFull / sample
                    for (i in small.indices) small[i] += (r.nextGaussian() * s).toFloat()
                }
                val gray = ByteArray(aw * ah) { i -> small[i].roundToInt().coerceIn(0, 255).toByte() }
                val res = Sharpness.compute(gray, aw, ah)
                perCond[c.key] = res.score
                perFloor[c.key] = (res.score - res.tileVariances.min()).coerceAtLeast(0.0)
                perAxis[c.key] = minAxisScore(gray, aw, ah)
                if (c.key == "orig") {
                    focus = "(${res.bestCol},${res.bestRow})"
                    noiseFloor = res.tileVariances.min()
                }
            }
            scores[f.name] = perCond
            floorSub[f.name] = perFloor
            minAxis[f.name] = perAxis
            meta[f.name] = "${ow}×$oh → ${aw}×$ah, 초점 타일 $focus, 최저 타일 ${fmt(noiseFloor)}"
            println("%s %s %s (%.1fs)".format(f.name, meta[f.name], perCond.values.joinToString(" ") { "%.0f".format(it) }, (System.nanoTime() - t0) / 1e9))
        }

        val report = buildReport(scores, meta) +
            "\n\n## 실험: 잡음 바닥 빼기(상위 3개 평균 − 최저 타일 분산)\n\n" + summary(floorSub) +
            "\n\n## 실험: 축별 2차 미분 분산 중 작은 쪽(상위 3개 평균)\n\n" + summary(minAxis)
        println(report)
        System.getenv("PHOTOCULL_CALIB_OUT")?.let { File(it).writeText(report) }
    }

    private fun buildReport(scores: Map<String, Map<String, Double>>, meta: Map<String, String>): String = buildString {
        appendLine("| 사진 | 크기(원본 → 분석) | " + conditions.joinToString(" | ") { it.key } + " |")
        appendLine("|---|---|" + conditions.joinToString("") { "---:|" })
        for ((name, s) in scores) {
            appendLine("| $name | ${meta[name]} | " + conditions.joinToString(" | ") { fmt(s.getValue(it.key)) } + " |")
        }
        appendLine()
        appendLine("| 조건 | 최소 | 10% | 중앙값 | 90% | 최대 |")
        appendLine("|---|---:|---:|---:|---:|---:|")
        for (c in conditions) {
            val v = scores.values.map { it.getValue(c.key) }.sorted()
            appendLine("| ${c.label} (${c.key}) | ${fmt(v.first())} | ${fmt(q(v, 0.1))} | ${fmt(q(v, 0.5))} | ${fmt(q(v, 0.9))} | ${fmt(v.last())} |")
        }
        appendLine()
        appendLine("현재 BlurThresholds로 흐림 후보가 되는 비율(점수 < 임계값):")
        appendLine()
        appendLine("| 조건 | " + BlurSensitivity.entries.joinToString(" | ") { "${it.name} (${fmt(BlurThresholds.threshold(it))})" } + " |")
        appendLine("|---|" + BlurSensitivity.entries.joinToString("") { "---:|" })
        for (c in conditions) {
            val v = scores.values.map { it.getValue(c.key) }
            appendLine("| ${c.label} | " + BlurSensitivity.entries.joinToString(" | ") { s ->
                val t = BlurThresholds.threshold(s)
                "${v.count { it < t }}/${v.size}"
            } + " |")
        }
        appendLine()
        appendLine("원본 대비 점수 비율(사진별 중앙값): " + conditions.drop(1).joinToString(", ") { c ->
            val r = scores.values.map { it.getValue(c.key) / it.getValue("orig") }.sorted()
            "${c.key} ${"%.2f".format(Locale.US, q(r, 0.5))}"
        })
    }

    private fun summary(scores: Map<String, Map<String, Double>>): String = buildString {
        appendLine("| 조건 | 최소 | 10% | 중앙값 | 90% | 최대 |")
        appendLine("|---|---:|---:|---:|---:|---:|")
        for (c in conditions) {
            val v = scores.values.map { it.getValue(c.key) }.sorted()
            appendLine("| ${c.label} (${c.key}) | ${fmt(v.first())} | ${fmt(q(v, 0.1))} | ${fmt(q(v, 0.5))} | ${fmt(q(v, 0.9))} | ${fmt(v.last())} |")
        }
    }

    /** 실험용: 타일마다 var(Ixx), var(Iyy)([1,-2,1])를 따로 구해 작은 쪽, 그 상위 3개 평균. 한 방향 모션 블러에 민감하다. */
    private fun minAxisScore(g: ByteArray, w: Int, h: Int): Double {
        val (cols, rows) = Sharpness.grid(w, h)
        val n = LongArray(cols * rows)
        val sx = DoubleArray(cols * rows); val qx = DoubleArray(cols * rows)
        val sy = DoubleArray(cols * rows); val qy = DoubleArray(cols * rows)
        for (y in 1 until h - 1) {
            val tr = (y.toLong() * rows / h).toInt()
            for (x in 1 until w - 1) {
                val t = tr * cols + (x.toLong() * cols / w).toInt()
                val c = g[y * w + x].toInt() and 0xFF
                val dxx = ((g[y * w + x - 1].toInt() and 0xFF) + (g[y * w + x + 1].toInt() and 0xFF) - 2 * c).toDouble()
                val dyy = ((g[(y - 1) * w + x].toInt() and 0xFF) + (g[(y + 1) * w + x].toInt() and 0xFF) - 2 * c).toDouble()
                n[t]++; sx[t] += dxx; qx[t] += dxx * dxx; sy[t] += dyy; qy[t] += dyy * dyy
            }
        }
        val v = DoubleArray(cols * rows) { t ->
            if (n[t] == 0L) 0.0 else {
                val vx = qx[t] / n[t] - (sx[t] / n[t]).let { it * it }
                val vy = qy[t] / n[t] - (sy[t] / n[t]).let { it * it }
                min(vx, vy)
            }
        }
        return v.sortedDescending().take(3).average()
    }

    private fun fmt(v: Double) = if (v >= 100) "%.0f".format(Locale.US, v) else "%.1f".format(Locale.US, v)

    private fun q(sorted: List<Double>, p: Double): Double {
        if (sorted.size == 1) return sorted[0]
        val pos = p * (sorted.size - 1)
        val i = pos.toInt().coerceAtMost(sorted.size - 2)
        val f = pos - i
        return sorted[i] * (1 - f) + sorted[i + 1] * f
    }

    // ---- 영상 처리(float, 행 우선) ----

    private fun gaussian(src: FloatArray, w: Int, h: Int, sigma: Double): FloatArray {
        val r = max(1, ceil(3 * sigma).toInt())
        val k = FloatArray(2 * r + 1) { i -> exp(-((i - r) * (i - r)) / (2 * sigma * sigma)).toFloat() }
        val sum = k.sum()
        for (i in k.indices) k[i] /= sum
        return convolveY(convolveX(src, w, h, k), w, h, k)
    }

    /** 가로 모션 블러: 길이 [len]의 상자(양 끝은 겹친 비율만큼 가중) */
    private fun motionX(src: FloatArray, w: Int, h: Int, len: Double): FloatArray {
        val half = len / 2
        val r = ceil(half - 0.5).toInt().coerceAtLeast(0)
        val k = FloatArray(2 * r + 1) { i ->
            val j = i - r
            (min(j + 0.5, half) - max(j - 0.5, -half)).coerceAtLeast(0.0).toFloat()
        }
        val sum = k.sum()
        for (i in k.indices) k[i] /= sum
        return convolveX(src, w, h, k)
    }

    private fun convolveX(src: FloatArray, w: Int, h: Int, k: FloatArray): FloatArray {
        val r = k.size / 2
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                var s = 0f
                for (i in k.indices) {
                    val xx = (x + i - r).coerceIn(0, w - 1)
                    s += src[base + xx] * k[i]
                }
                out[base + x] = s
            }
        }
        return out
    }

    private fun convolveY(src: FloatArray, w: Int, h: Int, k: FloatArray): FloatArray {
        val r = k.size / 2
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (i in k.indices) {
                val yy = (y + i - r).coerceIn(0, h - 1)
                val kw = k[i]
                val sb = yy * w
                val ob = y * w
                for (x in 0 until w) out[ob + x] += src[sb + x] * kw
            }
        }
        return out
    }

    /** 영역 평균 축소(임의 배율, 분리형) */
    private fun resampleArea(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        val tmp = FloatArray(dw * sh)
        val (xi, xw) = areaWeights(sw, dw)
        for (y in 0 until sh) for (x in 0 until dw) {
            var s = 0f
            val idx = xi[x]
            val wt = xw[x]
            for (j in idx.indices) s += src[y * sw + idx[j]] * wt[j]
            tmp[y * dw + x] = s
        }
        val out = FloatArray(dw * dh)
        val (yi, yw) = areaWeights(sh, dh)
        for (y in 0 until dh) {
            val idx = yi[y]
            val wt = yw[y]
            for (j in idx.indices) {
                val sb = idx[j] * dw
                val wj = wt[j]
                for (x in 0 until dw) out[y * dw + x] += tmp[sb + x] * wj
            }
        }
        return out
    }

    private fun areaWeights(srcLen: Int, dstLen: Int): Pair<Array<IntArray>, Array<FloatArray>> {
        val scale = srcLen.toDouble() / dstLen
        val idx = arrayOfNulls<IntArray>(dstLen)
        val wts = arrayOfNulls<FloatArray>(dstLen)
        for (d in 0 until dstLen) {
            val a = d * scale
            val b = (d + 1) * scale
            val i0 = a.toInt()
            val i1 = min(srcLen - 1, ceil(b).toInt() - 1)
            val n = i1 - i0 + 1
            val ii = IntArray(n)
            val ww = FloatArray(n)
            for (j in 0 until n) {
                val s = i0 + j
                ii[j] = s
                ww[j] = ((min(b, s + 1.0) - max(a, s.toDouble())) / scale).toFloat()
            }
            idx[d] = ii
            wts[d] = ww
        }
        @Suppress("UNCHECKED_CAST")
        return (idx as Array<IntArray>) to (wts as Array<FloatArray>)
    }
}
