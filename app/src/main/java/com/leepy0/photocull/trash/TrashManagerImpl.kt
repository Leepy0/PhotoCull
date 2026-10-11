package com.leepy0.photocull.trash

import android.content.Context
import com.leepy0.photocull.PhotoCullApp
import com.leepy0.photocull.data.db.AppDatabase
import com.leepy0.photocull.data.db.MediaItemEntity
import com.leepy0.photocull.data.db.ReviewState
import com.leepy0.photocull.data.settings.SettingsRepository
import com.leepy0.photocull.importer.AndroidMediaScanSink
import com.leepy0.photocull.importer.MediaScanSink
import com.leepy0.photocull.storage.StoragePaths
import com.leepy0.photocull.util.Permissions
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
 *   그래서 대표 파일이 휴지통에 없는데 짝(CR3)만 휴지통에 남아 보이지 않게 지워지는 일이 없다.
 * - 영구 삭제는 휴지통 폴더 안의 파일만 지운다.
 * - 파일 접근 권한이 없으면(파일이 '없어 보이는' 상태) 아무것도 바꾸지 않는다.
 * - 파일 작업과 기록은 중간에 취소되지 않는다(NonCancellable). 모든 작업은 하나씩(Mutex) 처리한다.
 *
 * 휴지통 상태(TRASHED)인 행의 trashPath가 null이면 "자리 표시" 행이다: 휴지통으로 옮길 파일이 이미 없었거나
 * (짝만 남은 촬영) 원래 자리에서 옮기지 못한 파일. 복원하면 원래 자리 파일이 있으면 그대로 두고, 없으면 DELETED.
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
) : TrashManager {

    private val mutex = Mutex()
    private val dao get() = db.mediaDao()

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
        var freed = 0L
        for (id in requested) {
            val primary = primaries[id] ?: continue
            if (!primary.reviewState.isActive) continue // 이미 휴지통이거나 영구 삭제됨
            val members = units[primary.unitKey].orEmpty().filter { it.reviewState.isActive }
            when (val outcome = trashUnit(members, dir, now, scan)) {
                is UnitTrash.Moved -> {
                    moved++
                    freed += outcome.bytes
                    trashedIds += id
                }
                UnitTrash.AlreadyGone -> moved++ // 폰에서 이미 지워진 사진: 목록에서만 뺀다(되돌릴 파일 없음)
                UnitTrash.Failed -> failed++
            }
        }
        // 하나도 처리하지 못했으면 '남기기'도 하지 않는다(실패한 작업의 절반만 적용되지 않게)
        val kept = if (moved > 0) applyKeep(keepRequested) else emptyList()
        scan.flush(mediaScanner)
        TrashResult(TrashBatch(trashedIds, kept), moved, failed, freed)
    }

    private suspend fun trashUnit(members: List<MediaItemEntity>, dir: File, now: Long, scan: ScanBatch): UnitTrash {
        if (members.isEmpty()) return UnitTrash.Failed
        val present = members.filter { m -> m.dstPath?.let { File(it).isFile } == true }
        if (present.isEmpty()) {
            writeFresh(members.map { it.id }) { it.copy(reviewState = ReviewState.DELETED, trashPath = null, updatedAt = now) }
            members.forEach { scan.add(it.dstPath, it.mimeType) } // 갤러리에 남은 흔적 정리
            return UnitTrash.AlreadyGone
        }

        val moved = ArrayList<Pair<MediaItemEntity, File>>()
        var bytes = 0L
        var failure = false
        // 짝(CR3)을 먼저, 대표 파일을 마지막에
        for (m in present.sortedBy { it.isPrimary }) {
            val src = File(requireNotNull(m.dstPath))
            val size = src.length()
            val target = TrashFiles.uniqueTrashFile(dir, m.id, src.name)
            if (TrashFiles.move(src, target)) {
                moved += m to target
                bytes += size
            } else {
                failure = true
                break
            }
        }
        if (failure) {
            val stuck = ArrayList<Pair<MediaItemEntity, File>>()
            for ((m, target) in moved.asReversed()) {
                if (!TrashFiles.move(target, File(requireNotNull(m.dstPath)))) stuck += m to target
            }
            if (stuck.isEmpty()) return UnitTrash.Failed
            // 되돌리지 못한 파일이 있으면 그 파일을 잃지 않도록 단위 전체를 휴지통 상태로 기록한다.
            // 옮기지 못한 파일은 자리 표시(trashPath = null)로 남아 원래 자리에 그대로 있다.
            moved.clear()
            moved += stuck
            bytes = stuck.sumOf { it.second.length() }
        }

        val trashPaths = moved.associate { (m, f) -> m.id to f.path }
        writeFresh(members.map { it.id }) { row ->
            row.copy(reviewState = ReviewState.TRASHED, trashedAt = now, trashPath = trashPaths[row.id], updatedAt = now)
        }
        for (m in members) if (m.id in trashPaths || m !in present) scan.add(m.dstPath, m.mimeType)
        return UnitTrash.Moved(bytes)
    }

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
            var count = RestoreCount(0, 0)
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

    override suspend fun restore(ids: Collection<Long>): Int {
        val restored = locked {
            if (ids.isEmpty() || !storageAccessible()) return@locked 0
            restoreUnits(ids.distinct(), ReviewState.KEPT).restored
        }
        if (restored > 0) notifyRestored()
        return restored
    }

    private fun notifyRestored() {
        runCatching { onRestored() }
    }

    private data class RestoreCount(val restored: Int, val failed: Int)

    private suspend fun restoreUnits(ids: List<Long>, targetState: ReviewState): RestoreCount {
        val primaries = getAll(ids).associateBy { it.id }
        val units = membersOf(primaries.values.map { it.unitKey })
        val scan = ScanBatch()
        var restored = 0
        var failed = 0
        val now = clock()
        for (id in ids) {
            val primary = primaries[id] ?: continue
            if (primary.reviewState != ReviewState.TRASHED) continue
            val members = units[primary.unitKey].orEmpty().filter { it.reviewState == ReviewState.TRASHED }
            when (restoreUnit(members, targetState, now, scan)) {
                UnitRestore.Restored -> restored++
                UnitRestore.Gone -> Unit
                UnitRestore.Failed -> failed++
            }
        }
        scan.flush(mediaScanner)
        return RestoreCount(restored, failed)
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
        val gone = ArrayList<MediaItemEntity>()
        for (m in members) {
            val inTrash = m.trashPath?.let(::File)?.takeIf { it.isFile }
            when {
                inTrash != null -> toMove += m to inTrash
                m.trashPath == null && m.dstPath?.let { File(it).isFile } == true -> inPlace += m
                else -> gone += m
            }
        }
        if (toMove.isEmpty() && inPlace.isEmpty()) {
            // 휴지통 파일이 모두 사라졌다(다른 앱이 지움 등): 복원할 것이 없으니 영구 삭제된 것으로 정리
            writeFresh(members.map { it.id }) { it.copy(reviewState = ReviewState.DELETED, trashPath = null, updatedAt = now) }
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
            val stuck = done.filterNot { (_, trashFile, target) -> TrashFiles.move(target, trashFile) }
            // 되돌리지 못한 짝은 원래 폴더에 안전하게 있으므로 복원된 것으로 기록한다(대표 파일은 휴지통에 남아 다시 시도 가능)
            if (stuck.isNotEmpty()) {
                val byId = stuck.associate { (m, _, target) -> m.id to target }
                writeFresh(byId.keys.toList()) { row -> restoredRow(row, byId.getValue(row.id), targetState, now) }
                stuck.forEach { (m, _, target) -> scan.add(target.path, m.mimeType) }
            }
            return UnitRestore.Failed
        }

        val movedTo = done.associate { (m, _, target) -> m.id to target }
        val inPlaceIds = inPlace.map { it.id }.toSet()
        writeFresh(members.map { it.id }) { row ->
            when {
                row.id in movedTo -> restoredRow(row, movedTo.getValue(row.id), targetState, now)
                row.id in inPlaceIds -> row.copy(reviewState = targetState, trashPath = null, trashedAt = null, updatedAt = now)
                else -> row.copy(reviewState = ReviewState.DELETED, trashPath = null, updatedAt = now)
            }
        }
        done.forEach { (m, _, target) -> scan.add(target.path, m.mimeType) }
        return UnitRestore.Restored
    }

    private fun restoredRow(row: MediaItemEntity, target: File, state: ReviewState, now: Long) = row.copy(
        reviewState = state,
        dstPath = target.path,
        fileName = target.name,
        trashPath = null,
        trashedAt = null,
        updatedAt = now,
    )

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
        val cutoff = clock() - days * DAY_MS
        val count = deleteTrashedRows(dao.trashedBefore(cutoff))
        trashDir().takeIf { it.isDirectory }?.let { TrashFiles.cleanPartFiles(it) }
        count
    }

    /** 행들이 속한 촬영 단위의 휴지통 파일을 모두 지운다. 지운 단위(대표 파일 기준) 수 */
    private suspend fun deleteTrashedRows(rows: List<MediaItemEntity>): Int {
        if (rows.isEmpty()) return 0
        val units = membersOf(rows.map { it.unitKey })
        var count = 0
        for ((_, all) in units) {
            val members = all.filter { it.reviewState == ReviewState.TRASHED }
            if (members.isEmpty()) continue
            val hasPrimary = members.any { it.isPrimary }
            if (deleteUnit(members) && hasPrimary) count++
        }
        return count
    }

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
                file == null -> true // 자리 표시: 휴지통에 지울 파일이 없다
                !TrashFiles.isInside(dir, file) -> true // 휴지통 밖 파일은 지우지 않고 기록만 정리
                !file.exists() -> true // 이미 사라짐
                else -> file.delete() || !file.exists()
            }
            if (ok) deleted += m.id else failure = true
        }
        if (deleted.isNotEmpty()) {
            writeFresh(deleted) { it.copy(reviewState = ReviewState.DELETED, trashPath = null, updatedAt = now) }
        }
        return !failure
    }

    // ------------------------------------------------------------------ 보조

    private suspend fun <T> locked(block: suspend () -> T): T =
        withContext(io + NonCancellable) { mutex.withLock { block() } }

    private suspend fun getAll(ids: List<Long>): List<MediaItemEntity> =
        ids.chunked(CHUNK).flatMap { dao.getAll(it) }

    private suspend fun membersOf(unitKeys: List<String>): Map<String, List<MediaItemEntity>> =
        unitKeys.distinct().chunked(CHUNK).flatMap { dao.unitMembersOf(it) }.groupBy { it.unitKey }

    /** 파일 작업 사이에 다른 작업(분석 등)이 쓴 값을 덮지 않도록 쓰기 직전에 다시 읽어 바꾼다 */
    private suspend fun writeFresh(ids: List<Long>, transform: (MediaItemEntity) -> MediaItemEntity) {
        if (ids.isEmpty()) return
        val rows = getAll(ids)
        if (rows.isNotEmpty()) dao.updateAll(rows.map(transform))
    }

    private val ReviewState.isActive: Boolean get() = this == ReviewState.PENDING || this == ReviewState.KEPT

    private sealed interface UnitTrash {
        data class Moved(val bytes: Long) : UnitTrash
        data object AlreadyGone : UnitTrash
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
        const val CHUNK = 500
        const val DAY_MS = 86_400_000L

        /** 앱 컨테이너의 분석을 다시 돌린다. 컨테이너가 준비되지 않았으면(테스트·초기화 중) 건너뛴다. */
        fun kickAnalysis(context: Context): () -> Unit {
            val app = context.applicationContext
            return {
                runCatching { (app as? PhotoCullApp)?.container?.analysis?.kick() }
            }
        }
    }
}
