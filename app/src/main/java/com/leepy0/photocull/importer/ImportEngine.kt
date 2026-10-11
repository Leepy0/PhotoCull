package com.leepy0.photocull.importer

import com.leepy0.photocull.data.db.ImportSessionEntity
import com.leepy0.photocull.data.db.ImportStatus
import com.leepy0.photocull.data.db.MediaItemEntity
import com.leepy0.photocull.data.db.MediaType
import com.leepy0.photocull.data.db.SessionState
import com.leepy0.photocull.storage.CardFile
import com.leepy0.photocull.storage.CardFileTypes
import com.leepy0.photocull.storage.CardSource
import com.leepy0.photocull.storage.StoragePaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.abs

data class ImportRequest(
    val plan: ScanPlan,
    /** true = 검증 후 카드 원본 삭제 */
    val moveMode: Boolean,
    val destFolderName: String,
    val monthlySubfolders: Boolean,
    /** 기본 저장소 루트(/storage/emulated/0). 테스트는 임시 폴더 */
    val storageRoot: File = StoragePaths.primaryRoot,
)

/** [error]가 있으면 중간에 멈춘 것(카드 분리·공간 부족 등). [result]는 그때까지의 결과. */
data class EngineOutcome(val result: ImportResult, val error: ImportError?)

/**
 * 카드 → 폰 이동의 핵심. DESIGN §3.3 순서를 그대로 따른다.
 *
 * 안전 불변식: 카드 원본은 폰에 같은 SHA-256의 복사본이 디스크까지 기록(fsync)된 뒤에만 지운다.
 * - 새 파일: `.photocull.part`로 복사하며 해시 → fsync → 다시 읽어 해시 비교(불일치면 1회 재시도)
 *   → 이름 변경 → 이름 변경도 fsync → VERIFIED
 * - 같은 이름에 같은 내용(크기+해시)이 이미 있으면 DUPLICATE(복사 생략)
 * - 이전 기록과 같은 파일은 복사 생략. 이동 모드면 기록된 복사본과 카드 파일의 해시를 모두 다시 확인한 뒤에만 원본 삭제
 * - 원본 삭제는 촬영 단위의 모든 파일 처리가 끝난 뒤. 중간에 멈추면 단위가 카드에 함께 남는다.
 * - DB는 가져오기 열만 바꾼다(분석·검토가 같은 행을 동시에 쓴다). [ImportLedger] 참고
 *
 * 디스크·DB 작업이 많으므로 IO 디스패처에서 부른다.
 */
