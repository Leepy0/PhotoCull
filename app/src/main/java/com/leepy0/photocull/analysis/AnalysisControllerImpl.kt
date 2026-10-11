package com.leepy0.photocull.analysis

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import com.leepy0.photocull.data.db.AppDatabase
import com.leepy0.photocull.data.db.GroupingDiff
import com.leepy0.photocull.data.db.MediaGroupEntity
import com.leepy0.photocull.data.db.MediaItemEntity
import com.leepy0.photocull.data.db.ReviewState
import com.leepy0.photocull.data.settings.AppSettings
import com.leepy0.photocull.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicLong

/**
 * 분석 작업자(DESIGN.md §4). 앱 범위 코루틴 하나가 순서대로 처리한다.
 *
 * - [kick]: 신호만 보낸다(합쳐짐). 작업자는 분석 대기 사진을 동시 2장씩 처리하고, 이어서 영상, 그다음 그룹을 다시 계산한다.
 *   처리하는 동안 새 항목이 들어오면 이어서 처리한다.
 * - 그룹 계산은 별도 코루틴이 맡는다. 분석 중에는 [regroupIntervalMs]에 한 번까지만(가져오는 동안 사진마다 돌지 않게),
 *   분석이 쉬면 바로 계산한다. 계산은 트랜잭션 밖에서 하고, 바뀐 그룹·행만 짧은 트랜잭션으로 쓴다.
 * - 항목 하나의 실패(손상 파일, 메모리 부족, [itemTimeoutMs] 초과 등)는 그 항목만 analysisFailed로 기록하고 계속한다. 프로세스를 죽이지 않는다.
 * - DB 쓰기는 방금 읽은 행에 분석 필드만 덮어써서, 그 사이 바뀐 검토 상태(휴지통 이동 등)를 되돌리지 않는다.
 *
 * @param lingerMs 일이 끝난 뒤 '분석 중' 표시를 유지하며 다음 신호를 기다리는 시간. 가져오는 동안 파일마다 표시가 깜박이지 않게 한다.
 * @param regroupIntervalMs 분석 중 그룹 재계산 최소 간격
 * @param itemTimeoutMs 항목 하나(디코드·메타데이터 읽기) 시간 제한. 멈춘 파일 하나가 작업자 전체를 세우지 않게 한다.
 * @param progressResetIdleMs 이만큼 쉬었다가 다시 시작해야 진행 수(done)를 0부터 센다. 가져오는 중 큰 영상을 복사하느라
 *   잠깐 쉬어도 "분석 n/m"이 처음부터 다시 시작하지 않게 한다(4GB 영상 복사 ≈ 45초).
 */
