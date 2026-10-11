package com.leepy0.photocull.trash

import android.content.Context
import android.database.sqlite.SQLiteFullException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.data.db.AppDatabase
import com.leepy0.photocull.data.db.ImportSessionEntity
import com.leepy0.photocull.data.db.ImportStatus
import com.leepy0.photocull.data.db.MediaItemEntity
import com.leepy0.photocull.data.db.MediaType
import com.leepy0.photocull.data.db.ReviewState
import com.leepy0.photocull.data.settings.SettingsRepository
import com.leepy0.photocull.importer.MediaScanSink
import com.leepy0.photocull.storage.StoragePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TrashManagerImplTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var root: File
    private lateinit var manager: TrashManagerImpl
    private val scanned = ArrayList<String>()
    private var now = NOW
    private var retention = 30
    private var accessible = true
    private var sessionId = 0L
    private var restoredCalls = 0

    private val dao get() = db.mediaDao()
    private val destDir get() = File(root, "DCIM/R10")
    private val trashDir get() = File(root, "DCIM/.PhotoCullTrash")

    private fun manager(beforeDbWrite: () -> Unit = {}) = TrashManagerImpl(
        context = context,
        db = db,
        settings = SettingsRepository(context),
        storageRoot = { root },
        mediaScanner = MediaScanSink { paths, _ -> scanned += paths },
        storageAccessible = { accessible },
        clock = { now },
        retentionDays = { retention },
        io = Dispatchers.Unconfined,
        onRestored = { restoredCalls++ },
        beforeDbWrite = beforeDbWrite,
    )

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = AppDatabase.inMemory(context)
        root = tmp.newFolder("storage")
        manager = manager()
        sessionId = runBlocking {
            db.sessionDao().insert(ImportSessionEntity(volumeId = "vol", volumeLabel = "EOS", startedAt = NOW, moveMode = true))
        }
    }

    @After fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------ 준비

    private class Shot(val jpg: MediaItemEntity, val raw: MediaItemEntity?, val jpgBytes: ByteArray, val rawBytes: ByteArray?)

    /** DCIM/R10/<name>.JPG (+ .CR3) 파일과 행을 만든다 */
    private fun shot(name: String, withRaw: Boolean = true, dir: File = destDir, seed: Int = name.hashCode()): Shot = runBlocking {
        val jpgBytes = Random(seed).nextBytes(4_000)
        val rawBytes = if (withRaw) Random(seed + 1).nextBytes(9_000) else null
        val unitKey = "$sessionId:DCIM/100CANON/$name"
        val jpgFile = File(dir, "$name.JPG").also { it.parentFile!!.mkdirs(); it.writeBytes(jpgBytes) }
        val jpgId = dao.insert(row(unitKey, jpgFile, MediaType.PHOTO, isPrimary = true, size = jpgBytes.size.toLong()))
        val rawId = rawBytes?.let {
            val rawFile = File(dir, "$name.CR3").also { f -> f.writeBytes(it) }
            dao.insert(row(unitKey, rawFile, MediaType.RAW, isPrimary = false, size = it.size.toLong()))
        }
        Shot(dao.get(jpgId)!!, rawId?.let { dao.get(it) }, jpgBytes, rawBytes)
    }

    private fun row(unitKey: String, file: File, type: MediaType, isPrimary: Boolean, size: Long) = MediaItemEntity(
        sessionId = sessionId,
        volumeId = "vol",
        srcRelPath = "DCIM/100CANON/${file.name}",
        srcLocation = "/card/DCIM/100CANON/${file.name}",
        srcSize = size,
        srcLastModified = NOW,
        unitKey = unitKey,
        isPrimary = isPrimary,
        type = type,
        mimeType = if (type == MediaType.RAW) "image/x-canon-cr3" else "image/jpeg",
        dstPath = file.path,
        fileName = file.name,
        importStatus = ImportStatus.SOURCE_DELETED,
        capturedAt = NOW,
    )

    private fun item(id: Long): MediaItemEntity = runBlocking { dao.get(id)!! }

    private fun trashFiles(): Set<String> = trashDir.listFiles()?.map { it.name }?.toSet().orEmpty()

    // ------------------------------------------------------------ 휴지통으로

    @Test fun moveUnitMovesJpgAndRawTogether() = runBlocking<Unit> {
        val s = shot("IMG_0001")
        val other = shot("IMG_0002")

        val result = manager.moveToTrash(listOf(s.jpg.id))

        assertThat(result.movedCount).isEqualTo(1)
        assertThat(result.failedCount).isEqualTo(0)
        assertThat(result.freedBytes).isEqualTo((s.jpgBytes.size + s.rawBytes!!.size).toLong())
        assertThat(result.batch.trashedIds).containsExactly(s.jpg.id)

        // 파일: 둘 다 휴지통으로, 원래 자리에는 없음, 내용 그대로
        assertThat(File(s.jpg.dstPath!!).exists()).isFalse()
        assertThat(File(s.raw!!.dstPath!!).exists()).isFalse()
        assertThat(trashFiles()).containsExactly(".nomedia", "${s.jpg.id}_IMG_0001.JPG", "${s.raw.id}_IMG_0001.CR3")
        assertThat(File(trashDir, "${s.raw.id}_IMG_0001.CR3").readBytes()).isEqualTo(s.rawBytes)

        // DB: 둘 다 TRASHED, 원래 위치 기억
        for (id in listOf(s.jpg.id, s.raw.id)) {
            val r = item(id)
            assertThat(r.reviewState).isEqualTo(ReviewState.TRASHED)
            assertThat(r.trashedAt).isEqualTo(NOW)
            assertThat(File(r.trashPath!!).isFile).isTrue()
            assertThat(r.dstPath).isEqualTo(if (id == s.jpg.id) s.jpg.dstPath else s.raw.dstPath)
        }
        // 다른 사진은 그대로
        assertThat(item(other.jpg.id).reviewState).isEqualTo(ReviewState.PENDING)
        assertThat(File(other.jpg.dstPath!!).isFile).isTrue()
        // 갤러리에서 빠지도록 원래 경로를, 확실히 정리되도록 새 경로도 알림
        assertThat(scanned).containsExactly(s.jpg.dstPath, s.raw.dstPath, item(s.jpg.id).trashPath, item(s.raw.id).trashPath)
    }

    @Test fun keepIdsMarkWholeUnitKept() = runBlocking<Unit> {
        val del = shot("IMG_0001")
        val keep = shot("IMG_0002")

        val result = manager.moveToTrash(listOf(del.jpg.id), keepIds = listOf(keep.jpg.id))

        assertThat(result.batch.keptIds).containsExactly(keep.jpg.id)
        assertThat(item(keep.jpg.id).reviewState).isEqualTo(ReviewState.KEPT)
        assertThat(item(keep.raw!!.id).reviewState).isEqualTo(ReviewState.KEPT)
        assertThat(File(keep.jpg.dstPath!!).isFile).isTrue()
    }

    @Test fun movingSameIdTwiceDoesNothingTheSecondTime() = runBlocking<Unit> {
        val s = shot("IMG_0001")
        manager.moveToTrash(listOf(s.jpg.id, s.jpg.id))
        val again = manager.moveToTrash(listOf(s.jpg.id, 999L))

        assertThat(again.movedCount).isEqualTo(0)
        assertThat(again.failedCount).isEqualTo(0)
        assertThat(trashFiles()).hasSize(3) // .nomedia + 2
    }

    @Test fun neverOverwritesLeftoverFileInTrash() = runBlocking<Unit> {
        val s = shot("IMG_0001", withRaw = false)
        trashDir.mkdirs()
        val leftover = File(trashDir, "${s.jpg.id}_IMG_0001.JPG").apply { writeText("old") }

        manager.moveToTrash(listOf(s.jpg.id))

        assertThat(leftover.readText()).isEqualTo("old")
        val r = item(s.jpg.id)
        assertThat(File(r.trashPath!!).name).isEqualTo("${s.jpg.id}_1_IMG_0001.JPG")
        assertThat(File(r.trashPath!!).readBytes()).isEqualTo(s.jpgBytes)
    }

    @Test fun moveWhenWholeUnitIsMissingOnDiskRemovesItFromReview() = runBlocking<Unit> {
        val s = shot("IMG_0001")
        File(s.jpg.dstPath!!).delete()
        File(s.raw!!.dstPath!!).delete()

        val result = manager.moveToTrash(listOf(s.jpg.id))

        // 옮긴 것과 따로 센다(실행 취소로 되돌릴 것이 없다)
        assertThat(result.movedCount).isEqualTo(0)
        assertThat(result.goneCount).isEqualTo(1)
        assertThat(result.failedCount).isEqualTo(0)
        assertThat(result.freedBytes).isEqualTo(0)
        assertThat(result.batch.trashedIds).isEmpty() // 되돌릴 파일 없음
        assertThat(item(s.jpg.id).reviewState).isEqualTo(ReviewState.DELETED)
        assertThat(item(s.raw.id).reviewState).isEqualTo(ReviewState.DELETED)
    }

    @Test fun moveWhenOnlyPrimaryIsMissingKeepsUnitVisibleInTrash() = runBlocking<Unit> {
        val s = shot("IMG_0001")
        File(s.jpg.dstPath!!).delete()

        val result = manager.moveToTrash(listOf(s.jpg.id))

        assertThat(result.movedCount).isEqualTo(1)
        assertThat(result.batch.trashedIds).containsExactly(s.jpg.id)
        // CR3는 휴지통으로, 대표 행은 자리 표시(휴지통 화면에 보이도록 TRASHED)
        assertThat(File(item(s.raw!!.id).trashPath!!).readBytes()).isEqualTo(s.rawBytes)
        assertThat(item(s.jpg.id).reviewState).isEqualTo(ReviewState.TRASHED)
        assertThat(item(s.jpg.id).trashPath).isNull()

        // 복원: CR3는 돌아오고, 없는 JPG는 정리
        assertThat(manager.restore(listOf(s.jpg.id))).isEqualTo(1)
        assertThat(File(s.raw.dstPath!!).readBytes()).isEqualTo(s.rawBytes)
        assertThat(item(s.raw.id).reviewState).isEqualTo(ReviewState.KEPT)
        assertThat(item(s.jpg.id).reviewState).isEqualTo(ReviewState.DELETED)
    }

    @Test fun withoutStorageAccessNothingChanges() = runBlocking<Unit> {
        val s = shot("IMG_0001")
        accessible = false

        val result = manager.moveToTrash(listOf(s.jpg.id), keepIds = listOf(shot("IMG_0002").jpg.id))

        assertThat(result.movedCount).isEqualTo(0)
        assertThat(result.failedCount).isEqualTo(1)
        assertThat(result.batch.keptIds).isEmpty()
        assertThat(item(s.jpg.id).reviewState).isEqualTo(ReviewState.PENDING)
        assertThat(File(s.jpg.dstPath!!).isFile).isTrue()
        // 권한이 없을 때는 '파일 없음'으로 오해해 정리하지 않는다
        assertThat(manager.purgeExpired()).isEqualTo(0)
        assertThat(manager.emptyTrash()).isEqualTo(0)
    }

    // ------------------------------------------------------------ 실행 취소

    @Test fun undoRestoresOriginalPathsAndStatesIncludingKept() = runBlocking<Unit> {
        val a = shot("IMG_0001")
        val b = shot("IMG_0002", withRaw = false)
        val keep = shot("IMG_0003")
        val result = manager.moveToTrash(listOf(a.jpg.id, b.jpg.id), keepIds = listOf(keep.jpg.id))
        scanned.clear()

        manager.undo(result.batch)

        for ((r, bytes) in listOf(a.jpg to a.jpgBytes, a.raw!! to a.rawBytes!!, b.jpg to b.jpgBytes)) {
            val now = item(r.id)
            assertThat(now.reviewState).isEqualTo(ReviewState.PENDING)
            assertThat(now.dstPath).isEqualTo(r.dstPath)
            assertThat(now.trashPath).isNull()
            assertThat(now.trashedAt).isNull()
            assertThat(File(r.dstPath!!).readBytes()).isEqualTo(bytes)
        }
        assertThat(item(keep.jpg.id).reviewState).isEqualTo(ReviewState.PENDING)
        assertThat(item(keep.raw!!.id).reviewState).isEqualTo(ReviewState.PENDING)
        assertThat(trashFiles()).containsExactly(".nomedia")
        assertThat(scanned).containsExactly(a.jpg.dstPath, a.raw.dstPath, b.jpg.dstPath)
        assertThat(restoredCalls).isEqualTo(1) // 분석(그룹 재계산) 다시 돌리기
    }

    @Test fun undoDoesNotTouchKeptIdsThatWereAlreadyKept() = runBlocking<Unit> {
        val del = shot("IMG_0001")
        val keep = shot("IMG_0002")
        dao.setUnitReviewState(listOf(keep.jpg.id), ReviewState.KEPT)

        val result = manager.moveToTrash(listOf(del.jpg.id), keepIds = listOf(keep.jpg.id))
        manager.undo(result.batch)

        assertThat(result.batch.keptIds).isEmpty()
        assertThat(item(keep.jpg.id).reviewState).isEqualTo(ReviewState.KEPT)
    }

    // ------------------------------------------------------------ 복원

    @Test fun restoreToFreeOriginalPathMarksKept() = runBlocking<Unit> {
        val s = shot("IMG_0001")
        manager.moveToTrash(listOf(s.jpg.id))

        assertThat(manager.restore(listOf(s.jpg.id))).isEqualTo(1)

        assertThat(item(s.jpg.id).reviewState).isEqualTo(ReviewState.KEPT)
        assertThat(item(s.raw!!.id).reviewState).isEqualTo(ReviewState.KEPT)
        assertThat(File(s.jpg.dstPath!!).readBytes()).isEqualTo(s.jpgBytes)
        assertThat(item(s.jpg.id).fileName).isEqualTo("IMG_0001.JPG")
        assertThat(trashFiles()).containsExactly(".nomedia")
        assertThat(restoredCalls).isEqualTo(1)
    }

    @Test fun restoreWithOccupiedOriginalPathRenamesWholeUnit() = runBlocking<Unit> {
        val s = shot("IMG_0001")
        manager.moveToTrash(listOf(s.jpg.id))
        // 그사이 같은 이름의 다른 사진(JPG만)이 들어왔다
        val occupant = File(s.jpg.dstPath!!).apply { writeText("someone else") }
        scanned.clear()

        assertThat(manager.restore(listOf(s.jpg.id))).isEqualTo(1)

        assertThat(occupant.readText()).isEqualTo("someone else")
        val jpg = item(s.jpg.id)
        val raw = item(s.raw!!.id)
        assertThat(jpg.fileName).isEqualTo("IMG_0001_restored1.JPG")
        assertThat(raw.fileName).isEqualTo("IMG_0001_restored1.CR3") // 짝도 같은 접미사
        assertThat(jpg.dstPath).isEqualTo(File(destDir, "IMG_0001_restored1.JPG").path)
        assertThat(File(jpg.dstPath!!).readBytes()).isEqualTo(s.jpgBytes)
        assertThat(File(raw.dstPath!!).readBytes()).isEqualTo(s.rawBytes)
        assertThat(jpg.reviewState).isEqualTo(ReviewState.KEPT)
        assertThat(scanned).containsExactly(jpg.dstPath, raw.dstPath)
    }

    @Test fun restoreRecreatesMissingFolder() = runBlocking<Unit> {
        val monthDir = File(destDir, "2026-10")
        val s = shot("IMG_0001", dir = monthDir)
        manager.moveToTrash(listOf(s.jpg.id))
        monthDir.deleteRecursively()

        assertThat(manager.restore(listOf(s.jpg.id))).isEqualTo(1)

        assertThat(File(monthDir, "IMG_0001.JPG").readBytes()).isEqualTo(s.jpgBytes)
        assertThat(File(monthDir, "IMG_0001.CR3").readBytes()).isEqualTo(s.rawBytes)
    }

    @Test fun restoreWhenTrashFileIsGoneMarksDeleted() = runBlocking<Unit> {
        val s = shot("IMG_0001")
        manager.moveToTrash(listOf(s.jpg.id))
        trashDir.listFiles()!!.filter { it.name != ".nomedia" }.forEach { it.delete() }

        assertThat(manager.restore(listOf(s.jpg.id))).isEqualTo(0)

        assertThat(restoredCalls).isEqualTo(0)
        assertThat(item(s.jpg.id).reviewState).isEqualTo(ReviewState.DELETED)
        assertThat(item(s.raw!!.id).reviewState).isEqualTo(ReviewState.DELETED)
        assertThat(item(s.jpg.id).trashPath).isNull()
    }

    @Test fun restoreIgnoresItemsNotInTrash() = runBlocking<Unit> {
        val s = shot("IMG_0001")
        assertThat(manager.restore(listOf(s.jpg.id, 12345L))).isEqualTo(0)
        assertThat(item(s.jpg.id).reviewState).isEqualTo(ReviewState.PENDING)
    }

    // ------------------------------------------------------------ 영구 삭제

    @Test fun deleteForeverRemovesTrashFilesOfUnit() = runBlocking<Unit> {
        val a = shot("IMG_0001")
        val b = shot("IMG_0002")
        manager.moveToTrash(listOf(a.jpg.id, b.jpg.id))

        assertThat(manager.deleteForever(listOf(a.jpg.id))).isEqualTo(1)

        for (id in listOf(a.jpg.id, a.raw!!.id)) {
            assertThat(item(id).reviewState).isEqualTo(ReviewState.DELETED)
            assertThat(item(id).trashPath).isNull()
        }
        assertThat(trashFiles()).containsExactly(".nomedia", "${b.jpg.id}_IMG_0002.JPG", "${b.raw!!.id}_IMG_0002.CR3")
        assertThat(item(b.jpg.id).reviewState).isEqualTo(ReviewState.TRASHED)
    }

    @Test fun deleteForeverIgnoresItemsNotInTrash() = runBlocking<Unit> {
        val s = shot("IMG_0001")
        assertThat(manager.deleteForever(listOf(s.jpg.id))).isEqualTo(0)
        assertThat(File(s.jpg.dstPath!!).isFile).isTrue()
        assertThat(item(s.jpg.id).reviewState).isEqualTo(ReviewState.PENDING)
    }

    @Test fun deleteForeverWithMissingTrashFileStillCompletes() = runBlocking<Unit> {
        val s = shot("IMG_0001")
        manager.moveToTrash(listOf(s.jpg.id))
        File(item(s.raw!!.id).trashPath!!).delete()

        assertThat(manager.deleteForever(listOf(s.jpg.id))).isEqualTo(1)

        assertThat(item(s.jpg.id).reviewState).isEqualTo(ReviewState.DELETED)
        assertThat(item(s.raw.id).reviewState).isEqualTo(ReviewState.DELETED)
        assertThat(trashFiles()).containsExactly(".nomedia")
    }

    @Test fun deleteForeverNeverDeletesFilesOutsideTrash() = runBlocking<Unit> {
        val s = shot("IMG_0001", withRaw = false)
        // 잘못된 기록: 휴지통 경로가 원래 사진을 가리킨다
        dao.update(item(s.jpg.id).copy(reviewState = ReviewState.TRASHED, trashedAt = NOW, trashPath = s.jpg.dstPath))

        manager.deleteForever(listOf(s.jpg.id))

        assertThat(File(s.jpg.dstPath!!).readBytes()).isEqualTo(s.jpgBytes)
    }

    @Test fun emptyTrashDeletesEverythingIncludingOrphans() = runBlocking<Unit> {
        val a = shot("IMG_0001")
        val b = shot("IMG_0002", withRaw = false)
        manager.moveToTrash(listOf(a.jpg.id, b.jpg.id))
        // 대표 파일만 복원된 상태로 휴지통에 남은 짝(이전 버전 등)
        dao.update(item(a.jpg.id).copy(reviewState = ReviewState.KEPT))

        // b 단위 + 대표 파일 없이 남은 a의 짝 단위
        assertThat(manager.emptyTrash()).isEqualTo(2)

        assertThat(trashFiles()).containsExactly(".nomedia", "${a.jpg.id}_IMG_0001.JPG")
        assertThat(item(a.raw!!.id).reviewState).isEqualTo(ReviewState.DELETED)
        assertThat(item(b.jpg.id).reviewState).isEqualTo(ReviewState.DELETED)
    }

    // ------------------------------------------------------------ 보관 기간

    @Test fun purgeExpiredRespectsRetention() = runBlocking<Unit> {
        val old = shot("IMG_0001")
        now = NOW - 31 * DAY
        manager.moveToTrash(listOf(old.jpg.id))
        val recent = shot("IMG_0002")
        now = NOW - 10 * DAY
        manager.moveToTrash(listOf(recent.jpg.id))
        now = NOW

        assertThat(manager.purgeExpired()).isEqualTo(1)

        assertThat(item(old.jpg.id).reviewState).isEqualTo(ReviewState.DELETED)
        assertThat(item(old.raw!!.id).reviewState).isEqualTo(ReviewState.DELETED)
        assertThat(item(recent.jpg.id).reviewState).isEqualTo(ReviewState.TRASHED)
        assertThat(trashFiles()).containsExactly(".nomedia", "${recent.jpg.id}_IMG_0002.JPG", "${recent.raw!!.id}_IMG_0002.CR3")

        // 7일로 줄이면 10일 된 것도 지운다
        retention = 7
        assertThat(manager.purgeExpired()).isEqualTo(1)
        assertThat(item(recent.jpg.id).reviewState).isEqualTo(ReviewState.DELETED)
    }

    @Test fun purgeExpiredNeverWhenRetentionIsZero() = runBlocking<Unit> {
        val s = shot("IMG_0001")
        now = NOW - 400 * DAY
        manager.moveToTrash(listOf(s.jpg.id))
        now = NOW
        retention = 0

        assertThat(manager.purgeExpired()).isEqualTo(0)
        assertThat(item(s.jpg.id).reviewState).isEqualTo(ReviewState.TRASHED)
        assertThat(File(item(s.jpg.id).trashPath!!).isFile).isTrue()
    }

    @Test fun purgeCleansOnlyOwnPartLeftovers() = runBlocking<Unit> {
        trashDir.mkdirs()
        val own = File(trashDir, "5_IMG_0005.JPG" + TrashFiles.TRASH_PART_SUFFIX).apply { writeText("x") }
        val importers = File(trashDir, "6_IMG_0006.JPG" + StoragePaths.PART_SUFFIX).apply { writeText("x") }
        val unknown = File(trashDir, "notes.txt").apply { writeText("keep me") }

        manager.purgeExpired()

        assertThat(own.exists()).isFalse()
        assertThat(importers.exists()).isTrue() // 가져오기의 임시 파일은 건드리지 않는다
        assertThat(unknown.exists()).isTrue()
    }

    // ------------------------------------------------------------ 가져오는 중 / 기록 실패

    @Test fun unitStillImportingIsNotTrashedNorDeleted() = runBlocking<Unit> {
        val s = shot("IMG_0001")
        // CR3는 아직 복사 중(.part): 원래 자리에 파일이 없다
        File(s.raw!!.dstPath!!).delete()
        dao.update(item(s.raw.id).copy(importStatus = ImportStatus.PENDING))

        val result = manager.moveToTrash(listOf(s.jpg.id))

        assertThat(result.movedCount).isEqualTo(0)
        assertThat(result.goneCount).isEqualTo(0)
        assertThat(result.failedCount).isEqualTo(1)
        assertThat(result.importingCount).isEqualTo(1)
        assertThat(result.batch.trashedIds).isEmpty()
        assertThat(item(s.jpg.id).reviewState).isEqualTo(ReviewState.PENDING)
        assertThat(item(s.raw.id).reviewState).isEqualTo(ReviewState.PENDING)
        assertThat(File(s.jpg.dstPath!!).readBytes()).isEqualTo(s.jpgBytes)

        // 모든 파일이 아직 없는 단위도 '이미 지워짐'으로 처리하지 않는다
        File(s.jpg.dstPath!!).delete()
        val again = manager.moveToTrash(listOf(s.jpg.id))
        assertThat(again.goneCount).isEqualTo(0)
        assertThat(again.importingCount).isEqualTo(1)
        assertThat(item(s.jpg.id).reviewState).isEqualTo(ReviewState.PENDING)
    }

    @Test fun dbFailureAfterTrashMoveMovesFilesBack() = runBlocking<Unit> {
        val a = shot("IMG_0001")
        val b = shot("IMG_0002")
        val failing = manager(beforeDbWrite = { throw SQLiteFullException("full") })

        val result = failing.moveToTrash(listOf(a.jpg.id, b.jpg.id))

        assertThat(result.movedCount).isEqualTo(0)
        assertThat(result.failedCount).isEqualTo(2)
        for (s in listOf(a, b)) {
            assertThat(File(s.jpg.dstPath!!).readBytes()).isEqualTo(s.jpgBytes)
            assertThat(File(s.raw!!.dstPath!!).readBytes()).isEqualTo(s.rawBytes)
            assertThat(item(s.jpg.id).reviewState).isEqualTo(ReviewState.PENDING)
            assertThat(item(s.jpg.id).trashPath).isNull()
        }
        assertThat(trashFiles()).containsExactly(".nomedia")
    }

    @Test fun dbFailureAfterRestoreMovesFilesBackToTrash() = runBlocking<Unit> {
        val s = shot("IMG_0001")
        manager.moveToTrash(listOf(s.jpg.id))
        val trashedJpg = item(s.jpg.id).trashPath!!
        val failing = manager(beforeDbWrite = { throw SQLiteFullException("full") })

        val report = failing.restoreDetailed(listOf(s.jpg.id))

        assertThat(report.restored).isEqualTo(0)
        assertThat(report.failed).isEqualTo(1)
        assertThat(File(trashedJpg).readBytes()).isEqualTo(s.jpgBytes)
        assertThat(File(s.jpg.dstPath!!).exists()).isFalse()
        assertThat(File(s.raw!!.dstPath!!).exists()).isFalse()
        assertThat(item(s.jpg.id).reviewState).isEqualTo(ReviewState.TRASHED)
    }

    @Test fun trashWriteKeepsConcurrentAnalysisAndImportValues() = runBlocking<Unit> {
        val s = shot("IMG_0001", withRaw = false)
        dao.update(item(s.jpg.id).copy(importStatus = ImportStatus.VERIFIED))
        // 휴지통이 행을 읽은 뒤 기록하기 직전에 분석·가져오기가 값을 썼다
        val racing = manager(beforeDbWrite = {
            db.openHelper.writableDatabase.execSQL(
                "UPDATE media_item SET sharpness = 42.0, importStatus = 'SOURCE_DELETED' WHERE id = ?",
                arrayOf<Any?>(s.jpg.id),
            )
        })

        racing.moveToTrash(listOf(s.jpg.id))

        val r = item(s.jpg.id)
        assertThat(r.reviewState).isEqualTo(ReviewState.TRASHED)
        assertThat(r.sharpness).isEqualTo(42.0)
        assertThat(r.importStatus).isEqualTo(ImportStatus.SOURCE_DELETED)
    }

    @Test fun deletePlaceholderWhoseFileIsStillInDcimKeepsIt() = runBlocking<Unit> {
        val s = shot("IMG_0001", withRaw = false)
        // 예전 버전이 남긴 자리 표시: 휴지통 상태인데 파일은 원래 자리에
        dao.update(item(s.jpg.id).copy(reviewState = ReviewState.TRASHED, trashedAt = NOW, trashPath = null))

        assertThat(manager.deleteForever(listOf(s.jpg.id))).isEqualTo(0)

        assertThat(File(s.jpg.dstPath!!).readBytes()).isEqualTo(s.jpgBytes)
        assertThat(item(s.jpg.id).reviewState).isEqualTo(ReviewState.TRASHED) // 복원하면 그대로 돌아온다
        assertThat(manager.restore(listOf(s.jpg.id))).isEqualTo(1)
        assertThat(item(s.jpg.id).reviewState).isEqualTo(ReviewState.KEPT)
    }

    @Test fun restoreReportsGoneSeparately() = runBlocking<Unit> {
        val a = shot("IMG_0001")
        val b = shot("IMG_0002")
        manager.moveToTrash(listOf(a.jpg.id, b.jpg.id))
        File(item(a.jpg.id).trashPath!!).delete()
        File(item(a.raw!!.id).trashPath!!).delete()

        val report = manager.restoreDetailed(listOf(a.jpg.id, b.jpg.id))

        assertThat(report).isEqualTo(RestoreReport(restored = 1, gone = 1, failed = 0))
        assertThat(item(a.jpg.id).reviewState).isEqualTo(ReviewState.DELETED)
        assertThat(item(b.jpg.id).reviewState).isEqualTo(ReviewState.KEPT)
    }

    @Test fun previewPurgeMatchesWhatPurgeDeletes() = runBlocking<Unit> {
        val old = shot("IMG_0001")
        val oldSingle = shot("IMG_0002", withRaw = false)
        now = NOW - 20 * DAY
        manager.moveToTrash(listOf(old.jpg.id, oldSingle.jpg.id))
        val recent = shot("IMG_0003")
        now = NOW - 2 * DAY
        manager.moveToTrash(listOf(recent.jpg.id))
        now = NOW

        val preview = manager.previewPurge(7)!!
        assertThat(preview.count).isEqualTo(2)
        // JPG+CR3까지 실제로 지워지는 용량
        assertThat(preview.bytes).isEqualTo((old.jpgBytes.size + old.rawBytes!!.size + oldSingle.jpgBytes.size).toLong())
        assertThat(manager.previewPurge(0)).isEqualTo(PurgePreview(0, 0))

        retention = 7
        assertThat(manager.purgeExpired()).isEqualTo(preview.count)
        assertThat(item(recent.jpg.id).reviewState).isEqualTo(ReviewState.TRASHED)
    }

    // ------------------------------------------------------------ 시작 시 맞추기

    /** 파일만 휴지통으로 옮기고 기록 전에 앱이 끝난 상태를 만든다 */
    private fun moveWithoutRecord(r: MediaItemEntity): File {
        trashDir.mkdirs()
        val target = File(trashDir, "${r.id}_${File(r.dstPath!!).name}")
        check(File(r.dstPath!!).renameTo(target))
        return target
    }

    @Test fun reconcileRecordsUnitMovedBeforeProcessDeath() = runBlocking<Unit> {
        val s = shot("IMG_0001")
        val rawFile = moveWithoutRecord(s.raw!!)
        val jpgFile = moveWithoutRecord(s.jpg)
        now = NOW + DAY

        assertThat(manager.reconcile()).isEqualTo(2)

        val jpg = item(s.jpg.id)
        assertThat(jpg.reviewState).isEqualTo(ReviewState.TRASHED)
        assertThat(jpg.trashPath).isEqualTo(jpgFile.path)
        assertThat(item(s.raw.id).trashPath).isEqualTo(rawFile.path)
        // 보관 기간은 지금부터(파일 수정 시각이 아니라): 바로 이어지는 정리에서 지워지지 않는다
        assertThat(jpg.trashedAt).isEqualTo(NOW + DAY)
        retention = 7
        now = NOW + 3 * DAY
        assertThat(manager.purgeExpired()).isEqualTo(0)
        assertThat(jpgFile.readBytes()).isEqualTo(s.jpgBytes)
        // 휴지통 화면에서 보이고 복원된다
        assertThat(manager.restore(listOf(s.jpg.id))).isEqualTo(1)
        assertThat(File(s.jpg.dstPath!!).readBytes()).isEqualTo(s.jpgBytes)
    }

    @Test fun reconcileMovesBackPartOfInterruptedUnit() = runBlocking<Unit> {
        val s = shot("IMG_0001")
        val rawFile = moveWithoutRecord(s.raw!!) // CR3만 옮기고 끝남(대표 파일은 원래 자리)

        assertThat(manager.reconcile()).isEqualTo(1)

        assertThat(rawFile.exists()).isFalse()
        assertThat(File(s.raw.dstPath!!).readBytes()).isEqualTo(s.rawBytes)
        assertThat(item(s.raw.id).reviewState).isEqualTo(ReviewState.PENDING)
        assertThat(item(s.jpg.id).reviewState).isEqualTo(ReviewState.PENDING)
    }

    @Test fun reconcileMarksRestoredWhenRestoreWasNotRecorded() = runBlocking<Unit> {
        val s = shot("IMG_0001", withRaw = false)
        dao.update(item(s.jpg.id).copy(sha256 = TrashFiles.sha256Of(File(s.jpg.dstPath!!))))
        manager.moveToTrash(listOf(s.jpg.id))
        // 복원 위치("_restored1")로 옮긴 뒤 기록 전에 끝남
        val restored = File(destDir, "IMG_0001_restored1.JPG")
        check(File(item(s.jpg.id).trashPath!!).renameTo(restored))
        // 원래 자리에는 다른 사진이 들어와 있다(같은 크기라도 해시가 달라 내 것이 아니다)
        File(s.jpg.dstPath!!).writeBytes(Random(99).nextBytes(s.jpgBytes.size))

        assertThat(manager.reconcile()).isEqualTo(1)

        val r = item(s.jpg.id)
        assertThat(r.reviewState).isEqualTo(ReviewState.KEPT)
        assertThat(r.dstPath).isEqualTo(restored.path)
        assertThat(r.fileName).isEqualTo("IMG_0001_restored1.JPG")
        assertThat(r.trashPath).isNull()
    }

    @Test fun reconcileLeavesUnknownAndMismatchedFiles() = runBlocking<Unit> {
        val s = shot("IMG_0001", withRaw = false)
        trashDir.mkdirs()
        val unknown = File(trashDir, "holiday.jpg").apply { writeText("x") }
        val noRow = File(trashDir, "9999_IMG_9999.JPG").apply { writeText("x") }
        val wrongName = File(trashDir, "${s.jpg.id}_OTHER.JPG").apply { writeText("x") }
        val ownPart = File(trashDir, "7_IMG_0007.JPG" + TrashFiles.TRASH_PART_SUFFIX).apply { writeText("x") }

        assertThat(manager.reconcile()).isEqualTo(0)

        assertThat(unknown.exists()).isTrue()
        assertThat(noRow.exists()).isTrue()
        assertThat(wrongName.exists()).isTrue()
        assertThat(ownPart.exists()).isFalse()
        assertThat(item(s.jpg.id).reviewState).isEqualTo(ReviewState.PENDING)
    }

    @Test fun reconcileDoesNothingWithoutStorageAccess() = runBlocking<Unit> {
        val s = shot("IMG_0001", withRaw = false)
        moveWithoutRecord(s.jpg)
        accessible = false
        assertThat(manager.reconcile()).isEqualTo(0)
        assertThat(item(s.jpg.id).reviewState).isEqualTo(ReviewState.PENDING)
    }

    // ------------------------------------------------------------ 파일 도우미

    @Test fun moveNeverOverwritesExistingTarget() {
        val src = tmp.newFile("a.jpg").apply { writeText("a") }
        val dst = tmp.newFile("b.jpg").apply { writeText("b") }
        assertThat(TrashFiles.move(src, dst)).isFalse()
        assertThat(src.readText()).isEqualTo("a")
        assertThat(dst.readText()).isEqualTo("b")
    }

    @Test fun copyFallbackVerifiesHashAndNeverTouchesExistingPart() {
        val dir = tmp.newFolder("copy")
        val bytes = Random(5).nextBytes(50_000)
        val src = File(dir, "a.jpg").apply { writeBytes(bytes) }
        val dst = File(dir, "b.jpg")

        // 사본 해시가 다르면(손상) 원본을 남기고 사본·임시 파일을 지운다
        assertThat(TrashFiles.copyThenDelete(src, dst, hashOf = { "bad" })).isFalse()
        assertThat(src.readBytes()).isEqualTo(bytes)
        assertThat(dst.exists()).isFalse()
        assertThat(dir.list()!!.toList()).containsExactly("a.jpg")

        // 같은 이름의 임시 파일이 이미 있으면(남의 것일 수 있다) 지우지 않고 포기한다
        val part = File(dir, "b.jpg" + TrashFiles.TRASH_PART_SUFFIX).apply { writeText("someone") }
        assertThat(TrashFiles.copyThenDelete(src, dst)).isFalse()
        assertThat(part.readText()).isEqualTo("someone")
        assertThat(src.exists()).isTrue()
        part.delete()

        assertThat(TrashFiles.copyThenDelete(src, dst)).isTrue()
        assertThat(src.exists()).isFalse()
        assertThat(dst.readBytes()).isEqualTo(bytes)
        assertThat(TrashFiles.TRASH_PART_SUFFIX).isNotEqualTo(StoragePaths.PART_SUFFIX)
    }

    @Test fun parseTrashNames() {
        assertThat(TrashFiles.parseTrashName("12_IMG_0001.JPG")).isEqualTo(12L to setOf("IMG_0001.JPG"))
        assertThat(TrashFiles.parseTrashName("12_3_IMG_0001.JPG")).isEqualTo(12L to setOf("3_IMG_0001.JPG", "IMG_0001.JPG"))
        assertThat(TrashFiles.parseTrashName(".nomedia")).isNull()
        assertThat(TrashFiles.parseTrashName("notes.txt")).isNull()
    }

    @Test fun restoreTargetsPickSharedSuffix() {
        val dir = tmp.newFolder("d")
        File(dir, "IMG_1.CR3").writeText("x")
        File(dir, "IMG_1_restored1.JPG").writeText("x")
        val targets = TrashFiles.restoreTargets(mapOf(1L to File(dir, "IMG_1.JPG"), 2L to File(dir, "IMG_1.CR3")))!!
        assertThat(targets.getValue(1L).name).isEqualTo("IMG_1_restored2.JPG")
        assertThat(targets.getValue(2L).name).isEqualTo("IMG_1_restored2.CR3")
        assertThat(TrashFiles.withSuffix(File(dir, "NOEXT"), "_restored1").name).isEqualTo("NOEXT_restored1")
    }

    private companion object {
        const val DAY = 86_400_000L
        /** 2026-10-11 12:00 UTC */
        const val NOW = 1_791_720_000_000L
    }
}
