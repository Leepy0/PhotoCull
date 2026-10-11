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
 * 안전 불변식: 카드 원본은 폰에 같은 SHA-256의 복사본이 확인된 뒤에만 지운다.
 * - 새 파일: `.photocull.part`로 복사하며 해시 → fsync → 다시 읽어 해시 비교(불일치면 1회 재시도) → 이름 변경 → VERIFIED
 * - 같은 이름에 같은 내용(크기+해시)이 이미 있으면 DUPLICATE(복사 생략)
 * - 이전 기록과 같은 파일은 복사 생략. 이동 모드면 기록된 복사본의 해시를 다시 확인한 뒤에만 원본 삭제
 * - 원본 삭제는 촬영 단위의 모든 파일 처리가 끝난 뒤. 중간에 멈추면 단위가 카드에 함께 남는다.
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

    /** 단위 처리가 끝나면 지울 카드 원본과 그 근거가 된 폰 복사본 */
    private class PendingDelete(val file: CardFile, val rowId: Long, val copy: File)

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
                PartFiles.clean(destRoot)
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

        /** 이전에 가져온 파일: 복사하지 않는다. 이동 모드면 기록된 폰 복사본을 다시 확인한 뒤에만 원본을 지운다. */
        private fun handleAlreadyImported(uf: UnitFile, refs: List<StoredRef>, deletes: MutableList<PendingDelete>) {
            duplicates++
            if (!request.moveMode) return
            checkCancelled()
            val ref = refs.firstOrNull { verifyStoredCopy(it, uf.file.size) } ?: return // 확인 못 하면 카드에 남긴다
            deletes += PendingDelete(uf.file, ref.id, File(requireNotNull(ref.dstPath)))
        }

        private fun verifyStoredCopy(ref: StoredRef, size: Long): Boolean {
            val path = ref.dstPath ?: return false
            val sha = ref.sha256 ?: return false
            val copy = File(path)
            if (!copy.isFile || copy.length() != size) return false
            return Sha256.ofFile(io, copy, buffer) == sha
        }

        private suspend fun importNewFiles(unit: ShotUnit, newFiles: List<UnitFile>, deletes: MutableList<PendingDelete>) {
            // 같은 단위의 일부가 이전에 저장됐으면(예: JPG는 옮겨졌고 CR3 복사 중 카드 분리) 그 단위에 이어 붙인다:
            // 같은 단위 키, 같은 폴더, 같은 접미사. 단, 같은 경로의 파일이 다른 내용으로 기록돼 있으면 다른 촬영이다.
            val siblings = if (unit.isVideo) emptyList() else siblingsByBase[unit.keyPath.lowercase(Locale.ROOT)].orEmpty()
            val newPaths = newFiles.mapTo(HashSet()) { it.file.relPath.lowercase(Locale.ROOT) }
            val anchor = siblings.takeIf { list -> list.none { it.srcRelPath.lowercase(Locale.ROOT) in newPaths } }?.firstOrNull()
            val unitKey = anchor?.unitKey ?: UnitKeys.unit(session.id, unit.keyPath)
            val capturedAt = anchor?.capturedAt ?: captureTimeOf(unit)
            val defaultDir = StoragePaths.destDir(request.destFolderName, request.monthlySubfolders, capturedAt, request.storageRoot)
            val preferred = anchor?.let(::preferredSlot)
            val storedPrimary = anchor != null && siblings.any { it.isPrimary }
            val top = unit.files.first()

            val slots = resolveNames(unit, newFiles, defaultDir, preferred)
            for (uf in newFiles) {
                checkCancelled()
                val isPrimary = uf === top && !storedPrimary
                when (val slot = slots?.get(uf)) {
                    null -> failWithoutCopy(uf, capturedAt, FailureReason.WRITE_ERROR)
                    is Slot.Duplicate -> recordDuplicate(uf, slot, capturedAt, deletes)
                    is Slot.New -> copyNew(uf, slot.target, unitKey, isPrimary, capturedAt, deletes)
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
                    ledger.updateItem(row)

                    // 4. .part를 다시 읽어 비교. 불일치면 지우고 1회 재시도
                    val check = Sha256.ofFile(io, part, buffer)
                    if (check != sha || part.length() != file.size) {
                        deleteQuietly(part)
                        row = row.copy(importStatus = ImportStatus.PENDING, sha256 = null, updatedAt = clock())
                        ledger.updateItem(row)
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
                    if (file.lastModified > 0) runCatching { target.setLastModified(file.lastModified) }
                    row = row.copy(importStatus = ImportStatus.VERIFIED, updatedAt = clock())
                    ledger.updateItem(row)
                    verified = true
                    break
                }
            } catch (e: Throwable) {
                // 카드 분리·공간 부족·취소·예상 못 한 오류: 현재 .part 정리, 행 정리 후 위로 전달
                if (!verified) {
                    withContext(NonCancellable) {
                        deleteQuietly(part)
                        runCatching { ledger.updateItem(detach(row, interruptCode(e))) }
                    }
                }
                throw e
            }

            if (verified) {
                onVerified(uf, target, isPrimary)
                if (request.moveMode) deletes += PendingDelete(file, row.id, target)
                progress.finishFile()
                return
            }
            // 실패: 카드 원본은 그대로 둔다
            val failure = reason ?: FailureReason.WRITE_ERROR
            deleteQuietly(part)
            ledger.updateItem(detach(row, failure.name))
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

        private suspend fun recordDuplicate(uf: UnitFile, slot: Slot.Duplicate, capturedAt: Long, deletes: MutableList<PendingDelete>) {
            progress.startFile(display(uf.file), uf.file.size)
            // 이미 있던 파일을 가리키는 행. 그 파일의 주인 행과 겹치지 않게 단위에서 떼어 둔다(검토에 두 번 나오지 않게).
            val row = newRow(uf, UnitKeys.duplicate(session.id, uf.file.relPath), isPrimary = false, capturedAt = capturedAt).copy(
                dstPath = slot.existing.path,
                fileName = slot.existing.name,
                sha256 = slot.sha256,
                importStatus = ImportStatus.DUPLICATE,
            )
            val id = ledger.insertItem(row)
            duplicates++
            if (request.moveMode) deletes += PendingDelete(uf.file, id, slot.existing)
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

        /** 6. 이동 모드: 검증된(또는 같은 내용이 확인된) 복사본이 있을 때만 카드 원본 삭제 */
        private suspend fun deleteSource(d: PendingDelete) = withContext(NonCancellable) {
            // 마지막 안전 확인: 근거가 된 폰 복사본이 그대로 있고 크기가 같다
            if (!d.copy.isFile || d.copy.length() != d.file.size) return@withContext
            val deleted = runCatching { source.delete(d.file) }.getOrDefault(false)
            val gone = if (deleted) {
                true
            } else {
                if (cardGone()) throw CardRemovedSignal()
                !source.exists(d.file)
            }
            val row = ledger.getItem(d.rowId)
            if (gone) {
                if (row != null && (row.importStatus == ImportStatus.VERIFIED || row.importStatus == ImportStatus.SOURCE_DELETE_FAILED)) {
                    ledger.updateItem(row.copy(importStatus = ImportStatus.SOURCE_DELETED, updatedAt = clock()))
                }
            } else {
                // 쓰기 금지 스위치 등. 폰 복사본은 유지
                deleteFailed++
                failures += ImportFailure(display(d.file), FailureReason.SOURCE_DELETE_FAILED, null)
                if (row != null && row.importStatus == ImportStatus.VERIFIED) {
                    ledger.updateItem(row.copy(importStatus = ImportStatus.SOURCE_DELETE_FAILED, updatedAt = clock()))
                }
            }
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
