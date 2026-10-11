package com.leepy0.photocull.analysis

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class VideoTimeTest {

    private val seoul = ZoneId.of("Asia/Seoul")
    private val utc = ZoneId.of("UTC")

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, zone: ZoneId) =
        ZonedDateTime.of(y, mo, d, h, mi, s, 0, zone).toInstant().toEpochMilli()

    @Test
    fun parse() {
        assertThat(VideoTime.parseMetadataDate("20261011T050312.000Z")).isEqualTo(ms(2026, 10, 11, 5, 3, 12, utc))
        assertThat(VideoTime.parseMetadataDate("20261011T050312Z")).isEqualTo(ms(2026, 10, 11, 5, 3, 12, utc))
        assertThat(VideoTime.parseMetadataDate("19040101T000000.000Z")).isNull() // 값 없음(0)
        assertThat(VideoTime.parseMetadataDate("")).isNull()
        assertThat(VideoTime.parseMetadataDate(null)).isNull()
        assertThat(VideoTime.parseMetadataDate("not a date")).isNull()
    }

    @Test
    fun reconcile_trueUtcMatchingFileTime_isUsed() {
        val start = ms(2026, 10, 11, 14, 3, 12, seoul)
        val fileMtime = start + 20_000 // 녹화 끝
        assertThat(VideoTime.reconcile(start, fileMtime, 20_000, seoul)).isEqualTo(start)
    }

    @Test
    fun reconcile_localTimeWrittenAsUtc_isCorrected() {
        val start = ms(2026, 10, 11, 14, 3, 12, seoul)
        val writtenAsUtc = ms(2026, 10, 11, 14, 3, 12, utc) // 카메라가 현지 시각을 UTC 칸에 적음(9시간 늦게 읽힘)
        assertThat(VideoTime.reconcile(writtenAsUtc, start + 10_000, 10_000, seoul)).isEqualTo(start)
    }

    @Test
    fun reconcile_unrelatedTime_keepsExisting() {
        val start = ms(2026, 10, 11, 14, 3, 12, seoul)
        assertThat(VideoTime.reconcile(start - 3 * 86_400_000L, start, 5_000, seoul)).isNull()
        assertThat(VideoTime.reconcile(null, start, 5_000, seoul)).isNull()
    }
}
