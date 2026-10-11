package com.leepy0.photocull.importer

/**
 * 진행률 계산: 바이트 기준 완료율, 최근 약 3초 구간의 전송 속도, 남은 시간.
 * 알림/화면 갱신이 과하지 않게 [minIntervalNanos]보다 자주 내보내지 않는다(기본 250ms ≈ 초당 4회).
 */
internal class ProgressTracker(
    private val sessionId: Long,
    private val totalFiles: Int,
    private val totalBytes: Long,
    private val startedAt: Long,
    private val ticker: () -> Long,
    private val minIntervalNanos: Long,
    private val emit: (ImportProgress) -> Unit,
) {
    var doneFiles = 0
        private set
    var failedCount = 0
        private set

    private var completedBytes = 0L
    private var currentBytes = 0L
    private var currentSize = 0L
    private var currentName: String? = null

    /** 카드에서 읽은 전체 바이트(재시도·해시 확인 포함) - 속도 계산용 */
    private var transferred = 0L
    private val startNanos = ticker()
    private val samples = ArrayDeque<LongArray>() // [nanos, transferred]
    private var lastSampleNanos = Long.MIN_VALUE
    private var lastEmitNanos = Long.MIN_VALUE

    val doneBytes: Long get() = completedBytes + currentBytes.coerceAtMost(currentSize)

    fun startFile(name: String, size: Long) {
        currentName = name
        currentSize = size
        currentBytes = 0
        maybeEmit()
    }

    /** 재시도로 처음부터 다시 읽을 때 현재 파일 진행분을 되돌린다 */
    fun restartFile() {
        currentBytes = 0
    }

    fun onTransferred(bytes: Int, countsForCurrentFile: Boolean = true) {
        transferred += bytes
        if (countsForCurrentFile) currentBytes += bytes
        sample()
        maybeEmit()
    }

    fun finishFile(failed: Boolean = false) {
        doneFiles++
        completedBytes += currentSize
        currentBytes = 0
        currentSize = 0
        if (failed) failedCount++
        maybeEmit()
    }

    fun emitNow() {
        sample(force = true)
        lastEmitNanos = ticker()
        emit(snapshot())
    }

    private fun maybeEmit() {
        val now = ticker()
        if (lastEmitNanos != Long.MIN_VALUE && now - lastEmitNanos < minIntervalNanos) return
        lastEmitNanos = now
        emit(snapshot())
    }

    private fun sample(force: Boolean = false) {
        val now = ticker()
        if (!force && lastSampleNanos != Long.MIN_VALUE && now - lastSampleNanos < SAMPLE_NANOS) return
        lastSampleNanos = now
        samples.addLast(longArrayOf(now, transferred))
        // 3초 창: 창 시작 직전 표본 하나는 기준점으로 남긴다
        while (samples.size > 2 && now - samples[1][0] >= WINDOW_NANOS) samples.removeFirst()
    }

    fun bytesPerSecond(): Long {
        val now = ticker()
        val first = samples.firstOrNull() ?: return 0
        val dt = now - first[0]
        if (dt < MIN_SPEED_NANOS) return 0
        val bytes = transferred - first[1]
        return (bytes * 1_000_000_000.0 / dt).toLong().coerceAtLeast(0)
    }

    fun snapshot(): ImportProgress {
        val speed = bytesPerSecond()
        val elapsed = ticker() - startNanos
        val remaining = (totalBytes - doneBytes).coerceAtLeast(0)
        val eta = if (speed > 0 && elapsed >= MIN_ETA_NANOS) (remaining + speed - 1) / speed else null
        return ImportProgress(
            sessionId = sessionId,
            totalFiles = totalFiles,
            doneFiles = doneFiles,
            totalBytes = totalBytes,
            doneBytes = doneBytes.coerceAtMost(totalBytes.coerceAtLeast(0)),
            currentFileName = currentName,
            bytesPerSecond = speed,
            etaSeconds = eta,
            failedCount = failedCount,
            startedAt = startedAt,
        )
    }

    private companion object {
        const val WINDOW_NANOS = 3_000_000_000L
        const val SAMPLE_NANOS = 100_000_000L
        const val MIN_SPEED_NANOS = 500_000_000L
        const val MIN_ETA_NANOS = 2_000_000_000L
    }
}
