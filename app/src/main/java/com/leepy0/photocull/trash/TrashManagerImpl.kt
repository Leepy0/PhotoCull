package com.leepy0.photocull.trash

import android.content.Context
import android.util.Log
import com.leepy0.photocull.PhotoCullApp
import com.leepy0.photocull.data.db.AppDatabase
import com.leepy0.photocull.data.db.ImportStatus
import com.leepy0.photocull.data.db.MediaItemEntity
import com.leepy0.photocull.data.db.ReviewState
import com.leepy0.photocull.data.settings.SettingsRepository
import com.leepy0.photocull.importer.AndroidMediaScanSink
import com.leepy0.photocull.importer.MediaScanSink
import com.leepy0.photocull.storage.StoragePaths
import com.leepy0.photocull.util.Permissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 휴지통 = DCIM/.PhotoCullTrash/ (.nomedia). 같은 저장소 안의 이름 변경이라 즉시 끝난다.
 *
 * 데이터 안전 원칙
 * - 파일은 덮어쓰지 않는다. 옮긴 곳에 온전한 파일이 생긴 뒤에만 원래 자리에서 사라진다.
 * - 촬영 단위(JPG+CR3)는 함께 움직인다. 대표 파일은 마지막에 옮기고, 실패하면 단위 전체를 되돌린다.
 * - 파일을 옮긴 뒤 기록(DB)에 실패하면 파일을 원래 자리로 되돌린다. 그 사이 앱이 끝나 어긋난 것은
 *   다음 시작 때 [reconcile]이 휴지통 파일 이름의 id로 찾아 맞춘다.
 * - 기록은 휴지통 관련 열만 바꾼다([TrashDb]). 분석 결과나 가져오기 상태를 덮지 않는다.
 * - 아직 가져오는 중(검증 전)인 파일이 섞인 단위는 휴지통으로 보내지 않는다.
 * - 영구 삭제는 휴지통 폴더 안의 파일만 지운다.
 * - 파일 접근 권한이 없으면(파일이 '없어 보이는' 상태) 아무것도 바꾸지 않는다.
 * - 파일 작업과 기록은 중간에 취소되지 않는다(NonCancellable). 모든 작업은 하나씩(Mutex) 처리한다.
 *
 * 휴지통 상태(TRASHED)인 행의 trashPath가 null이면 "자리 표시" 행이다: 단위를 휴지통으로 보낼 때 그 파일이
 * 이미 폰에 없었다(짝만 남은 촬영). 복원하면 원래 자리 파일이 있으면 그대로 두고, 없으면 DELETED.
 */
