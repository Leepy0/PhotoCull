package com.leepy0.photocull.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/** 화면 표시용 포맷. 단위는 1000이 아닌 1024 기준(갤러리·파일 앱과 같은 방식). */
object Format {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun bytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes / 1024.0
        var i = 0
        while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
        return if (value >= 100 || i == 0) "${value.roundToLong()} ${units[i]}"
        else String.format(Locale.US, "%.1f %s", value, units[i])
    }

    /** 1,234 */
    fun count(n: Int): String = String.format(Locale.KOREA, "%,d", n)

    /** 0:03, 1:02:03 */
    fun duration(ms: Long): String {
        val total = (ms + 500) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** 남은 시간: "약 3분", "1분 미만", "약 1시간 5분" */
    fun eta(seconds: Long): String = when {
        seconds < 60 -> "1분 미만"
        seconds < 3600 -> "약 ${(seconds + 30) / 60}분"
        else -> "약 ${seconds / 3600}시간 ${(seconds % 3600) / 60}분"
    }

    /** 85 MB/s */
    fun speed(bytesPerSecond: Long): String = "${bytes(bytesPerSecond)}/s"

    /** 2026. 10. 11. 14:03 */
    fun dateTime(epochMs: Long): String =
        DateTimeFormatter.ofPattern("yyyy. M. d. HH:mm").withZone(zone).format(Instant.ofEpochMilli(epochMs))

    /** 10월 11일 */
    fun monthDay(epochMs: Long): String =
        DateTimeFormatter.ofPattern("M월 d일").withZone(zone).format(Instant.ofEpochMilli(epochMs))

    /** 14:03:12 */
    fun time(epochMs: Long): String =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(zone).format(Instant.ofEpochMilli(epochMs))

    /** 촬영 기간: "10월 3일 ~ 10월 11일" 또는 하루면 "10월 11일" */
    fun dateRange(fromMs: Long, toMs: Long): String {
        val a = Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate()
        val b = Instant.ofEpochMilli(toMs).atZone(zone).toLocalDate()
        return if (a == b) monthDay(fromMs) else "${monthDay(fromMs)} ~ ${monthDay(toMs)}"
    }

    /** "오늘", "어제", "3일 전", "10월 2일" */
    fun relativeDay(epochMs: Long, now: Long = System.currentTimeMillis()): String {
        val d = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val days = today.toEpochDay() - d.toEpochDay()
        return when {
            days <= 0L -> "오늘"
            days == 1L -> "어제"
            days < 7L -> "${days}일 전"
            else -> monthDay(epochMs)
        }
    }

    /** 휴지통 남은 보관 일수. retentionDays=0(무기한)이면 null */
    fun daysLeft(trashedAt: Long, retentionDays: Int, now: Long = System.currentTimeMillis()): Int? {
        if (retentionDays <= 0) return null
        val elapsedDays = ((now - trashedAt) / 86_400_000L).toInt()
        return (retentionDays - elapsedDays).coerceAtLeast(0)
    }

    fun localDate(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
}
