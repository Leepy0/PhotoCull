package com.leepy0.photocull.importer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProgressTrackerTest {
    private var now = 0L
    private val emitted = ArrayList<ImportProgress>()

    private fun tracker(totalBytes: Long = 100_000_000, totalFiles: Int = 10) =
        ProgressTracker(7, totalFiles, totalBytes, startedAt = 123, ticker = { now }, minIntervalNanos = 250_000_000, emit = { emitted += it })

    private fun advanceMs(ms: Long) {
        now += ms * 1_000_000
    }

    @Test fun throttlesToFourPerSecond() {
        val t = tracker()
        t.startFile("a", 10_000_000)
        repeat(100) {
            advanceMs(10)
            t.onTransferred(100_000)
        }
        // 1초 동안 100번 갱신 → 250ms 간격이면 최대 5회(처음 포함)
        assertThat(emitted.size).isAtMost(5)
        t.emitNow()
        assertThat(emitted.last().doneBytes).isEqualTo(10_000_000)
    }

    @Test fun bytesFractionSpeedAndEta() {
        val t = tracker(totalBytes = 100_000_000)
        t.startFile("IMG_0001.JPG", 50_000_000)
        // 3초 동안 10MB/s
        repeat(30) {
            advanceMs(100)
            t.onTransferred(1_000_000)
        }
        val p = t.snapshot()
        assertThat(p.doneBytes).isEqualTo(30_000_000)
        assertThat(p.fraction).isWithin(0.001f).of(0.3f)
        assertThat(p.bytesPerSecond).isIn(9_000_000L..11_000_000L)
        assertThat(p.etaSeconds).isIn(6L..8L) // 남은 70MB / 10MB/s
        assertThat(p.currentFileName).isEqualTo("IMG_0001.JPG")
        assertThat(p.sessionId).isEqualTo(7)
        assertThat(p.startedAt).isEqualTo(123)
    }

    @Test fun retryDoesNotDoubleCountAndFailuresCount() {
        val t = tracker(totalBytes = 1_000)
        t.startFile("a", 600)
        t.onTransferred(500)
        t.restartFile()
        t.onTransferred(600)
        t.onTransferred(600) // 해시 확인 등으로 더 읽어도 파일 크기를 넘지 않는다
        assertThat(t.doneBytes).isEqualTo(600)
        t.finishFile(failed = true)
        assertThat(t.snapshot().failedCount).isEqualTo(1)
        assertThat(t.snapshot().doneFiles).isEqualTo(1)
    }

    @Test fun noEtaBeforeEnoughData() {
        val t = tracker()
        t.startFile("a", 1_000)
        advanceMs(100)
        t.onTransferred(100)
        assertThat(t.snapshot().etaSeconds).isNull()
        assertThat(t.snapshot().bytesPerSecond).isEqualTo(0)
    }
}
