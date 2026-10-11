package com.leepy0.photocull.importer

import androidx.exifinterface.media.ExifInterface
import com.leepy0.photocull.storage.CardFile
import com.leepy0.photocull.storage.CardFileKind
import com.leepy0.photocull.storage.CardSource
import java.io.BufferedInputStream
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 촬영 시각 읽기(월별 폴더·DB capturedAt). 모르면 null → 파일 수정 시각을 쓴다. */
fun interface CaptureTimeReader {
    @Throws(IOException::class)
    fun read(source: CardSource, file: CardFile, kind: CardFileKind): Long?
}

/**
 * 카드 JPG의 EXIF DateTimeOriginal(+SubSec, OffsetTime). 헤더(SOS 앞 세그먼트)만 읽고 멈춘다.
 * HIF는 스트림 파싱이 파일 전체를 버퍼링할 수 있어 읽지 않고, CR3·영상은 지원하지 않아 수정 시각을 쓴다.
 */
object ExifCaptureTimeReader : CaptureTimeReader {
    override fun read(source: CardSource, file: CardFile, kind: CardFileKind): Long? {
        if (kind != CardFileKind.JPEG) return null
        return source.openInput(file).use { input ->
            val exif = ExifInterface(BufferedInputStream(input, 64 * 1024), ExifInterface.STREAM_TYPE_FULL_IMAGE_DATA)
            ExifDates.captureTime(exif)
        }
    }
}

object ExifDates {
    private val FORMAT = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    fun captureTime(exif: ExifInterface, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val tags = listOf(
            Triple(ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
            Triple(ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME_DIGITIZED),
            Triple(ExifInterface.TAG_DATETIME, ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_OFFSET_TIME),
        )
        for ((dateTag, subSecTag, offsetTag) in tags) {
            parse(exif.getAttribute(dateTag), exif.getAttribute(subSecTag), exif.getAttribute(offsetTag), zone)?.let { return it }
        }
        return null
    }

    /**
     * "2026:10:11 14:03:12" → epoch ms. 시간대(OffsetTime)가 없으면 카메라 시계 = 폰 시간대로 본다
     * (ExifInterface.getDateTimeOriginal은 이때 UTC로 해석해 월 경계에서 폴더가 어긋난다).
     */
    fun parse(dateTime: String?, subSec: String?, offset: String?, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val text = dateTime?.trim()?.takeIf { it.length >= 19 }?.substring(0, 19) ?: return null
        if (text.startsWith("0000")) return null
        val local = runCatching { LocalDateTime.parse(text, FORMAT) }.getOrNull() ?: return null
        val zoneOffset = offset?.trim()?.let { runCatching { ZoneOffset.of(it) }.getOrNull() }
        val instant = if (zoneOffset != null) local.toInstant(zoneOffset) else local.atZone(zone).toInstant()
        val millis = subSec?.trim()?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }
            ?.let { (it + "00").substring(0, 3).toLong() } ?: 0L
        return (instant.toEpochMilli() + millis).takeIf { it > 0 }
    }
}