class ImportEngine(
    private val ledger: ImportLedger,
    private val io: DestinationFiles = DestinationFiles.Default,
    private val mediaScanner: MediaScanSink = MediaScanSink.None,
    /** 대표 사진·영상이 검증될 때마다(분석 시작 신호) */
    private val onMediaVerified: () -> Unit = {},
    private val captureTime: CaptureTimeReader = ExifCaptureTimeReader,
    private val freeSpace: (File) -> Long = ::usableSpace,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ticker: () -> Long = System::nanoTime,
    /** 읽기 오류 후 볼륨이 사라지는지 기다리는 시간(분리 직후엔 아직 MOUNTED로 보일 수 있다) */
    private val removalGraceMs: Long = 2_000,
    private val progressIntervalMs: Long = 250,
) {

    suspend fun run(
        request: ImportRequest,
        source: CardSource,
        cancel: CancelSignal = CancelSignal(),
        onProgress: (ImportProgress) -> Unit = {},
    ): EngineOutcome = Run(request, source, cancel, onProgress, currentCoroutineContext()[Job]).execute()

    private data class LedgerKey(val relPath: String, val size: Long, val lastModified: Long)

    private sealed interface Slot {
        data class New(val target: File) : Slot
        data class Duplicate(val existing: File, val sha256: String) : Slot
    }

    /**
     * 단위 처리가 끝나면 지울 카드 원본과 그 근거가 된 폰 복사본.
     * [synced]: 이번 실행에서 복사본(이름 변경 포함)을 이미 fsync했는지. 아니면 지우기 전에 한다.
     */
    private class PendingDelete(val file: CardFile, val rowId: Long, val copy: File, val synced: Boolean)

    // 흐름 제어용 신호(외부로 나가지 않음)
    private class SourceReadException(cause: Throwable) : Exception(cause)
    private class DestWriteException(cause: Throwable) : Exception(cause)
    private class CardRemovedSignal : Exception()
    private class SpaceSignal(val required: Long, val free: Long) : Exception()
    private class CancelledSignal : Exception()

    private inner class Run(
        private val request: ImportRequest,
        private val source: CardSource,
        private val cancel: CancelSignal,
        private val onProgress: (ImportProgress) -> Unit,
        private val job: Job?,
    ) {
        private val plan = request.plan
        private val volume = plan.volume
        private val destRoot = StoragePaths.destRoot(request.destFolderName, request.storageRoot)
        private val buffer = ByteArray(BUFFER_SIZE)

        private lateinit var session: ImportSessionEntity
        private lateinit var progress: ProgressTracker
        private var storedByKey: Map<LedgerKey, List<StoredRef>> = emptyMap()
        private var siblingsByBase: Map<String, List<StoredRef>> = emptyMap()
        private val sourceHashes = HashMap<String, String>()

        private var saved = 0
        private var duplicates = 0
        private var failed = 0
        private var deleteFailed = 0
        /** 이번 실행에서 지운 카드 원본 수 / 카드가 그대로 있는데 못 지운 수 */
        private var deletedSources = 0
        private var undeletable = 0
        /** 처음 몇 개가 모두 지워지지 않으면 쓰기 금지 카드로 보고 남은 삭제는 시도하지 않는다 */
        private var sourceReadOnly = false
        private var savedBytes = 0L
        private var savedPhotos = 0
        private var savedVideos = 0
        private val failures = ArrayList<ImportFailure>()
        private val scanPaths = ArrayList<String>()
        private val scanMimes = ArrayList<String>()

        suspend fun execute(): EngineOutcome {
            val startedAt = clock()
            val summary = plan.summary
            session = ImportSessionEntity(
                volumeId = volume.id,
                volumeLabel = volume.label,
                startedAt = startedAt,
                moveMode = request.moveMode,
                totalFiles = summary.newFileCount,
                totalBytes = summary.newBytes,
            )
            session = session.copy(id = ledger.insertSession(session))
            progress = ProgressTracker(
                sessionId = session.id,
                totalFiles = summary.newFileCount,
                totalBytes = summary.newBytes,
                startedAt = startedAt,
                ticker = ticker,
                minIntervalNanos = progressIntervalMs * 1_000_000,
                emit = onProgress,
            )

            var error: ImportError? = null
            var cancelled = false
            var endState = SessionState.COMPLETED
            try {
                if (!destRoot.isDirectory && !destRoot.mkdirs()) throw IOException("destination not writable")
                // 남은 .part: 무조건 지우지 않는다(이름 변경이 디스크에 남지 못한 유일한 복사본일 수 있다)
                val parts = PartFileRecovery(ledger, io, clock)
                for (part in PartFiles.list(destRoot)) parts.handle(part)
                loadLedger()
                progress.emitNow()
                for (unit in plan.units) {
                    checkCancelled()
                    processUnit(unit)
                    saveSession(SessionState.RUNNING)
                }
            } catch (e: CardRemovedSignal) {
                error = ImportError.CardRemoved(progress.doneFiles, summary.newFileCount)
                endState = SessionState.INTERRUPTED
            } catch (e: SpaceSignal) {
                error = ImportError.InsufficientSpace(e.required, e.free)
                endState = SessionState.INTERRUPTED
            } catch (e: CancelledSignal) {
                cancelled = true
                endState = SessionState.CANCELLED
            } catch (e: CancellationException) {
                // 서비스 종료 등으로 코루틴이 취소됨: 사용자 취소와 같이 정리한다
                cancelled = true
                endState = SessionState.CANCELLED
            } catch (e: Exception) {
                error = ImportError.Unexpected(e.javaClass.simpleName ?: "Exception")
                endState = SessionState.INTERRUPTED
            }

            withContext(NonCancellable) {
                flushScan()
                runCatching { saveSession(endState, finishedAt = clock()) }
            }
            progress.emitNow()
            val result = ImportResult(
                sessionId = session.id,
                moveMode = request.moveMode,
                savedCount = saved,
                duplicateCount = duplicates,
                failedCount = failed,
                sourceDeleteFailedCount = deleteFailed,
                savedBytes = savedBytes,
                durationMs = (clock() - startedAt).coerceAtLeast(0),
                cancelled = cancelled,
                failures = failures.toList(),
                photoCount = savedPhotos,
                videoCount = savedVideos,
                destDisplayPath = summary.destDisplayPath,
            )
            return EngineOutcome(result, error)
        }

        private suspend fun saveSession(state: SessionState, finishedAt: Long? = null) {
            session = session.copy(
                state = state,
                finishedAt = finishedAt ?: session.finishedAt,
                successCount = saved,
                duplicateCount = duplicates,
                failedCount = failed,
                sourceDeleteFailedCount = deleteFailed,
            )
            ledger.updateSession(session)
        }

        private suspend fun loadLedger() {
            val refs = ledger.storedRecords(volume.id).sortedByDescending { it.id }
            storedByKey = refs.groupBy { LedgerKey(it.srcRelPath, it.srcSize, it.srcLastModified) }
            siblingsByBase = refs
                .filter { it.type != MediaType.VIDEO && !UnitKeys.isDetached(it.unitKey) && it.dstPath != null }
                .groupBy { CardFileTypes.withoutExtension(it.srcRelPath).lowercase(Locale.ROOT) }
        }

        private fun checkCancelled() {
            if (cancel.isCancelled) throw CancelledSignal()
            if (job != null && !job.isActive) throw CancellationException("import job cancelled")
        }

        // ---------------------------------------------------------------- 단위

        private suspend fun processUnit(unit: ShotUnit) {
            val deletes = ArrayList<PendingDelete>()
            val newFiles = ArrayList<UnitFile>()
            for (uf in unit.files) {
                val refs = storedByKey[LedgerKey(uf.file.relPath, uf.file.size, uf.file.lastModified)]
                if (refs.isNullOrEmpty()) newFiles += uf else handleAlreadyImported(uf, refs, deletes)
            }
            if (newFiles.isNotEmpty()) importNewFiles(unit, newFiles, deletes)
            if (request.moveMode) {
                for (d in deletes) deleteSource(d)
            }
        }

        /**
         * 이전에 가져온 파일: 복사하지 않는다. 이동 모드면 기록된 폰 복사본을 다시 확인하고,
         * 카드 파일도 기록된 해시와 같은지 읽어 본 뒤에만 원본을 지운다(경로·크기·수정 시각만 같은 다른 파일일 수 있다).
         */
        private suspend fun handleAlreadyImported(uf: UnitFile, refs: List<StoredRef>, deletes: MutableList<PendingDelete>) {
            duplicates++
            if (!request.moveMode) return
            checkCancelled()
            val ref = refs.firstOrNull { verifyStoredCopy(it, uf.file.size) } ?: return // 확인 못 하면 카드에 남긴다
            if (sourceHash(uf.file) != ref.sha256) return
            deletes += PendingDelete(uf.file, ref.id, File(requireNotNull(ref.dstPath)), synced = false)
        }

        private fun verifyStoredCopy(ref: StoredRef, size: Long): Boolean {
            val path = ref.dstPath ?: return false
            val sha = ref.sha256 ?: return false
            val copy = File(path)
            if (!copy.isFile || copy.length() != size) return false
            return Sha256.ofFile(io, copy, buffer) == sha
        }

        private suspend fun importNewFiles(unit: ShotUnit, newFiles: List<UnitFile>, deletes: MutableList<PendingDelete>) {
            // 같은 촬영의 일부가 이전에 저장됐으면(예: JPG는 옮겨졌고 CR3 복사 중 카드 분리) 그 단위에 이어 붙인다:
            // 같은 단위 키, 같은 폴더, 같은 접미사.
            val anchor = findAnchor(unit, newFiles)
            var unitKey = anchor?.unitKey ?: UnitKeys.unit(session.id, unit.keyPath)
            var capturedAt = anchor?.capturedAt ?: captureTimeOf(unit)
            var storedPrimary = anchor != null &&
                siblingsOf(unit).any { it.unitKey == anchor.unitKey && it.isPrimary }
            val defaultDir = StoragePaths.destDir(request.destFolderName, request.monthlySubfolders, capturedAt, request.storageRoot)
            val preferred = anchor?.let(::preferredSlot)

            val slots = resolveNames(unit, newFiles, defaultDir, preferred)

            // 같은 이름·같은 내용의 파일이 이미 있으면(중복) 그 파일을 검토에 내놓는 주인 행을 찾는다.
            // - 주인이 있으면 그 행이 검토에 나오므로 중복 행은 단위에서 떼어 두고, 이 단위의 새 파일은 주인 단위에 붙인다.
            // - 주인이 없으면(앱 데이터 삭제 뒤 다시 가져오기 등) 기존 파일을 이 단위의 일원으로 들인다(대표 규칙 그대로).
            val owners = HashMap<UnitFile, StoredRef>()
            slots?.forEach { (uf, slot) ->
                if (slot is Slot.Duplicate) ledger.liveOwner(slot.existing.path)?.let { owners[uf] = it }
            }
            if (anchor == null) {
                newFiles.firstNotNullOfOrNull { owners[it] }?.let { owner ->
                    unitKey = owner.unitKey
                    capturedAt = owner.capturedAt
                    storedPrimary = ledger.unitHasLivePrimary(owner.unitKey)
                }
            }
            // 대표 파일: 단위에 이미 대표가 없으면 이번에 단위에 들어가는 첫 파일(JPG > HIF > CR3 순)
            val firstMember = newFiles.firstOrNull { uf ->
                when (slots?.get(uf)) {
                    is Slot.New -> true
                    is Slot.Duplicate -> uf !in owners
                    null -> false
                }
            }

            for (uf in newFiles) {
                checkCancelled()
                val isPrimary = !storedPrimary && uf === firstMember
                when (val slot = slots?.get(uf)) {
                    null -> failWithoutCopy(uf, capturedAt, FailureReason.WRITE_ERROR)
                    is Slot.Duplicate -> recordDuplicate(uf, slot, if (uf in owners) null else unitKey, isPrimary, capturedAt, deletes)
                    is Slot.New -> copyNew(uf, slot.target, unitKey, isPrimary, capturedAt, deletes)
                }
            }
        }

        private fun siblingsOf(unit: ShotUnit): List<StoredRef> =
            if (unit.isVideo) emptyList() else siblingsByBase[unit.keyPath.lowercase(Locale.ROOT)].orEmpty()

        /**
         * 이전에 저장된 같은 폴더·같은 기본 이름의 파일 중 이번 새 파일과 같은 촬영으로 볼 수 있는 것(가장 최근 것).
         * - 수정 시각이 모두 [SAME_EXPOSURE_MS] 안: 한 번의 촬영에서 JPG와 CR3는 1~2초 안에 기록된다.
         *   이동 모드로 카드를 비운 뒤 카메라 파일 번호가 다시 1부터 시작해도 예전의 다른 사진에 붙지 않게 한다.
         *   수정 시각을 모르면(0) 붙이지 않는다.
         * - 그 단위가 살아 있어야 한다(검토 대기·남김). 휴지통·영구 삭제된 단위에 붙으면 검토에 영영 나오지 않는다.
         * - 같은 경로가 다른 내용으로 기록된 단위는 다른 촬영이다.
         */
        private fun findAnchor(unit: ShotUnit, newFiles: List<UnitFile>): StoredRef? {
            val siblings = siblingsOf(unit)
            if (siblings.isEmpty()) return null
            val times = newFiles.map { it.file.lastModified }
            if (times.any { it <= 0 }) return null
            val newPaths = newFiles.mapTo(HashSet()) { it.file.relPath.lowercase(Locale.ROOT) }
            val byUnit = siblings.groupBy { it.unitKey }
            return siblings.firstOrNull { s ->
                s.srcLastModified > 0 &&
                    times.all { abs(it - s.srcLastModified) <= SAME_EXPOSURE_MS } &&
                    byUnit.getValue(s.unitKey).let { members ->
                        members.all { it.isLive } && members.none { it.srcRelPath.lowercase(Locale.ROOT) in newPaths }
                    }
            }
        }

        private suspend fun captureTimeOf(unit: ShotUnit): Long {
            val primary = unit.primary
            // 수정 시각을 모르면(SAF가 0을 줄 때) 1970-01 폴더가 되지 않게 가져온 시각
            val fallback = primary.file.lastModified.takeIf { it > 0 } ?: clock()
            if (primary.kind.mediaType != MediaType.PHOTO) return fallback
            val exif = try {
                captureTime.read(source, primary.file, primary.kind)
            } catch (e: IOException) {
                if (cardGone()) throw CardRemovedSignal()
                null
            } catch (e: RuntimeException) {
                null
            }
            return exif ?: fallback
        }

        /** 기존 형제 파일의 폴더와 접미사("" 또는 "_n") */
        private fun preferredSlot(anchor: StoredRef): Pair<File, String>? {
            val dst = File(anchor.dstPath ?: return null)
            val dir = dst.parentFile ?: return null
            val dstBase = CardFileTypes.baseName(dst.name)
            val srcBase = CardFileTypes.baseName(anchor.srcRelPath.substringAfterLast('/'))
            if (!dstBase.startsWith(srcBase, ignoreCase = true)) return null
            val suffix = dstBase.substring(srcBase.length)
            if (suffix.isNotEmpty() && !SUFFIX.matches(suffix)) return null
            return dir to suffix
        }

        // ---------------------------------------------------------------- 이름 결정

        /**
         * 단위 전체에 같은 접미사: "", "_1", "_2"… 순서로, 단위의 모든 파일 이름이 비어 있거나 같은 내용일 때 사용.
         * 사진 단위는 다른 확장자의 남의 파일(예: 다른 촬영의 IMG_0001.JPG 옆에 이번 IMG_0001.CR3)과도 짝이 되지 않게 한다.
         */
        private suspend fun resolveNames(
            unit: ShotUnit,
            files: List<UnitFile>,
            defaultDir: File,
            preferred: Pair<File, String>?,
        ): Map<UnitFile, Slot>? {
            if (preferred != null) tryAssign(unit, files, preferred.first, preferred.second, exclusive = false)?.let { return it }
            for (i in 0..MAX_SUFFIX) {
                checkCancelled()
                tryAssign(unit, files, defaultDir, if (i == 0) "" else "_$i", exclusive = !unit.isVideo)?.let { return it }
            }
            return null
        }

        private suspend fun tryAssign(
            unit: ShotUnit,
            files: List<UnitFile>,
            dir: File,
            suffix: String,
            exclusive: Boolean,
        ): Map<UnitFile, Slot>? {
            if (exclusive) {
                val ownExts = unit.files.mapTo(HashSet()) { CardFileTypes.extension(it.file.name).lowercase(Locale.ROOT) }
                val base = CardFileTypes.baseName(unit.primary.file.name) + suffix
                val foreign = PAIR_EXTENSIONS.any { ext ->
                    ext.lowercase(Locale.ROOT) !in ownExts && File(dir, "$base.$ext").exists()
                }
                if (foreign) return null
            }
            val out = LinkedHashMap<UnitFile, Slot>()
            for (uf in files) {
                val target = File(dir, targetName(uf.file.name, suffix))
                if (!target.exists()) {
                    out[uf] = Slot.New(target)
                    continue
                }
                if (!target.isFile || target.length() != uf.file.size) return null
                val existing = Sha256.ofFile(io, target, buffer) ?: return null
                val src = sourceHash(uf.file) ?: return null
                if (existing != src) return null
                out[uf] = Slot.Duplicate(target, src)
            }
            return out
        }

        private fun targetName(name: String, suffix: String): String {
            val ext = CardFileTypes.extension(name)
            val base = CardFileTypes.baseName(name)
            return if (ext.isEmpty()) "$base$suffix" else "$base$suffix.$ext"
        }

        /** 같은 크기 파일과 비교하기 위한 카드 원본 해시(파일당 한 번만 읽는다) */
        private suspend fun sourceHash(file: CardFile): String? {
            sourceHashes[file.relPath]?.let { return it }
            val hash = try {
                source.openInput(file).use { input ->
                    val digest = Sha256.newDigest()
                    var total = 0L
                    while (true) {
                        checkCancelled()
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        total += n
                        progress.onTransferred(n, countsForCurrentFile = false)
                    }
                    if (total != file.size) return null
                    Sha256.hex(digest)
                }
            } catch (e: IOException) {
                if (cardGone()) throw CardRemovedSignal()
                return null // 읽기 문제 → 이 접미사는 포기하고 다음 이름으로 복사 시도(복사에서 오류가 정식 처리된다)
            }
            sourceHashes[file.relPath] = hash
            return hash
        }

        // ---------------------------------------------------------------- 파일

        private fun newRow(uf: UnitFile, unitKey: String, isPrimary: Boolean, capturedAt: Long) = MediaItemEntity(
            sessionId = session.id,
            volumeId = volume.id,
            srcRelPath = uf.file.relPath,
            srcLocation = uf.file.location,
            srcSize = uf.file.size,
            srcLastModified = uf.file.lastModified,
            unitKey = unitKey,
            isPrimary = isPrimary,
            type = uf.kind.mediaType,
            mimeType = uf.kind.mimeType,
            fileName = uf.file.name,
            capturedAt = capturedAt,
            updatedAt = clock(),
        )

        /** 폰에 파일이 없는 행: 검토·휴지통 단위에서 떼어낸다 */
        private fun detach(row: MediaItemEntity, code: String) = row.copy(
            importStatus = ImportStatus.FAILED,
            importError = code,
            dstPath = null,
            sha256 = null,
            isPrimary = false,
            unitKey = UnitKeys.detached(row.sessionId, row.srcRelPath),
            updatedAt = clock(),
        )

        private suspend fun copyNew(
            uf: UnitFile,
            target: File,
            unitKey: String,
            isPrimary: Boolean,
            capturedAt: Long,
            deletes: MutableList<PendingDelete>,
        ) {
            val file = uf.file
            progress.startFile(display(file), file.size)
            var row = newRow(uf, unitKey, isPrimary, capturedAt)
                .copy(dstPath = target.path, fileName = target.name, importStatus = ImportStatus.PENDING)
            row = row.copy(id = ledger.insertItem(row))
            val part = PartFiles.partOf(target)
            var verified = false
            /** 최종 이름으로 바뀜(이제 폰에 최종 파일이 있다) */
            var renamed = false
            /** 이름 변경까지 fsync됨 */
            var durable = false
            var reason: FailureReason? = null
            try {
                var attempt = 0
                while (true) {
                    attempt++
                    progress.restartFile()
                    ensureSpace(file.size)
                    val dir = target.parentFile
                    if (dir != null && !dir.isDirectory && !dir.mkdirs()) {
                        reason = FailureReason.WRITE_ERROR
                        break
                    }
                    // 3. .part로 복사하며 해시(카드는 한 번만 읽음) + fsync
                    val sha = try {
                        copyToPart(file, part)
                    } catch (e: SourceReadException) {
                        deleteQuietly(part)
                        if (cardGone()) throw CardRemovedSignal()
                        if (attempt < MAX_ATTEMPTS) continue
                        reason = FailureReason.READ_ERROR
                        break
                    } catch (e: DestWriteException) {
                        deleteQuietly(part)
                        ensureSpace(file.size) // 공간이 바닥났으면 전체 중단
                        reason = FailureReason.WRITE_ERROR
                        break
                    }
                    row = row.copy(importStatus = ImportStatus.COPIED, sha256 = sha, updatedAt = clock())
                    ledger.updateImportFields(row)

                    // 4. .part를 다시 읽어 비교. 불일치면 지우고 1회 재시도
                    val check = Sha256.ofFile(io, part, buffer)
                    if (check != sha || part.length() != file.size) {
                        deleteQuietly(part)
                        row = row.copy(importStatus = ImportStatus.PENDING, sha256 = null, updatedAt = clock())
                        ledger.updateImportFields(row)
                        if (attempt < MAX_ATTEMPTS) continue
                        reason = FailureReason.VERIFY_FAILED
                        break
                    }

                    // 5. 최종 이름(기존 파일은 절대 덮어쓰지 않음), 수정 시각, VERIFIED
                    if (!io.moveNoReplace(part, target)) {
                        deleteQuietly(part)
                        reason = FailureReason.WRITE_ERROR
                        break
                    }
                    renamed = true
                    // 이름 변경까지 디스크에 기록. 실패하면 복사본은 두되 카드 원본은 지우기 전에 다시 시도한다
                    durable = try {
                        io.syncRenamed(target)
                        true
                    } catch (e: Exception) {
                        false
                    }
                    if (file.lastModified > 0) runCatching { target.setLastModified(file.lastModified) }
                    row = row.copy(importStatus = ImportStatus.VERIFIED, updatedAt = clock())
                    ledger.updateImportFields(row)
                    verified = true
                    break
                }
            } catch (e: Throwable) {
                // 카드 분리·공간 부족·취소·예상 못 한 오류: 현재 .part 정리, 행 정리 후 위로 전달
                if (!verified) {
                    withContext(NonCancellable) {
                        if (renamed) {
                            // 최종 파일은 이미 검증을 마치고 제자리에 있다: 지우거나 행을 떼어내지 않는다(주인 없는 파일이 된다).
                            // COPIED(경로·해시 유지)로 두면 다음 복구가 VERIFIED로 올린다. 카드 원본은 아직 그대로다.
                            runCatching { ledger.updateImportFields(row.copy(importStatus = ImportStatus.COPIED, updatedAt = clock())) }
                        } else {
                            deleteQuietly(part)
                            runCatching { ledger.updateImportFields(detach(row, interruptCode(e))) }
                        }
                    }
                }
                throw e
            }

            if (verified) {
                onVerified(uf, target, isPrimary)
                if (request.moveMode) deletes += PendingDelete(file, row.id, target, synced = durable)
                progress.finishFile()
                return
            }
            // 실패: 카드 원본은 그대로 둔다
            val failure = reason ?: FailureReason.WRITE_ERROR
            deleteQuietly(part)
            ledger.updateImportFields(detach(row, failure.name))
            failed++
            failures += ImportFailure(display(file), failure, null)
            progress.finishFile(failed = true)
        }

        private fun interruptCode(e: Throwable): String = when (e) {
            is CardRemovedSignal -> "CARD_REMOVED"
            is SpaceSignal -> "NO_SPACE"
            is CancelledSignal, is CancellationException -> "CANCELLED"
            else -> "INTERRUPTED"
        }

        /** 카드 → .part 복사. 반환: 카드에서 읽은 내용의 SHA-256 */
        private fun copyToPart(file: CardFile, part: File): String {
            val digest = Sha256.newDigest()
            val input = try {
                source.openInput(file)
            } catch (e: IOException) {
                throw SourceReadException(e)
            } catch (e: SecurityException) {
                throw SourceReadException(e)
            }
            try {
                val out = try {
                    io.openForWrite(part)
                } catch (e: IOException) {
                    throw DestWriteException(e)
                }
                var ok = false
                try {
                    var total = 0L
                    while (true) {
                        checkCancelled()
                        val n = try {
                            input.read(buffer)
                        } catch (e: IOException) {
                            throw SourceReadException(e)
                        }
                        if (n < 0) break
                        if (n == 0) continue
                        digest.update(buffer, 0, n)
                        try {
                            out.write(buffer, 0, n)
                        } catch (e: IOException) {
                            throw DestWriteException(e)
                        }
                        total += n
                        progress.onTransferred(n)
                    }
                    // 분리 직후 일부 드라이버는 오류 대신 일찍 끝(EOF)을 돌려준다
                    if (total != file.size) throw SourceReadException(IOException("read $total of ${file.size} bytes"))
                    try {
                        out.sync()
                    } catch (e: IOException) {
                        throw DestWriteException(e)
                    }
                    ok = true
                } finally {
                    try {
                        out.close()
                    } catch (e: IOException) {
                        if (ok) throw DestWriteException(e)
                    }
                }
            } finally {
                runCatching { input.close() }
            }
            return Sha256.hex(digest)
        }

        /**
         * 이미 있던 같은 내용 파일을 가리키는 행.
         * @param memberKey null이면 그 파일의 주인 행이 따로 있다: 검토에 두 번 나오지 않게 단위에서 떼어 둔다.
         *   아니면 주인이 없는 파일을 이 단위의 일원으로 들인다(대표면 분석·검토 대상).
         */
        private suspend fun recordDuplicate(
            uf: UnitFile,
            slot: Slot.Duplicate,
            memberKey: String?,
            isPrimary: Boolean,
            capturedAt: Long,
            deletes: MutableList<PendingDelete>,
        ) {
            progress.startFile(display(uf.file), uf.file.size)
            val adopted = memberKey != null
            val primary = adopted && isPrimary
            val unitKey = memberKey ?: UnitKeys.duplicate(session.id, uf.file.relPath)
            val row = newRow(uf, unitKey, isPrimary = primary, capturedAt = capturedAt).copy(
                dstPath = slot.existing.path,
                fileName = slot.existing.name,
                sha256 = slot.sha256,
                importStatus = ImportStatus.DUPLICATE,
            )
            val id = ledger.insertItem(row)
            duplicates++
            if (primary && uf.kind.mediaType != MediaType.RAW) runCatching { onMediaVerified() }
            if (request.moveMode) deletes += PendingDelete(uf.file, id, slot.existing, synced = false)
            progress.finishFile()
        }

        private suspend fun failWithoutCopy(uf: UnitFile, capturedAt: Long, reason: FailureReason) {
            progress.startFile(display(uf.file), uf.file.size)
            val row = detach(newRow(uf, "", isPrimary = false, capturedAt = capturedAt), reason.name)
            ledger.insertItem(row)
            failed++
            failures += ImportFailure(display(uf.file), reason, null)
            progress.finishFile(failed = true)
        }

        private fun onVerified(uf: UnitFile, target: File, isPrimary: Boolean) {
            saved++
            savedBytes += uf.file.size
            if (isPrimary) {
                when (uf.kind.mediaType) {
                    MediaType.PHOTO -> savedPhotos++
                    MediaType.VIDEO -> savedVideos++
                    MediaType.RAW -> Unit
                }
            }
            scanPaths += target.path
            scanMimes += uf.kind.mimeType
            if (scanPaths.size >= SCAN_BATCH) flushScan()
            if (isPrimary && uf.kind.mediaType != MediaType.RAW) runCatching { onMediaVerified() }
        }

        private fun flushScan() {
            if (scanPaths.isEmpty()) return
            val paths = scanPaths.toList()
            val mimes = scanMimes.toList()
            scanPaths.clear()
            scanMimes.clear()
            runCatching { mediaScanner.scan(paths, mimes) }
        }

        // ---------------------------------------------------------------- 원본 삭제

        /** 6. 이동 모드: 검증된(또는 같은 내용이 확인된) 복사본이 디스크에 기록돼 있을 때만 카드 원본 삭제 */
        private suspend fun deleteSource(d: PendingDelete) = withContext(NonCancellable) {
            // 마지막 안전 확인: 근거가 된 폰 복사본이 그대로 있고 크기가 같다
            if (!d.copy.isFile || d.copy.length() != d.file.size) return@withContext
            // 복사본이 디스크까지 기록됐는지 확인(fsync). 못 하면 카드에 남긴다
            if (!d.synced && !syncQuietly(d.copy)) {
                keepSource(d)
                return@withContext
            }
            // 쓰기 금지 카드: 처음 몇 개가 모두 실패했으면 남은 파일은 시도하지 않는다
            if (sourceReadOnly) {
                keepSource(d)
                return@withContext
            }
            val deleted = runCatching { source.delete(d.file) }.getOrDefault(false)
            val gone = when {
                deleted -> true
                // 카드는 그대로 있고 이 파일만 지워지지 않음(쓰기 금지 등): 분리 여부를 기다릴 필요가 없다
                runCatching { source.exists(d.file) }.getOrDefault(false) -> false
                else -> {
                    // 파일이 안 보이거나 확인할 수 없음: 카드가 빠졌는지 잠깐 기다려 본다
                    if (cardGone()) throw CardRemovedSignal()
                    !runCatching { source.exists(d.file) }.getOrDefault(true)
                }
            }
            if (gone) {
                deletedSources++
                ledger.setStatusIf(d.rowId, DELETABLE_STATUSES, ImportStatus.SOURCE_DELETED, clock())
            } else {
                undeletable++
                if (deletedSources == 0 && undeletable >= READ_ONLY_AFTER_FAILURES) sourceReadOnly = true
                keepSource(d)
            }
        }

        /** 카드 원본을 지우지 못함(쓰기 금지 등). 폰 복사본은 유지 */
        private suspend fun keepSource(d: PendingDelete) {
            deleteFailed++
            failures += ImportFailure(display(d.file), FailureReason.SOURCE_DELETE_FAILED, null)
            ledger.setStatusIf(d.rowId, setOf(ImportStatus.VERIFIED), ImportStatus.SOURCE_DELETE_FAILED, clock())
        }

        private fun syncQuietly(file: File): Boolean = try {
            io.syncRenamed(file)
            true
        } catch (e: Exception) {
            false
        }

        // ---------------------------------------------------------------- 보조

        /** 읽기 오류 뒤 카드가 분리됐는지: 볼륨이 사라질 때까지 잠깐 기다려 본다 */
        private suspend fun cardGone(): Boolean {
            if (!source.isPresent()) return true
            var waited = 0L
            while (waited < removalGraceMs) {
                val step = minOf(REMOVAL_POLL_MS, removalGraceMs - waited)
                withContext(NonCancellable) { delay(step) }
                waited += step
                if (!source.isPresent()) return true
            }
            return false
        }

        private fun ensureSpace(size: Long) {
            val free = freeSpace(destRoot)
            if (free < size + RUNNING_MARGIN_BYTES) {
                val remaining = (plan.summary.newBytes - progress.doneBytes).coerceAtLeast(size)
                throw SpaceSignal(required = remaining + StoragePaths.SPACE_MARGIN_BYTES, free = free)
            }
        }

        private fun deleteQuietly(file: File) {
            runCatching { if (file.exists()) file.delete() }
        }

        private fun display(file: CardFile): String =
            if (file.relPath.startsWith("${CardFileTypes.DCIM}/", ignoreCase = true)) file.relPath.substring(CardFileTypes.DCIM.length + 1) else file.relPath
    }

    companion object {
        const val BUFFER_SIZE = 1024 * 1024
        const val MAX_ATTEMPTS = 2
        const val MAX_SUFFIX = 9_999
        const val SCAN_BATCH = 20

        /** 가져오는 도중 폰 여유 공간이 이 아래로 내려가면 멈춘다(시작 전 확인은 2GB 여유) */
        const val RUNNING_MARGIN_BYTES: Long = 256L * 1024 * 1024
        private const val REMOVAL_POLL_MS = 250L

        /** 같은 촬영으로 보는 수정 시각 차이(JPG와 CR3는 1~2초 안에 기록된다. FAT은 2초 단위) */
        const val SAME_EXPOSURE_MS = 2_000L

        /** 처음 이만큼의 원본 삭제가 모두 실패하면(파일은 그대로) 쓰기 금지 카드로 보고 남은 삭제를 건너뛴다 */
        const val READ_ONLY_AFTER_FAILURES = 3

        private val DELETABLE_STATUSES = setOf(ImportStatus.VERIFIED, ImportStatus.SOURCE_DELETE_FAILED)
        private val SUFFIX = Regex("_\\d+")

        /** 사진 단위가 짝으로 보일 수 있는 확장자 */
        private val PAIR_EXTENSIONS = listOf("JPG", "jpg", "JPEG", "jpeg", "HIF", "hif", "HEIF", "heif", "CR3", "cr3")

        fun usableSpace(dir: File): Long {
            var d: File? = dir
            while (d != null && !d.exists()) d = d.parentFile
            return d?.usableSpace ?: 0L
        }
    }
}
