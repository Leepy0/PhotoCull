package com.leepy0.photocull.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ExifValuesTest {

    private val seoul = ZoneId.of("Asia/Seoul")

    @Test
    fun dateTime_withOffsetAndSubSec() {
        val ms = ExifValues.parseDateTime("2026:10:11 14:03:12", "45", "+09:00", ZoneId.of("UTC"))
        assertThat(ms).isEqualTo(ZonedDateTime.of(2026, 10, 11, 14, 3, 12, 450_000_000, seoul).toInstant().toEpochMilli())
    }

    @Test
    fun dateTime_withoutOffset_usesDeviceZone() {
        val ms = ExifValues.parseDateTime("2026:10:11 14:03:12", null, null, seoul)
        assertThat(ms).isEqualTo(ZonedDateTime.of(2026, 10, 11, 14, 3, 12, 0, seoul).toInstant().toEpochMilli())
    }

    @Test
    fun dateTime_negativeOffset_andDashFormat() {
        val ms = ExifValues.parseDateTime("2026-01-02 03:04:05", "5", "-05:30", seoul)
        assertThat(ms).isEqualTo(
            ZonedDateTime.of(2026, 1, 2, 3, 4, 5, 500_000_000, ZoneId.of("-05:30")).toInstant().toEpochMilli(),
        )
    }

    @Test
    fun dateTime_invalidValues() {
        assertThat(ExifValues.parseDateTime(null, null, null, seoul)).isNull()
        assertThat(ExifValues.parseDateTime("0000:00:00 00:00:00", null, null, seoul)).isNull()
        assertThat(ExifValues.parseDateTime("    :  :     :  :  ", null, null, seoul)).isNull()
        assertThat(ExifValues.parseDateTime("garbage", null, null, seoul)).isNull()
        // 잘못된 오프셋은 무시하고 기기 시간대로
        assertThat(ExifValues.parseDateTime("2026:10:11 14:03:12", null, "+99:00", seoul))
            .isEqualTo(ExifValues.parseDateTime("2026:10:11 14:03:12", null, null, seoul))
    }

    @Test
    fun subSec() {
        assertThat(ExifValues.subSecMillis("45")).isEqualTo(450)
        assertThat(ExifValues.subSecMillis("5")).isEqualTo(500)
        assertThat(ExifValues.subSecMillis("123456")).isEqualTo(123)
        assertThat(ExifValues.subSecMillis(" 07")).isEqualTo(70)
        assertThat(ExifValues.subSecMillis("")).isEqualTo(0)
        assertThat(ExifValues.subSecMillis(null)).isEqualTo(0)
    }

    @Test
    fun exposure() {
        assertThat(ExifValues.exposure(1.0 / 250)).isEqualTo("1/250")
        assertThat(ExifValues.exposure(1.0 / 8000)).isEqualTo("1/8000")
        assertThat(ExifValues.exposure(0.0125)).isEqualTo("1/80")
        assertThat(ExifValues.exposure(0.5)).isEqualTo("1/2")
        assertThat(ExifValues.exposure(0.3)).isEqualTo("0.3s")
        assertThat(ExifValues.exposure(0.8)).isEqualTo("0.8s")
        assertThat(ExifValues.exposure(1.0)).isEqualTo("1s")
        assertThat(ExifValues.exposure(2.5)).isEqualTo("2.5s")
        assertThat(ExifValues.exposure(30.0)).isEqualTo("30s")
        assertThat(ExifValues.exposure(0.0)).isNull()
        assertThat(ExifValues.exposure(null)).isNull()
    }

    @Test
    fun apertureAndFocalLength() {
        assertThat(ExifValues.aperture(5.6)).isEqualTo("f/5.6")
        assertThat(ExifValues.aperture(8.0)).isEqualTo("f/8")
        assertThat(ExifValues.aperture(11.0)).isEqualTo("f/11")
        assertThat(ExifValues.aperture(1.8)).isEqualTo("f/1.8")
        assertThat(ExifValues.aperture(-1.0)).isNull()
        assertThat(ExifValues.focalLength(50.0)).isEqualTo("50mm")
        assertThat(ExifValues.focalLength(18.4)).isEqualTo("18mm")
        assertThat(ExifValues.focalLength(4.25)).isEqualTo("4.3mm")
        assertThat(ExifValues.focalLength(null)).isNull()
    }

    @Test
    fun iso_fallsBackWhenSaturated() {
        assertThat(ExifValues.iso(400, null, null)).isEqualTo(400)
        assertThat(ExifValues.iso(65535, 102400, null)).isEqualTo(102400)
        assertThat(ExifValues.iso(null, null, 25600)).isEqualTo(25600)
        assertThat(ExifValues.iso(65535, null, null)).isEqualTo(65535)
        assertThat(ExifValues.iso(null, null, null)).isNull()
    }

    @Test
    fun orientation_displaySize() {
        assertThat(ExifOrientation.displaySize(6000, 4000, ExifOrientation.NORMAL)).isEqualTo(6000 to 4000)
        assertThat(ExifOrientation.displaySize(6000, 4000, ExifOrientation.ROTATE_180)).isEqualTo(6000 to 4000)
        for (o in listOf(5, 6, 7, 8)) assertThat(ExifOrientation.displaySize(6000, 4000, o)).isEqualTo(4000 to 6000)
    }

    @Test
    fun orientation_displayPoint() {
        // 저장 영상(가로 W×H)의 왼쪽 위 근처 점 (0.1, 0.2)
        val x = 0.1f
        val y = 0.2f
        fun p(o: Int) = ExifOrientation.displayPoint(x, y, o)
        assertThat(p(1)).isEqualTo(0.1f to 0.2f)
        assertThat(p(2)).isEqualTo(0.9f to 0.2f) // 좌우 반전
        assertThat(p(3)).isEqualTo(0.9f to 0.8f) // 180°
        assertThat(p(4)).isEqualTo(0.1f to 0.8f) // 상하 반전
        // 시계 방향 90°: 저장 영상의 왼쪽 위는 화면의 오른쪽 위로 간다
        assertThat(p(6).first).isWithin(1e-6f).of(0.8f)
        assertThat(p(6).second).isWithin(1e-6f).of(0.1f)
        // 반시계 90°(=270°): 왼쪽 위 → 왼쪽 아래
        assertThat(p(8).first).isWithin(1e-6f).of(0.2f)
        assertThat(p(8).second).isWithin(1e-6f).of(0.9f)
        assertThat(p(5)).isEqualTo(0.2f to 0.1f) // 전치
        assertThat(p(7).first).isWithin(1e-6f).of(0.8f)
        assertThat(p(7).second).isWithin(1e-6f).of(0.9f)
        // 알 수 없는 값은 그대로
        assertThat(ExifOrientation.displayPoint(x, y, 0)).isEqualTo(x to y)
    }

    @Test
    fun orientation_rotate90_matchesPixelMapping() {
        // 픽셀 단위로 확인: W×H 영상을 시계 방향으로 돌리면 (px, py) → (H-1-py, px)
        val w = 6
        val h = 4
        val px = 1
        val py = 0
        val (dx, dy) = ExifOrientation.displayPoint((px + 0.5f) / w, (py + 0.5f) / h, ExifOrientation.ROTATE_90)
        val (dw, dh) = ExifOrientation.displaySize(w, h, ExifOrientation.ROTATE_90)
        assertThat((dx * dw - 0.5f)).isWithin(1e-4f).of((h - 1 - py).toFloat())
        assertThat((dy * dh - 0.5f)).isWithin(1e-4f).of(px.toFloat())
    }

    @Test
    fun sampleSize() {
        assertThat(ImageAnalyzer.sampleSizeFor(6000, 4000)).isEqualTo(2)
        assertThat(ImageAnalyzer.sampleSizeFor(4000, 6000)).isEqualTo(2)
        // 긴 변이 3000 미만으로 떨어지지 않는 한 2배 축소는 하지 않는다(M 크기 3984px, 잘라낸 사진 등)
        assertThat(ImageAnalyzer.sampleSizeFor(5500, 2500)).isEqualTo(1)
        // 단, 화소 수 상한(1600만)을 넘으면 한 단계 더 줄인다: 거의 원본 크기로 자른 24MP 사진도 L 크기와 같은 배율로 분석
        assertThat(ImageAnalyzer.sampleSizeFor(5999, 3999)).isEqualTo(2)
        assertThat(ImageAnalyzer.sampleSizeFor(3984, 2656)).isEqualTo(1)
        assertThat(ImageAnalyzer.sampleSizeFor(3000, 2000)).isEqualTo(1)
        assertThat(ImageAnalyzer.sampleSizeFor(600, 400)).isEqualTo(1)
        assertThat(ImageAnalyzer.sampleSizeFor(12000, 8000)).isEqualTo(4)
        // 메모리 상한(1600만 화소): 9000×9000 → 2배 축소로는 2025만 화소라 4배
        assertThat(ImageAnalyzer.sampleSizeFor(9000, 9000)).isEqualTo(4)
        // 파노라마: 긴 변 기준
        assertThat(ImageAnalyzer.sampleSizeFor(20000, 3000)).isEqualTo(4)
    }

    @Test
    fun luma() {
        val argb = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
        val out = ByteArray(5)
        Luma.fromArgb(argb, 5, out, 0)
        assertThat(out.map { it.toInt() and 0xFF }).containsExactly(255, 0, 76, 149, 28).inOrder()
    }
}