class AnalysisControllerImpl(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val scope: CoroutineScope,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val imageAnalyzer: ImageAnalyzer = ImageAnalyzer(),
    private val videoAnalyzer: VideoAnalyzer = VideoAnalyzer(),
    private val lingerMs: Long = 1_500,
    private val clock: () -> Long = System::currentTimeMillis,
    private val regroupIntervalMs: Long = 5_000,
    private val itemTimeoutMs: Long = 60_000,
    private val progressResetIdleMs: Long = 60_000,
) : AnalysisController {

    private val mediaDao get() = db.mediaDao()

    /** 항목 분석을 돌리는 범위. 시간 제한을 넘긴(멈춘) 작업은 기다리지 않고 버리는데, 그 실패가 앱 범위로 번지지 않게 감독 작업 아래에 둔다. */
    private val itemScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    /** 테스트용: 그룹 계산이 끝나고 쓰기 직전에 불린다(그 사이 사용자 동작을 흉내 낸다). */
    @VisibleForTesting
    internal var beforeRegroupWrite: (suspend () -> Unit)? = null

    private val _status = MutableStateFlow(AnalysisStatus())
    override val status: StateFlow<AnalysisStatus> = _status.asStateFlow()

    // ---- 분석 작업자 ----
    private val workSignals = Channel<Unit>(Channel.CONFLATED)
    private val workRequested = AtomicLong(0)
    private val workCompleted = MutableStateFlow(0L)

    // ---- 그룹 계산 ----
    private val regroupSignals = Channel<Unit>(Channel.CONFLATED)
    private val regroupRequested = AtomicLong(0)
    private val regroupCompleted = MutableStateFlow(0L)

    /** 진행 상태와 DB 쓰기를 묶어 done/total이 어긋나지 않게 한다. */
    private val progressLock = Mutex()
    @Volatile private var busy = false
    private var done = 0
    private var lastPending = 0
    /** 마지막으로 쉬기 시작한 때(단조 시계 ms). null = 아직 한 번도 일하지 않음 */
    private var idleSince: Long? = null

    /** 분석 작업자가 쉬는 중(그룹 계산 간격 조절용) */
    private val workerIdle = MutableStateFlow(true)

    init {
        scope.launch { workLoop() }
        scope.launch { regroupLoop() }
        scope.launch {
            settings.settings
                .map { GroupingParams(it.burstGapMs, it.similarGapMs, it.similarMaxDistance) }
                .distinctUntilChanged()
                .drop(1) // 첫 값은 현재 설정: 시작할 때의 kick이 어차피 그룹을 계산한다.
                .catch { Log.w(TAG, "settings flow failed", it) }
                .collect { regroup() }
        }
    }

    override fun kick() {
        workRequested.incrementAndGet()
        workSignals.trySend(Unit)
    }

    override fun regroup() {
        regroupRequested.incrementAndGet()
        regroupSignals.trySend(Unit)
    }

    override suspend fun awaitIdle() {
        // DB에 남은 대기 항목까지 확실히 처리되도록 한 번 더 돌린다(남은 게 없으면 바로 끝난다).
        kick()
        val work = workRequested.get()
        workCompleted.first { it >= work }
        val group = regroupRequested.get()
        regroupCompleted.first { it >= group }
    }

    // =====================================================================================
    // 분석 루프
    // =====================================================================================

    private suspend fun workLoop() {
        while (true) {
            workSignals.receive()
            do {
                val target = workRequested.get()
                try {
                    runPass()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "analysis pass failed", t)
                }
                // 패스가 끝날 때마다 그룹 재계산 요청(분석 중에는 간격을 두고 합쳐서, 쉬면 바로 계산. 바뀐 게 없으면 DB는 그대로)
                regroup()
                workCompleted.update { maxOf(it, target) }
                val more = if (busy && lingerMs > 0) {
                    withTimeoutOrNull(lingerMs) { workSignals.receive() } != null
                } else {
                    workSignals.tryReceive().isSuccess
                }
            } while (more)
            endBusy()
        }
    }

    /** 대기 중인 사진 → 영상 순으로 비운다. 처리하는 동안 새로 들어온 항목도 이어서 처리한다. */
    private suspend fun runPass() {
        val attempted = HashSet<Long>()
        while (true) {
            var progressed = false

            while (true) {
                val photos = mediaDao.pendingPhotoAnalysis().filter { it.id !in attempted }
                if (photos.isEmpty()) break
                progressed = true
                beginBusy()
                for (chunk in photos.chunked(CHUNK_SIZE)) {
                    chunk.forEach { attempted += it.id }
                    val outOfMemory = analyzePhotos(chunk, PHOTO_PARALLELISM, lastChance = false)
                    // 메모리 부족은 동시 처리 때문일 수 있으니 한 장씩 한 번 더 시도
                    if (outOfMemory.isNotEmpty()) analyzePhotos(outOfMemory, 1, lastChance = true)
                    // 긴 분석 중에도 그룹이 주기적으로 보이게(그룹 계산 쪽이 간격을 조절한다)
                    regroup()
                }
            }

            while (true) {
                val videos = mediaDao.pendingVideoAnalysis().filter { it.id !in attempted }
                if (videos.isEmpty()) break
                progressed = true
                beginBusy()
                for (video in videos) {
                    attempted += video.id
                    analyzeVideo(video)
                }
            }

            if (!progressed) break
        }
    }

    /** @return 메모리 부족으로 실패해 다시 시도할 항목 */
    private suspend fun analyzePhotos(items: List<MediaItemEntity>, parallelism: Int, lastChance: Boolean): List<MediaItemEntity> =
        coroutineScope {
            val permits = Semaphore(parallelism)
            items.map { item ->
                async { permits.withPermit { if (analyzePhoto(item, lastChance)) null else item } }
            }.awaitAll().filterNotNull()
        }

    /** @return false = 메모리 부족(다시 시도 대상) */
    private suspend fun analyzePhoto(item: MediaItemEntity, lastChance: Boolean): Boolean {
        val path = item.dstPath ?: return true
        val result = try {
            withItemTimeout(item) { imageAnalyzer.analyze(path) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            if (!lastChance) return false
            Log.w(TAG, "out of memory analyzing ${item.fileName}")
            markFailed(item, missingFile = false)
            return true
        } catch (e: FileNotFoundException) {
            markFailed(item, missingFile = true)
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "photo analysis failed: ${item.fileName}", t)
            markFailed(item, missingFile = false)
            return true
        }
        record(item.id) { fresh ->
            fresh.copy(
                sharpness = result.sharpness,
                focusX = result.focusX,
                focusY = result.focusY,
                dhash = result.dhash,
                width = result.width,
                height = result.height,
                capturedAt = result.capturedAt ?: fresh.capturedAt,
                iso = result.iso ?: fresh.iso,
                exposure = result.exposure ?: fresh.exposure,
                aperture = result.aperture ?: fresh.aperture,
                focalLength = result.focalLength ?: fresh.focalLength,
                analysisFailed = false,
                updatedAt = clock(),
            )
        }
        return true
    }

    private suspend fun analyzeVideo(item: MediaItemEntity) {
        val path = item.dstPath ?: return
        val result = try {
            withItemTimeout(item) { videoAnalyzer.analyze(path, item.capturedAt) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: FileNotFoundException) {
            markFailed(item, missingFile = true)
            return
        } catch (t: Throwable) {
            Log.w(TAG, "video analysis failed: ${item.fileName}", t)
            markFailed(item, missingFile = false)
            return
        }
        record(item.id) { fresh ->
            fresh.copy(
                durationMs = result.durationMs,
                width = result.width ?: fresh.width,
                height = result.height ?: fresh.height,
                capturedAt = result.capturedAt ?: fresh.capturedAt,
                analysisFailed = false,
                updatedAt = clock(),
            )
        }
    }

    /**
     * [block]을 [itemTimeoutMs] 안에서 실행한다. 디코더·MediaMetadataRetriever의 네이티브 호출은 취소로 끊을 수 없어서,
     * 별도 작업으로 돌리고 시간이 지나면 기다리기를 그만둔다(그 작업은 버린다). 초과하면 [ItemTimeoutException].
     */
    private suspend fun <T : Any> withItemTimeout(item: MediaItemEntity, block: suspend () -> T): T {
        val work = itemScope.async { block() }
        val result = withTimeoutOrNull(itemTimeoutMs) { work.await() }
        if (result == null) {
            work.cancel()
            throw ItemTimeoutException(item.fileName, itemTimeoutMs)
        }
        return result
    }

    /**
     * 실패 기록. 단, 분석하는 사이 휴지통으로 옮겨졌거나 경로가 바뀐 항목은 실패로 남기지 않는다
     * (복원되면 다시 분석 대상이 된다). 파일이 없어서 실패했는데 지금은 파일이 있으면(잠깐 옮겨졌다 돌아옴) 다음 기회에 다시 시도한다.
     */
    private suspend fun markFailed(item: MediaItemEntity, missingFile: Boolean) {
        record(item.id) { fresh ->
            val moved = fresh.reviewState !in ACTIVE_STATES || fresh.dstPath != item.dstPath
            val reappeared = missingFile && fresh.dstPath?.let { File(it).isFile } == true
            if (moved || reappeared) null else fresh.copy(analysisFailed = true, updatedAt = clock())
        }
    }

    /**
     * 최신 행을 읽어 [transform] 결과로 저장(한 트랜잭션: 그 사이 다른 쓰기가 끼어들지 않는다).
     * 저장 뒤 진행 상태를 갱신한다. DB 오류는 이 항목만 건너뛴다.
     */
    private suspend fun record(id: Long, transform: (MediaItemEntity) -> MediaItemEntity?) {
        progressLock.withLock {
            try {
                db.withTransaction {
                    val fresh = mediaDao.get(id) ?: return@withTransaction
                    transform(fresh)?.let { mediaDao.update(it) }
                }
                done++
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "failed to save analysis result for item $id", t)
            }
            publishLocked()
        }
    }

    private suspend fun beginBusy() {
        progressLock.withLock {
            if (!busy) {
                busy = true
                workerIdle.value = false
                // 잠깐 쉬었다 다시 시작한 것(가져오는 중 큰 영상 복사 등)이면 이어서 센다
                val since = idleSince
                if (since == null || elapsedMs() - since >= progressResetIdleMs) done = 0
            }
            publishLocked()
        }
    }

    private suspend fun endBusy() {
        progressLock.withLock {
            if (!busy) return
            busy = false
            idleSince = elapsedMs()
            publishLocked()
        }
        workerIdle.value = true
    }

    /** total = 처리한 수 + 아직 남은 수(DB). 쓰기와 같은 잠금 안에서 세므로 둘이 어긋나지 않는다. */
    private suspend fun publishLocked() {
        val pending = try {
            mediaDao.observePendingAnalysisCount().first()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            lastPending
        }
        lastPending = pending
        _status.value = AnalysisStatus(running = busy, done = done, total = done + pending)
    }

    // =====================================================================================
    // 그룹 계산
    // =====================================================================================

    private suspend fun regroupLoop() {
        var lastRunAt: Long? = null
        for (signal in regroupSignals) {
            // 분석 중에는 마지막 계산 뒤 regroupIntervalMs까지 신호를 모은다. 그 전에 분석이 쉬면 바로 계산한다.
            val last = lastRunAt
            if (last != null && !workerIdle.value) {
                val wait = last + regroupIntervalMs - elapsedMs()
                if (wait > 0) withTimeoutOrNull(wait) { workerIdle.first { it } }
            }
            regroupSignals.tryReceive() // 기다리는 동안 온 신호는 이번 계산에 합친다
            val target = regroupRequested.get()
            try {
                regroupNow()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "regroup failed", t)
            }
            lastRunAt = elapsedMs()
            regroupCompleted.update { maxOf(it, target) }
        }
    }

    /**
     * 현재 설정으로 그룹 전체를 다시 계산하고 바뀐 부분만 쓴다.
     * - 읽기·계산은 트랜잭션 밖: 가져오기의 DB 쓰기를 막지 않는다.
     * - 쓰기는 짧은 트랜잭션 하나. 그 안에서 그룹을 다시 읽어, 계산하는 사이 사용자가 고정한 베스트가 있으면
     *   그것으로 다시 계산한다(고정을 잃지 않는다).
     * - 바뀐 게 없으면 쓰지 않는다(화면 깜박임·불필요한 Flow 재조회 방지).
     */
    private suspend fun regroupNow() {
        val s = try {
            settings.current()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            AppSettings()
        }
        val inputs = mediaDao.groupingCandidates().map { it.toGroupInput() }
        val currentGroupOf = HashMap<Long, Long>()
        db.groupingDao().groupRefs().forEach { currentGroupOf[it.id] = it.groupId }
        val existing = db.groupDao().all()
        val diff = plan(inputs, existing, currentGroupOf, s)
        if (diff.isEmpty) return

        beforeRegroupWrite?.invoke()
        db.withTransaction {
            val fresh = db.groupDao().all()
            val finalDiff = if (fresh.toSet() == existing.toSet()) diff else plan(inputs, fresh, currentGroupOf, s)
            if (!finalDiff.isEmpty) db.groupingDao().applyDiff(finalDiff)
        }
    }

    /** 그룹 계산(메모리 안 순수 계산) → 지금 상태와의 차이 */
    private fun plan(
        inputs: List<GroupInput>,
        existing: List<MediaGroupEntity>,
        currentGroupOf: Map<Long, Long>,
        s: AppSettings,
    ): GroupingDiff {
        val pinned = existing.filter { it.bestPinned }.mapTo(HashSet()) { it.bestItemId }
        val planned = Grouping.group(
            inputs,
            burstGapMs = s.burstGapMs,
            similarGapMs = s.similarGapMs,
            maxDistance = s.similarMaxDistance,
            pinnedBestIds = pinned,
        )
        val ids = Grouping.assignIds(planned, currentGroupOf, existing.mapTo(HashSet()) { it.id })
        return Grouping.diff(planned, ids, existing, currentGroupOf)
    }

    private data class GroupingParams(val burstGapMs: Long, val similarGapMs: Long, val maxDistance: Int)

    companion object {
        private const val TAG = "Analysis"
        private const val PHOTO_PARALLELISM = 2
        private const val CHUNK_SIZE = 16
        private val ACTIVE_STATES = setOf(ReviewState.PENDING, ReviewState.KEPT)

        /** 간격 계산용 단조 시계(벽시계 변경에 영향받지 않는다) */
        private fun elapsedMs(): Long = System.nanoTime() / 1_000_000
    }
}

/** 항목 하나의 분석이 시간 제한을 넘김(멈춘 디코더 등) */
class ItemTimeoutException(fileName: String, timeoutMs: Long) : Exception("analysis timed out after $timeoutMs ms: $fileName")
