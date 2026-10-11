package com.leepy0.photocull.analysis

import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.abs

/** 영상 1개 분석 결과. 크기는 표시 방향(회전 반영). */
data class VideoAnalysis(
    val durationMs: Long,
    val width: Int?,
    val height: Int?,
    /** 믿을 수 있는 촬영 시각을 얻었을 때만 값이 있다(없으면 기존 값 유지) */
    val capturedAt: Long?,
)

/** 영상 메타데이터를 읽을 수 없음(손상·미지원 형식) */
class UnreadableVideoException(path: String, cause: Throwable? = null) : IOException("cannot read video: $path", cause)

/**
 * 영상 길이·해상도·촬영 시각(DESIGN.md §4.5). 흐림 판정은 하지 않는다.
 * 길이를 못 읽으면 실패로 본다 — 길이 0으로 저장하면 '실수 촬영 후보'로 잘못 분류되기 때문.
 */
open class VideoAnalyzer(
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    /** @param currentCapturedAt DB에 있는 촬영 시각(가져오기 때 파일 수정 시각 등). 메타 시각 검증에 쓴다. */
    open suspend fun analyze(path: String, currentCapturedAt: Long): VideoAnalysis = withContext(Dispatchers.IO) {
        if (!File(path).isFile) throw FileNotFoundException(path)
        val retriever = MediaMetadataRetriever()
        try {
            try {
                retriever.setDataSource(path)
            } catch (e: RuntimeException) {
                throw UnreadableVideoException(path, e)
            }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.trim()?.toLongOrNull()
            if (duration == null || duration <= 0) throw UnreadableVideoException(path)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.trim()?.toIntOrNull() ?: 0
            val swap = (((rotation % 360) + 360) % 360).let { it == 90 || it == 270 }
            val metaDate = VideoTime.parseMetadataDate(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
            VideoAnalysis(
                durationMs = duration,
                width = if (swap) h else w,
                height = if (swap) w else h,
                capturedAt = VideoTime.reconcile(metaDate, currentCapturedAt, duration, zone()),
            )
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }
}

/** 영상 촬영 시각 해석(순수 Kotlin) */
object VideoTime {
    private val FORMATS = listOf(
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'", Locale.US),
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US),
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.US),
    )

    /** 기준과 이만큼 이내로 맞으면 같은 순간으로 본다(+영상 길이: 수정 시각은 녹화 끝 시각일 수 있다). */
    private const val TOLERANCE_MS = 5 * 60_000L

    /** MediaMetadataRetriever DATE("20221024T123456.000Z", UTC 기준) → epoch ms. 0(1904년 등)·이상값은 null. */
    fun parseMetadataDate(text: String?): Long? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val local = FORMATS.firstNotNullOfOrNull { f ->
            try { LocalDateTime.parse(t, f) } catch (_: DateTimeParseException) { null }
        } ?: return null
        if (local.year < 1990) return null
        return local.toInstant(ZoneOffset.UTC).toEpochMilli()
    }

    /**
     * 영상 메타 시각은 규격상 UTC지만, 많은 카메라가 현지 시각을 그대로 적는다. 기존 값(파일 수정 시각)과 비교해
     * - 그대로 맞으면 메타 시각(녹화 시작 시각)
     * - 기기 시간대만큼 어긋나 있으면 현지 시각으로 보고 보정한 값
     * - 둘 다 아니면 null(판단할 수 없으니 기존 값 유지)
     */
    fun reconcile(metaUtcMs: Long?, currentCapturedAt: Long, durationMs: Long, zone: ZoneId): Long? {
        if (metaUtcMs == null || currentCapturedAt <= 0) return null
        val tolerance = TOLERANCE_MS + durationMs.coerceAtLeast(0)
        if (abs(metaUtcMs - currentCapturedAt) <= tolerance) return metaUtcMs
        val offsetMs = zone.rules.getOffset(Instant.ofEpochMilli(currentCapturedAt)).totalSeconds * 1000L
        if (offsetMs != 0L) {
            val corrected = metaUtcMs - offsetMs
            if (abs(corrected - currentCapturedAt) <= tolerance) return corrected
        }
        return null
    }
}