class TrashManagerImpl(
    context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    /** 기본 저장소 루트(/storage/emulated/0). 테스트에서는 임시 폴더 */
    private val storageRoot: () -> File = { StoragePaths.primaryRoot },
    private val mediaScanner: MediaScanSink = AndroidMediaScanSink(context),
    /** 모든 파일 접근 권한. 없으면 파일이 있어도 없는 것처럼 보이므로 상태를 바꾸지 않는다 */
    private val storageAccessible: () -> Boolean = { Permissions.hasAllFilesAccess() },
    private val clock: () -> Long = System::currentTimeMillis,
    /** 보관 기간(일). 0 = 무기한 */
    private val retentionDays: suspend () -> Int = { settings.current().trashRetentionDays },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * 복원·실행 취소로 사진이 검토 대상에 돌아온 뒤 호출. 기본값은 분석을 다시 돌려(그룹 재계산) 복원한 사진이
     * 연사 그룹에 다시 묶이게 한다. 앱 컨테이너가 아직 없으면(테스트 등) 아무것도 하지 않는다.
     */
    private val onRestored: () -> Unit = kickAnalysis(context),
    /** DB 쓰기 직전(트랜잭션 안)에 불린다. 테스트에서 예외를 던져 쓰기 실패(저장공간 부족 등)를 흉내 낸다. */
    private val beforeDbWrite: () -> Unit = {},
) : TrashManager {

    private val mutex = Mutex()
    private val dao get() = db.mediaDao()
    private val writes = TrashDb(db)

    private fun trashDir(): File = StoragePaths.trashDir(storageRoot())

    // ------------------------------------------------------------------ 휴지통으로

    override suspend fun moveToTrash(ids: Collection<Long>, keepIds: Collection<Long>): TrashResult = locked {
        val requested = ids.distinct()
        val requestedSet = requested.toSet()
        val keepRequested = keepIds.distinct().filterNot { it in requestedSet }
        if (requested.isEmpty()) {
            val kept = applyKeep(keepRequested)
            return@locked TrashResult(TrashBatch(emptyList(), kept), movedCount = 0, failedCount = 0, freedBytes = 0)
        }
        if (!storageAccessible()) return@locked TrashResult(TrashBatch(emptyList()), 0, requested.size, 0)
        val dir = StoragePaths.ensureTrashDir(storageRoot())
        if (!dir.isDirectory) return@locked TrashResult(TrashBatch(emptyList()), 0, requested.size, 0)

        val now = clock()
        val primaries = getAll(requested).associateBy { it.id }
        val units = membersOf(primaries.values.map { it.unitKey })
        val scan = ScanBatch()
        val trashedIds = ArrayList<Long>()
        var moved = 0
        var failed = 0
        var gone = 0
        var importing = 0
        var freed = 0L
        var writeBroken = false
        for (id in requested) {
            val primary = primaries[id] ?: continue
            if (!primary.reviewState.isActive) continue // 이미 휴지통이거나 영구 삭제됨
            if (writeBroken) {
                failed++
                continue
            }
            val members = units[primary.unitKey].orEmpty().filter { it.reviewState.isActive }
            val outcome = try {
                trashUnit(members, dir, now, scan)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 기록하지 못했다(저장공간 부족 등). 파일은 원래 자리로 되돌렸고, 남은 단위도 같은 이유로 실패할 것이라 멈춘다.
                Log.w(TAG, "could not record trash move; stopping batch", e)
                writeBroken = true
                UnitTrash.Failed
            }
            when (outcome) {
                is UnitTrash.Moved -> {
                    moved++
                    freed += outcome.bytes
                    trashedIds += id
                }
                UnitTrash.AlreadyGone -> gone++ // 폰에서 이미 지워진 사진: 목록에서만 뺀다(되돌릴 파일 없음)
                UnitTrash.Importing -> {
                    failed++
                    importing++
                }
                UnitTrash.Failed -> failed++
            }
        }
        // 하나도 처리하지 못했으면 '남기기'도 하지 않는다(실패한 작업의 절반만 적용되지 않게)
        val kept = if (moved + gone > 0 && !writeBroken) {
            try {
                applyKeep(keepRequested)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "could not mark kept", e)
                emptyList()
            }
        } else {
            emptyList()
        }
        scan.flush(mediaScanner)
        TrashResult(TrashBatch(trashedIds, kept), moved, failed, freed, goneCount = gone, importingCount = importing)
    }

    private suspend fun trashUnit(members: List<MediaItemEntity>, dir: File, now: Long, scan: ScanBatch): UnitTrash {
        if (members.isEmpty()) return UnitTrash.Failed
        // 아직 가져오는 중(복사·검증 전)인 파일이 있으면 건드리지 않는다. 파일이 없어 보여도 '이미 지워짐'이 아니다.
        if (members.any { it.importStatus !in STORED }) return UnitTrash.Importing
        val present = members.filter { m -> m.dstPath?.let { File(it).isFile } == true }
        if (present.isEmpty()) {
            // 모두 폰에 저장됐던 파일인데 지금은 하나도 없다(다른 앱이 지움 등): 목록에서만 뺀다
            write(members.map { TrashChange.Deleted(it.id) }, now)
            members.forEach { scan.add(it.dstPath, it.mimeType) } // 갤러리에 남은 흔적 정리
            return UnitTrash.AlreadyGone
        }

        val moved = ArrayList<Pair<MediaItemEntity, File>>()
        var bytes = 0L
        // 짝(CR3)을 먼저, 대표 파일을 마지막에
        for (m in present.sortedBy { it.isPrimary }) {
            val src = File(requireNotNull(m.dstPath))
            val size = src.length()
            val target = TrashFiles.uniqueTrashFile(dir, m.id, src.name)
            if (TrashFiles.move(src, target)) {
                moved += m to target
                bytes += size
            } else {
                // 단위 전체를 실패로 되돌린다. 되돌리지 못한 짝은 기록 없이 휴지통에 남고(대표 파일은 원래 자리),
                // 다음 시작 때 reconcile이 원래 자리로 옮긴다. 원래 자리에 남은 파일을 휴지통 상태로 기록하지 않는다.
                val stuck = moveBack(moved)
                if (stuck.isNotEmpty()) Log.w(TAG, "${stuck.size} file(s) left in trash after a failed move; reconcile will retry")
                return UnitTrash.Failed
            }
        }

        val trashPaths = moved.associate { (m, f) -> m.id to f.path }
        try {
            write(members.map { TrashChange.Trashed(it.id, trashPaths[it.id], now) }, now)
        } catch (e: Exception) {
            // 기록하지 못했으면 파일을 원래 자리로 되돌려 기록 없는 휴지통 파일이 남지 않게 한다
            moveBack(moved)
            throw e
        }
        for (m in members) if (m.id in trashPaths || m !in present) scan.add(m.dstPath, m.mimeType)
        // 새 위치도 알린다(.nomedia라 갤러리에는 들어가지 않는다). 갤러리가 옛 항목을 확실히 정리하게
        for ((m, f) in moved) scan.add(f.path, m.mimeType)
        return UnitTrash.Moved(bytes)
    }

    /** 휴지통으로 옮긴 파일을 원래 자리로. 되돌리지 못한 것 */
    private fun moveBack(moved: List<Pair<MediaItemEntity, File>>): List<Pair<MediaItemEntity, File>> =
        moved.asReversed().filterNot { (m, target) -> TrashFiles.move(target, File(requireNotNull(m.dstPath))) }

    /** '남기기': 지금 검토 대기(PENDING)인 것만 KEPT로. 실행 취소 때 PENDING으로 돌릴 목록을 돌려준다 */
    private suspend fun applyKeep(ids: List<Long>): List<Long> {
        if (ids.isEmpty()) return emptyList()
        val pending = getAll(ids).filter { it.reviewState == ReviewState.PENDING }.map { it.id }
        pending.chunked(CHUNK).forEach { dao.setUnitReviewState(it, ReviewState.KEPT, clock()) }
        return pending
    }

    // ------------------------------------------------------------------ 되돌리기·복원

    override suspend fun undo(batch: TrashBatch) {
        val count = locked {
            var count = RestoreReport(0)
            if (batch.trashedIds.isNotEmpty()) {
                if (!storageAccessible()) throw IOException("storage not accessible")
                count = restoreUnits(batch.trashedIds.distinct(), ReviewState.PENDING)
            }
            if (batch.keptIds.isNotEmpty()) {
                val kept = getAll(batch.keptIds.distinct()).filter { it.reviewState == ReviewState.KEPT }.map { it.id }
                kept.chunked(CHUNK).forEach { dao.setUnitReviewState(it, ReviewState.PENDING, clock()) }
            }
            count
        }
        if (count.restored > 0) notifyRestored()
        if (count.failed > 0) throw IOException("${count.failed} units could not be restored")
    }

    override suspend fun restore(ids: Collection<Long>): Int = restoreDetailed(ids).restored

    override suspend fun restoreDetailed(ids: Collection<Long>): RestoreReport {
        val report = locked {
            val distinct = ids.distinct()
            if (distinct.isEmpty()) return@locked RestoreReport(0)
            if (!storageAccessible()) return@locked RestoreReport(0, failed = distinct.size)
            restoreUnits(distinct, ReviewState.KEPT)
        }
        if (report.restored > 0) notifyRestored()
        return report
    }

    private fun notifyRestored() {
        runCatching { onRestored() }
    }

    private suspend fun restoreUnits(ids: List<Long>, targetState: ReviewState): RestoreReport {
        val primaries = getAll(ids).associateBy { it.id }
        val units = membersOf(primaries.values.map { it.unitKey })
        val scan = ScanBatch()
        var restored = 0
        var gone = 0
        var failed = 0
        var writeBroken = false
        val now = clock()
        for (id in ids) {
            val primary = primaries[id] ?: continue
            if (primary.reviewState != ReviewState.TRASHED) continue
            if (writeBroken) {
                failed++
                continue
            }
            val members = units[primary.unitKey].orEmpty().filter { it.reviewState == ReviewState.TRASHED }
            val outcome = try {
                restoreUnit(members, targetState, now, scan)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "could not record restore; stopping batch", e)
                writeBroken = true
                UnitRestore.Failed
            }
            when (outcome) {
                UnitRestore.Restored -> restored++
                UnitRestore.Gone -> gone++
                UnitRestore.Failed -> failed++
            }
        }
        scan.flush(mediaScanner)
        return RestoreReport(restored = restored, gone = gone, failed = failed)
    }

    private suspend fun restoreUnit(
        members: List<MediaItemEntity>,
        targetState: ReviewState,
        now: Long,
        scan: ScanBatch,
    ): UnitRestore {
        if (members.isEmpty()) return UnitRestore.Failed
        val toMove = ArrayList<Pair<MediaItemEntity, File>>()
        val inPlace = ArrayList<MediaItemEntity>()
        for (m in members) {
            val inTrash = m.trashPath?.let(::File)?.takeIf { it.isFile }
            when {
                inTrash != null -> toMove += m to inTrash
                m.trashPath == null && m.dstPath?.let { File(it).isFile } == true -> inPlace += m
            }
        }
        if (toMove.isEmpty() && inPlace.isEmpty()) {
            // 휴지통 파일이 모두 사라졌다(다른 앱이 지움 등): 복원할 것이 없으니 영구 삭제된 것으로 정리
            write(members.map { TrashChange.Deleted(it.id) }, now)
            return UnitRestore.Gone
        }

        val originals = LinkedHashMap<Long, File>()
        for ((m, _) in toMove) originals[m.id] = originalLocation(m)
        val targets = TrashFiles.restoreTargets(originals) ?: return UnitRestore.Failed

        // 짝(CR3)을 먼저, 대표 파일을 마지막에. 실패하면 이미 꺼낸 짝을 휴지통으로 되돌린다.
        val done = ArrayList<Triple<MediaItemEntity, File, File>>() // 항목, 휴지통 파일, 복원 위치
        var failure = false
        for ((m, trashFile) in toMove.sortedBy { it.first.isPrimary }) {
            val target = targets.getValue(m.id)
            if (TrashFiles.move(trashFile, target)) done += Triple(m, trashFile, target) else {
                failure = true
                break
            }
        }
        if (failure) {
            val stuck = done.asReversed().filterNot { (_, trashFile, target) -> TrashFiles.move(target, trashFile) }
            // 되돌리지 못한 짝은 원래 폴더에 안전하게 있으므로 복원된 것으로 기록한다(대표 파일은 휴지통에 남아 다시 시도 가능).
            // 이 기록이 실패해도 다음 시작 때 reconcile이 원래 폴더의 파일을 찾아 맞춘다.
            if (stuck.isNotEmpty()) {
                write(stuck.map { (m, _, target) -> TrashChange.Restored(m.id, targetState, target.path, target.name) }, now)
                stuck.forEach { (m, _, target) -> scan.add(target.path, m.mimeType) }
            }
            return UnitRestore.Failed
        }

        val movedTo = done.associate { (m, _, target) -> m.id to target }
        val inPlaceIds = inPlace.map { it.id }.toSet()
        val changes = members.map { row ->
            val target = movedTo[row.id]
            when {
                target != null -> TrashChange.Restored(row.id, targetState, target.path, target.name)
                row.id in inPlaceIds -> TrashChange.Restored(row.id, targetState)
                else -> TrashChange.Deleted(row.id)
            }
        }
        try {
            write(changes, now)
        } catch (e: Exception) {
            // 기록하지 못했으면 꺼낸 파일을 휴지통으로 되돌린다(기록은 아직 휴지통 상태)
            done.asReversed().forEach { (_, trashFile, target) -> TrashFiles.move(target, trashFile) }
            throw e
        }
        done.forEach { (m, _, target) -> scan.add(target.path, m.mimeType) }
        return UnitRestore.Restored
    }

    /** 원래 위치. 기록이 없으면(정상이라면 없음) 지금 저장 폴더 */
    private suspend fun originalLocation(m: MediaItemEntity): File {
        m.dstPath?.let { return File(it) }
        val folder = runCatching { settings.current().destFolderName }.getOrDefault("")
        return File(StoragePaths.destRoot(folder, storageRoot()), m.fileName)
    }

    // ------------------------------------------------------------------ 영구 삭제

    override suspend fun deleteForever(ids: Collection<Long>): Int = locked {
        if (ids.isEmpty() || !storageAccessible()) return@locked 0
        val primaries = getAll(ids.distinct()).filter { it.reviewState == ReviewState.TRASHED }
        val units = membersOf(primaries.map { it.unitKey })
        primaries.count { p -> deleteUnit(units[p.unitKey].orEmpty().filter { it.reviewState == ReviewState.TRASHED }) }
    }

    override suspend fun emptyTrash(): Int = locked {
        if (!storageAccessible()) return@locked 0
        deleteTrashedRows(dao.allTrashed())
    }

    override suspend fun purgeExpired(): Int = locked {
        val days = retentionDays()
        if (days <= 0 || !storageAccessible()) return@locked 0
        val count = deleteUnits(expiredUnits(days))
        trashDir().takeIf { it.isDirectory }?.let { TrashFiles.cleanPartFiles(it) }
        count
    }

    override suspend fun previewPurge(retentionDays: Int): PurgePreview = withContext(io) {
        if (retentionDays <= 0) return@withContext PurgePreview(0, 0)
        val units = expiredUnits(retentionDays)
        PurgePreview(count = units.size, bytes = units.values.sumOf { members -> members.sumOf { it.srcSize } })
    }

    /**
     * 보관 기간 [days]를 넘긴 휴지통 단위(단위 키 → 휴지통 상태인 멤버). [purgeExpired]와 [previewPurge]가 같이 써서
     * 확인 대화상자의 수와 실제로 지우는 수가 같다. 단위의 어느 파일이든 기간을 넘기면 단위 전체가 대상이다.
     */
    private suspend fun expiredUnits(days: Int): Map<String, List<MediaItemEntity>> {
        val cutoff = clock() - days * DAY_MS
        val rows = dao.trashedBefore(cutoff)
        if (rows.isEmpty()) return emptyMap()
        return membersOf(rows.map { it.unitKey })
            .mapValues { (_, all) -> all.filter { it.reviewState == ReviewState.TRASHED } }
            .filterValues { it.isNotEmpty() }
    }

    /** 행들이 속한 촬영 단위의 휴지통 파일을 모두 지운다. 다 지운 단위 수 */
    private suspend fun deleteTrashedRows(rows: List<MediaItemEntity>): Int {
        if (rows.isEmpty()) return 0
        val units = membersOf(rows.map { it.unitKey })
            .mapValues { (_, all) -> all.filter { it.reviewState == ReviewState.TRASHED } }
            .filterValues { it.isNotEmpty() }
        return deleteUnits(units)
    }

    private suspend fun deleteUnits(units: Map<String, List<MediaItemEntity>>): Int =
        units.values.count { members -> deleteUnit(members) }

    /** 단위의 휴지통 파일 삭제. 짝을 먼저 지우고, 하나라도 못 지우면 대표 파일은 남겨 다시 시도할 수 있게 한다 */
    private suspend fun deleteUnit(members: List<MediaItemEntity>): Boolean {
        if (members.isEmpty()) return false
        val dir = trashDir()
        val now = clock()
        val deleted = ArrayList<Long>()
        var failure = false
        for (m in members.sortedBy { it.isPrimary }) {
            if (m.isPrimary && failure) break
            val file = m.trashPath?.let(::File)
            val ok = when {
                // 자리 표시: 휴지통에 지울 파일이 없다. 다만 원래 자리에 이 행의 파일이 있으면 휴지통 밖 파일이라
                // 지우지도, 지운 것으로 기록하지도 않는다(복원하면 그대로 돌아온다). 그 자리를 다른 행이 쓰면 남의 파일이다.
                file == null -> m.dstPath.let { p -> p == null || !File(p).exists() || writes.otherOwnerOf(p, m.id) }
                !TrashFiles.isInside(dir, file) -> true // 휴지통 밖 파일은 지우지 않고 기록만 정리
                !file.exists() -> true // 이미 사라짐
                else -> file.delete() || !file.exists()
            }
            if (ok) deleted += m.id else failure = true
        }
        if (deleted.isNotEmpty()) write(deleted.map { TrashChange.Deleted(it) }, now)
        return !failure
    }

    // ------------------------------------------------------------------ 시작 시 맞추기

    /**
     * 파일과 기록이 어긋난 것을 고친다(앱 시작 시 purgeExpired보다 먼저).
     * 1. 기록에 없는 휴지통 파일: 이름의 "<id>_"로 행을 찾고 파일명·크기가 맞을 때만 잇는다.
     *    - 행이 검토 중(PENDING/KEPT)인데 원래 자리에 파일이 없다 = 옮긴 뒤 기록 전에 끝남.
     *      단위의 파일이 원래 자리에 하나도 없으면 단위를 휴지통 상태로 기록하고(보관 기간은 지금부터),
     *      원래 자리에 남은 파일이 있으면(단위 중간에 끊김) 휴지통 파일을 원래 자리로 되돌린다.
     *    - 행이 휴지통 상태인데 기록된 휴지통 파일이 없다 → 이 파일로 잇는다.
     * 2. 휴지통 상태인데 휴지통 파일이 없고 원래 자리(또는 "_restored<n>")에 같은 파일이 있다 = 복원 뒤 기록 전에 끝남 → 남기기로.
     * 3. 중단된 휴지통 복사의 임시 파일(.photocull-trash.part) 정리.
     * 맞는 행이 없는 파일, 판단이 애매한 파일은 지우지 않고 그대로 둔다(로그만 남긴다).
     * 보관 시작 시각을 파일 수정 시각으로 쓰지 않는다: 이름 변경은 수정 시각을 바꾸지 않아(촬영 무렵 시각)
     * 바로 이어지는 보관 기간 정리에서 확인 없이 지워질 수 있기 때문이다.
     */
    override suspend fun reconcile(): Int = locked {
        if (!storageAccessible()) return@locked 0
        val dir = trashDir()
        val listed: Array<File> = if (dir.exists()) dir.listFiles() ?: return@locked 0 else emptyArray()
        val now = clock()
        val scan = ScanBatch()
        var fixed = 0

        val trashed = dao.allTrashed()
        val knownPaths = trashed.mapNotNullTo(HashSet()) { it.trashPath }

        // 1) 기록에 없는 휴지통 파일 → id별로 모은다(같은 id에 파일이 여럿이면 판단하지 않는다)
        val candidates = LinkedHashMap<Long, MutableList<Pair<File, Set<String>>>>()
        for (f in listed) {
            if (!f.isFile || f.path in knownPaths) continue
            if (f.name.startsWith(".") || f.name.endsWith(TrashFiles.TRASH_PART_SUFFIX)) continue
            val parsed = TrashFiles.parseTrashName(f.name)
            if (parsed == null) {
                Log.w(TAG, "unknown file in trash left as is: ${f.name}")
                continue
            }
            candidates.getOrPut(parsed.first) { ArrayList() } += f to parsed.second
        }
        val rows = getAll(candidates.keys.toList()).associateBy { it.id }
        val handled = HashSet<Long>()
        val activeStrays = LinkedHashMap<Long, Pair<MediaItemEntity, File>>()
        for ((id, files) in candidates) {
            val row = rows[id]
            val match = files.singleOrNull()?.takeIf { (f, names) -> row != null && matches(row, f, names) }?.first
            if (row == null || match == null) {
                Log.w(TAG, "trash file(s) without a matching record left as is: ${files.joinToString { it.first.name }}")
                continue
            }
            when (row.reviewState) {
                ReviewState.TRASHED -> {
                    val recorded = row.trashPath?.let(::File)
                    if (recorded == null || !recorded.isFile) {
                        write(listOf(TrashChange.Trashed(row.id, match.path, row.trashedAt ?: now)), now)
                        handled += row.id
                        fixed++
                    } else {
                        Log.w(TAG, "second trash file for item ${row.id} left as is: ${match.name}")
                    }
                }
                ReviewState.PENDING, ReviewState.KEPT -> {
                    val atOrigin = row.dstPath?.let { File(it).exists() } == true
                    if (row.importStatus in STORED && row.dstPath != null && !atOrigin) {
                        activeStrays[row.id] = row to match
                    } else {
                        Log.w(TAG, "trash file for active item ${row.id} left as is: ${match.name}")
                    }
                }
                ReviewState.DELETED -> Log.w(TAG, "trash file for deleted item ${row.id} left as is: ${match.name}")
            }
        }
        if (activeStrays.isNotEmpty()) {
            val units = membersOf(activeStrays.values.map { it.first.unitKey })
            for ((_, all) in units) {
                val members = all.filter { it.reviewState.isActive }
                val strays = members.mapNotNull { m -> activeStrays[m.id]?.let { m to it.second } }
                if (strays.isEmpty()) continue
                if (members.any { it.importStatus !in STORED }) {
                    Log.w(TAG, "unit with unfinished import left as is: ${strays.joinToString { it.second.name }}")
                    continue
                }
                val anyAtOrigin = members.any { m -> m.dstPath?.let { File(it).exists() } == true }
                if (!anyAtOrigin) {
                    // 단위 전체가 휴지통으로 옮겨진 뒤 기록 전에 끝났다 → 휴지통 상태로 기록
                    val paths = strays.associate { (m, f) -> m.id to f.path }
                    write(members.map { TrashChange.Trashed(it.id, paths[it.id], now) }, now)
                    members.forEach { scan.add(it.dstPath, it.mimeType) }
                    fixed += members.size
                } else {
                    // 단위 중간에 끊겼다(대표 파일은 원래 자리) → 옮겨진 짝을 원래 자리로
                    for ((m, f) in strays) {
                        if (TrashFiles.move(f, File(requireNotNull(m.dstPath)))) {
                            scan.add(m.dstPath, m.mimeType)
                            fixed++
                        } else {
                            Log.w(TAG, "could not move ${f.name} back; will retry next start")
                        }
                    }
                }
            }
        }

        // 2) 휴지통 파일이 없는 휴지통 행: 복원은 됐는데 기록 전에 끝났는지 본다
        for (row in trashed) {
            if (row.id in handled) continue
            val recorded = row.trashPath?.let(::File) ?: continue // 자리 표시
            if (recorded.isFile) continue
            val original = row.dstPath?.let(::File) ?: continue
            val found = TrashFiles.restoreSuffixRange.asSequence()
                .map { n -> TrashFiles.restoreCandidate(original, n) }
                .firstOrNull { f -> f.isFile && isSameFile(row, f) }
                ?: continue
            write(listOf(TrashChange.Restored(row.id, ReviewState.KEPT, found.path, found.name)), now)
            scan.add(found.path, row.mimeType)
            fixed++
        }

        // 3) 중단된 휴지통 복사의 임시 파일(이 접미사만)
        if (dir.isDirectory) TrashFiles.cleanPartFiles(dir)
        trashed.mapNotNullTo(HashSet()) { it.dstPath?.let(::File)?.parentFile }
            .filter { it.isDirectory }
            .forEach { TrashFiles.cleanPartFiles(it) }

        scan.flush(mediaScanner)
        if (fixed > 0) Log.i(TAG, "reconciled $fixed trash record(s)")
        fixed
    }

    /** 휴지통 파일이 이 행의 파일인지: 이름("<id>_<원래 이름>")과 크기 */
    private fun matches(row: MediaItemEntity, file: File, names: Set<String>): Boolean {
        val original = row.dstPath?.let { File(it).name } ?: row.fileName
        return (original in names || row.fileName in names) && file.length() == row.srcSize
    }

    /** [file]이 이 행의 사진인지: 크기, 기록된 해시, 그리고 다른 행이 그 자리를 쓰고 있지 않은지 */
    private fun isSameFile(row: MediaItemEntity, file: File): Boolean {
        if (file.length() != row.srcSize) return false
        if (writes.otherOwnerOf(file.path, row.id)) return false
        val sha = row.sha256 ?: return true
        return runCatching { TrashFiles.sha256Of(file) }.getOrNull().equals(sha, ignoreCase = true)
    }

    // ------------------------------------------------------------------ 보조

    private suspend fun <T> locked(block: suspend () -> T): T =
        withContext(io + NonCancellable) { mutex.withLock { block() } }

    private suspend fun getAll(ids: List<Long>): List<MediaItemEntity> =
        ids.chunked(CHUNK).flatMap { dao.getAll(it) }

    private suspend fun membersOf(unitKeys: List<String>): Map<String, List<MediaItemEntity>> =
        unitKeys.distinct().chunked(CHUNK).flatMap { dao.unitMembersOf(it) }.groupBy { it.unitKey }

    /** 단위 하나의 기록. 한 트랜잭션으로, 휴지통 관련 열만 바꾼다 */
    private suspend fun write(changes: List<TrashChange>, now: Long) = writes.apply(changes, now, beforeDbWrite)

    private val ReviewState.isActive: Boolean get() = this == ReviewState.PENDING || this == ReviewState.KEPT

    private sealed interface UnitTrash {
        data class Moved(val bytes: Long) : UnitTrash
        data object AlreadyGone : UnitTrash
        /** 아직 가져오는 중인 파일이 섞여 있어 건드리지 않음 */
        data object Importing : UnitTrash
        data object Failed : UnitTrash
    }

    private enum class UnitRestore { Restored, Gone, Failed }

    /** 갤러리 반영 요청을 모아 한 번에 보낸다 */
    private class ScanBatch {
        private val paths = ArrayList<String>()
        private val mimes = ArrayList<String>()

        fun add(path: String?, mime: String) {
            if (path == null) return
            paths += path
            mimes += mime
        }

        fun flush(sink: MediaScanSink) {
            if (paths.isEmpty()) return
            runCatching { sink.scan(paths.toList(), mimes.toList()) }
            paths.clear()
            mimes.clear()
        }
    }

    private companion object {
        const val TAG = "TrashManager"
        const val CHUNK = 500
        const val DAY_MS = 86_400_000L

        /** 폰에 안전하게 저장된 상태(Daos.kt의 STORED와 같은 집합) */
        val STORED = setOf(
            ImportStatus.VERIFIED,
            ImportStatus.SOURCE_DELETED,
            ImportStatus.DUPLICATE,
            ImportStatus.SOURCE_DELETE_FAILED,
        )

        /** 앱 컨테이너의 분석을 다시 돌린다. 컨테이너가 준비되지 않았으면(테스트·초기화 중) 건너뛴다. */
        fun kickAnalysis(context: Context): () -> Unit {
            val app = context.applicationContext
            return {
                runCatching { (app as? PhotoCullApp)?.container?.analysis?.kick() }
            }
        }
    }
}
