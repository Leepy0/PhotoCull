package com.leepy0.photocull.analysis

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** EXIF 값 해석·표시 문자열(순수 Kotlin). 화면에 그대로 보여 주는 값이라 짧은 국제 표기를 쓴다. */
object ExifValues {

    private val DATE_FORMATS = listOf(
        DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US),
    )
    private val OFFSET = Regex("""^([+-])(\d{2}):?(\d{2})$""")

    /**
     * EXIF DateTimeOriginal(+SubSecTimeOriginal, OffsetTimeOriginal) → epoch ms.
     * 오프셋이 있으면 그 시간대로, 없으면 [zone](기기 시간대)의 현지 시각으로 본다.
     * (androidx ExifInterface#getDateTimeOriginal은 오프셋이 없으면 현지 시각을 UTC로 읽어 시간대만큼 어긋나므로 직접 해석한다.)
     */
    fun parseDateTime(dateTime: String?, subSec: String?, offset: String?, zone: ZoneId): Long? {
        val text = dateTime?.trim()?.trimEnd('\u0000')?.takeIf { it.length >= 19 } ?: return null
        val local = DATE_FORMATS.firstNotNullOfOrNull { f ->
            try { LocalDateTime.parse(text.substring(0, 19), f) } catch (_: DateTimeParseException) { null }
        } ?: return null
        if (local.year < 1970) return null
        val zoneOffset = offset?.trim()?.let { OFFSET.matchEntire(it) }?.let { m ->
            val h = m.groupValues[2].toInt()
            val min = m.groupValues[3].toInt()
            if (h > 14 || min > 59) null
            else ZoneOffset.ofHoursMinutes(if (m.groupValues[1] == "-") -h else h, if (m.groupValues[1] == "-") -min else min)
        }
        val epochMs = if (zoneOffset != null) local.toInstant(zoneOffset).toEpochMilli()
        else local.atZone(zone).toInstant().toEpochMilli()
        return epochMs + subSecMillis(subSec)
    }

    /** "45" → 450ms, "5" → 500ms, "123456" → 123ms */
    fun subSecMillis(subSec: String?): Long {
        val digits = subSec?.trim()?.takeWhile { it.isDigit() }.orEmpty()
        if (digits.isEmpty()) return 0
        return digits.take(3).padEnd(3, '0').toLong()
    }

    /** 노출 시간(초) → "1/250", "1/2", "0.3s", "2s", "1.3s" */
    fun exposure(seconds: Double?): String? {
        if (seconds == null || !seconds.isFinite() || seconds <= 0) return null
        if (seconds >= 1.0) return trimDecimal(seconds) + "s"
        val inv = 1.0 / seconds
        val n = inv.roundToLong()
        return if (n >= 1 && abs(inv - n) / inv < 0.03) "1/$n" else trimDecimal(seconds) + "s"
    }

    /** F 값 → "f/5.6", "f/8", "f/11" */
    fun aperture(fNumber: Double?): String? {
        if (fNumber == null || !fNumber.isFinite() || fNumber <= 0) return null
        return "f/" + trimDecimal(fNumber)
    }

    /** 초점거리(mm) → "50mm", "18mm", "4.3mm" */
    fun focalLength(mm: Double?): String? {
        if (mm == null || !mm.isFinite() || mm <= 0) return null
        return if (mm >= 10) "${mm.roundToInt()}mm" else trimDecimal(mm) + "mm"
    }

    /** 소수 한 자리까지, 정수면 소수점 없이: 5.6 → "5.6", 8.0 → "8", 0.25 → "0.3" */
    private fun trimDecimal(v: Double): String {
        val tenth = (v * 10).roundToLong()
        return if (tenth % 10 == 0L) (tenth / 10).toString() else String.format(Locale.US, "%.1f", tenth / 10.0)
    }

    /** ISO: PhotographicSensitivity가 65535(상한)거나 없으면 RecommendedExposureIndex/ISOSpeed로 대체 */
    fun iso(photographicSensitivity: Int?, recommendedExposureIndex: Int?, isoSpeed: Int?): Int? {
        val primary = photographicSensitivity?.takeIf { it in 1 until 65535 }
        return primary ?: recommendedExposureIndex?.takeIf { it > 0 } ?: isoSpeed?.takeIf { it > 0 }
            ?: photographicSensitivity?.takeIf { it > 0 }
    }
}

/**
 * EXIF 방향(1~8). 분석은 저장된 픽셀 그대로 하고, 결과(크기·초점 위치)는 화면에 보이는 방향으로 바꿔 저장한다.
 * 뷰어(Coil·telephoto)는 EXIF 회전을 적용해 그리므로, 그 좌표계에서 100% 확대 위치가 맞아야 한다.
 */
object ExifOrientation {
    const val NORMAL = 1
    const val FLIP_HORIZONTAL = 2
    const val ROTATE_180 = 3
    const val FLIP_VERTICAL = 4
    const val TRANSPOSE = 5
    const val ROTATE_90 = 6
    const val TRANSVERSE = 7
    const val ROTATE_270 = 8

    /** 90°/270° 계열이면 가로·세로가 바뀐다 */
    fun swapsAxes(orientation: Int): Boolean = orientation in TRANSPOSE..ROTATE_270

    fun displaySize(width: Int, height: Int, orientation: Int): Pair<Int, Int> =
        if (swapsAxes(orientation)) height to width else width to height

    /** 저장 픽셀 기준 정규화 좌표(0..1) → 화면 표시 기준 좌표 */
    fun displayPoint(x: Float, y: Float, orientation: Int): Pair<Float, Float> = when (orientation) {
        FLIP_HORIZONTAL -> (1f - x) to y
        ROTATE_180 -> (1f - x) to (1f - y)
        FLIP_VERTICAL -> x to (1f - y)
        TRANSPOSE -> y to x
        ROTATE_90 -> (1f - y) to x
        TRANSVERSE -> (1f - y) to (1f - x)
        ROTATE_270 -> y to (1f - x)
        else -> x to y
    }
}
