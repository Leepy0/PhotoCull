package com.leepy0.photocull.analysis

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.leepy0.photocull.data.db.AppDatabase
import com.leepy0.photocull.data.db.MediaGroupEntity
import com.leepy0.photocull.data.db.MediaItemEntity
import com.leepy0.photocull.data.db.ReviewState
import com.leepy0.photocull.data.settings.AppSettings
import com.leepy0.photocull.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
 * - 그룹 계산은 별도 코루틴이 맡아, 긴 분석 중에도 설정 변경([regroup])이 바로 반영된다.
 * - 항목 하나의 실패(손상 파일, 메모리 부족 등)는 그 항목만 analysisFailed로 기록하고 계속한다. 프로세스를 죽이지 않는다.
 * - DB 쓰기는 방금 읽은 행에 분석 필드만 덮어써서, 그 사이 바뀐 검토 상태(휴지통 이동 등)를 되돌리지 않는다.
 *
 * @param lingerMs 일이 끝난 뒤 '분석 중' 표시를 유지하며 다음 신호를 기다리는 시간. 가져오는 동안 파일마다 표시가 깜박이지 않게 한다.
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
) : AnalysisController {

    private val mediaDao get() = db.mediaDao()

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
                // 패스가 끝날 때마다 그룹을 다시 계산(바뀐 게 없으면 DB는 그대로 둔다)
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
        var lastRegroupAt = clock()
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
                    // 긴 분석 중에도 그룹이 주기적으로 보이게
                    if (clock() - lastRegroupAt >= MID_PASS_REGROUP_MS) {
                        regroup()
                        lastRegroupAt = clock()
                    }
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
            imageAnalyzer.analyze(path)
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
            videoAnalyzer.analyze(path, item.capturedAt)
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
                done = 0
            }
            publishLocked()
        }
    }

    private suspend fun endBusy() {
        progressLock.withLock {
            if (!busy) return
            busy = false
            publishLocked()
        }
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
        for (signal in regroupSignals) {
            val target = regroupRequested.get()
            try {
                regroupNow()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "regroup failed", t)
            }
            regroupCompleted.update { maxOf(it, target) }
        }
    }

    /**
     * 현재 설정으로 그룹 전체를 다시 계산해 교체한다. 읽기부터 쓰기까지 한 트랜잭션이라,
     * 그 사이 사용자가 베스트를 고정해도 잃어버리지 않는다. 결과가 지금과 같으면 쓰지 않는다(화면 깜박임 방지).
     */
    private suspend fun regroupNow() {
        val s = try {
            settings.current()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            AppSettings()
        }
        db.withTransaction {
            val rows = mediaDao.groupingCandidates()
            val existing = db.groupDao().all()
            val pinned = existing.filter { it.bestPinned }.map { it.bestItemId }.toSet()
            val planned = Grouping.group(
                rows.map { it.toGroupInput() },
                burstGapMs = s.burstGapMs,
                similarGapMs = s.similarGapMs,
                maxDistance = s.similarMaxDistance,
                pinnedBestIds = pinned,
            )
            val previousGroupOf = HashMap<Long, Long>()
            rows.forEach { r -> r.groupId?.let { previousGroupOf[r.id] = it } }
            val ids = Grouping.assignIds(planned, previousGroupOf, existing.map { it.id }.toSet())
            val entities = planned.mapIndexed { i, g ->
                MediaGroupEntity(
                    id = ids[i] ?: 0,
                    kind = g.kind,
                    bestItemId = g.bestId,
                    bestPinned = g.bestPinned,
                    count = g.memberIds.size,
                    startAt = g.startAt,
                    endAt = g.endAt,
                )
            }

            val unchanged = entities.none { it.id == 0L } &&
                entities.toSet() == existing.toSet() &&
                run {
                    val plannedGroupOf = HashMap<Long, Long>()
                    entities.forEachIndexed { i, e -> planned[i].memberIds.forEach { plannedGroupOf[it] = e.id } }
                    rows.all { it.groupId == plannedGroupOf[it.id] }
                }
            if (!unchanged) {
                db.groupingDao().replaceAll(entities.mapIndexed { i, e -> e to planned[i].memberIds })
            }
        }
    }

    private data class GroupingParams(val burstGapMs: Long, val similarGapMs: Long, val maxDistance: Int)

    companion object {
        private const val TAG = "Analysis"
        private const val PHOTO_PARALLELISM = 2
        private const val CHUNK_SIZE = 16
        private const val MID_PASS_REGROUP_MS = 15_000L
        private val ACTIVE_STATES = setOf(ReviewState.PENDING, ReviewState.KEPT)
    }
}
