package com.leepy0.photocull.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.data.db.AppDatabase
import com.leepy0.photocull.data.db.GroupKind
import com.leepy0.photocull.data.db.ImportSessionEntity
import com.leepy0.photocull.data.db.ImportStatus
import com.leepy0.photocull.data.db.MediaItemEntity
import com.leepy0.photocull.data.db.MediaType
import com.leepy0.photocull.data.db.ReviewState
import com.leepy0.photocull.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import java.io.File
import java.util.concurrent.CountDownLatch
import java.time.ZonedDateTime
import java.time.ZoneId
import kotlin.random.Random

/** 실제 JPEG 파일 + 메모리 DB로 분석 작업자 전체 흐름을 확인한다. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AnalysisControllerImplTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var scope: CoroutineScope
    private lateinit var controller: AnalysisControllerImpl
    private lateinit var dir: File
    private var sessionId = 0L

    /** EXIF에 적을 촬영 시각(서울 14:03:12.25) */
    private val t0 = ZonedDateTime.of(2026, 10, 11, 14, 3, 12, 250_000_000, ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        warmUpNativeRuntimeOnTestThread()
        db = AppDatabase.inMemory(context)
        db.openHelper.writableDatabase
        settings = SettingsRepository(context)
        // DataStore는 프로세스 단일 인스턴스라 이전 테스트의 값이 남을 수 있다.
        settings.setBurstGapMs(1_000)
        settings.setSimilarGapMs(10_000)
        settings.setSimilarMaxDistance(10)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        controller = AnalysisControllerImpl(context, scope, db, settings, lingerMs = 0)
        dir = File(context.cacheDir, "analysis-test").apply { deleteRecursively(); mkdirs() }
        sessionId = db.sessionDao().insert(ImportSessionEntity(volumeId = "vol", volumeLabel = "EOS_DIGITAL", startedAt = 0, moveMode = true))
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        dir.deleteRecursively()
        ShadowMediaMetadataRetriever.reset()
    }

    /**
     * Robolectric 네이티브 그래픽의 파일 디코드 경로(BitmapFactory.decodeFile, ImageDecoder)를 테스트 스레드에서 먼저 한 번 탄다.
     * 이 경로는 처음 쓸 때 libnativehelper의 JniConstants를 초기화하는데, 그게 분석기의 백그라운드 스레드에서 일어나면
     * 가끔 "JniConstants: Class not found: java/nio/FloatBuffer"로 테스트 프로세스가 SIGABRT로 죽는다(Robolectric 한정).
     */
    private fun warmUpNativeRuntimeOnTestThread() {
        val f = File(context.cacheDir, "warmup.jpg")
        val bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inJustDecodeBounds = true })
        BitmapFactory.decodeFile(f.path)?.let { it.getPixels(IntArray(8), 0, 8, 0, 0, 8, 1); it.recycle() }
        runCatching { ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)).recycle() }
        f.delete()
    }

    // ---- 픽스처 ----

    private val w = 600
    private val h = 400

    /** 왼쪽 위 타일(0,0)에만 디테일이 있는 장면 */
    private fun sceneGray(): ByteArray {
        val img = TestImages.flat(w, h, 128)
        TestImages.paste(img, w, TestImages.checkerboard(90, 90, cell = 3), 90, 90, x0 = 5, y0 = 5)
        return img
    }

    private fun writeJpeg(name: String, gray: ByteArray, gw: Int = w, gh: Int = h): File {
        val pixels = IntArray(gw * gh) { i -> val v = gray[i].toInt() and 0xFF; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val bmp = Bitmap.createBitmap(pixels, gw, gh, Bitmap.Config.ARGB_8888)
        val f = File(dir, name)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        return f
    }

    private suspend fun insert(
        name: String,
        file: File,
        capturedAt: Long,
        type: MediaType = MediaType.PHOTO,
        reviewState: ReviewState = ReviewState.PENDING,
    ): Long = db.mediaDao().insert(
        MediaItemEntity(
            sessionId = sessionId,
            volumeId = "vol",
            srcRelPath = "DCIM/100CANON/$name",
            srcLocation = "/card/DCIM/100CANON/$name",
            srcSize = file.length(),
            srcLastModified = capturedAt,
            unitKey = "$sessionId:DCIM/100CANON/${name.substringBeforeLast('.')}",
            isPrimary = true,
            type = type,
            mimeType = if (type == MediaType.VIDEO) "video/mp4" else "image/jpeg",
            dstPath = file.path,
            fileName = name,
            importStatus = ImportStatus.SOURCE_DELETED,
            capturedAt = capturedAt,
            reviewState = reviewState,
        ),
    )

    private class Seeded(val a: Long, val b: Long, val c: Long, val corrupt: Long, val missing: Long, val video: Long, val badVideo: Long, val trashed: Long)

    private suspend fun seed(): Seeded {
        // A: 선명, EXIF(시각·노출·회전 6 = 시계 방향 90°). DB의 촬영 시각은 일부러 틀리게(파일 수정 시각 흉내)
        val fa = writeJpeg("IMG_0001.JPG", sceneGray())
        ExifInterface(fa).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:11 14:03:12")
            setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, "25")
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+09:00")
            setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "400")
            setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "1/250")
            setAttribute(ExifInterface.TAG_F_NUMBER, "56/10")
            setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "50/1")
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val a = insert("IMG_0001.JPG", fa, capturedAt = t0 + 5_000)
        // B: 같은 장면을 흐리게, 0.3초 뒤(연사)
        val b = insert("IMG_0002.JPG", writeJpeg("IMG_0002.JPG", TestImages.boxBlur(sceneGray(), w, h, 3)), capturedAt = t0 + 300)
        // C: 1분 뒤 다른 장면(혼자), 이미 '남기기'
        val c = insert("IMG_0003.JPG", writeJpeg("IMG_0003.JPG", TestImages.noiseTexture(w, h, seed = 9)), capturedAt = t0 + 60_000, reviewState = ReviewState.KEPT)
        // 손상 파일, 없는 파일
        val bad = File(dir, "IMG_0004.JPG").apply { writeBytes(Random(1).nextBytes(4096)) }
        val corrupt = insert("IMG_0004.JPG", bad, capturedAt = t0 + 120_000)
        val missing = insert("IMG_0005.JPG", File(dir, "IMG_0005.JPG"), capturedAt = t0 + 180_000)
        // 영상: 메타데이터 있음 / 없음
        val fv = File(dir, "MVI_0006.MP4").apply { writeBytes(ByteArray(64)) }
        ShadowMediaMetadataRetriever.addMetadata(fv.path, MediaMetadataRetriever.METADATA_KEY_DURATION, "2500")
        ShadowMediaMetadataRetriever.addMetadata(fv.path, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, "1920")
        ShadowMediaMetadataRetriever.addMetadata(fv.path, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, "1080")
        ShadowMediaMetadataRetriever.addMetadata(fv.path, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION, "90")
        val video = insert("MVI_0006.MP4", fv, capturedAt = t0 + 240_000, type = MediaType.VIDEO)
        val fv2 = File(dir, "MVI_0007.MP4").apply { writeBytes(ByteArray(64)) }
        val badVideo = insert("MVI_0007.MP4", fv2, capturedAt = t0 + 300_000, type = MediaType.VIDEO)
        // 휴지통에 있는 사진은 분석하지 않는다
        val trashed = insert("IMG_0008.JPG", writeJpeg("IMG_0008.JPG", sceneGray()), capturedAt = t0 + 400, reviewState = ReviewState.TRASHED)
        return Seeded(a, b, c, corrupt, missing, video, badVideo, trashed)
    }

    private suspend fun idle() = withTimeout(60_000) {
        controller.awaitIdle()
        controller.status.first { !it.running }
    }

    // ---- 테스트 ----

    @Test
    fun analyzesPhotosAndVideos_recordsFailures_andGroupsBurst() = runBlocking {
        val s = seed()
        controller.kick()
        idle()
        val dao = db.mediaDao()

        val a = dao.get(s.a)!!
        val b = dao.get(s.b)!!
        val c = dao.get(s.c)!!
        assertThat(a.analysisFailed).isFalse()
        assertThat(a.sharpness).isNotNull()
        assertThat(a.dhash).isNotNull()
        // 회전 6: 표시 크기는 세로, 초점(저장 영상 왼쪽 위 타일)은 화면의 오른쪽 위
        assertThat(a.width).isEqualTo(400)
        assertThat(a.height).isEqualTo(600)
        assertThat(a.focusX!!.toDouble()).isWithin(1e-4).of(1 - 0.5 / 4)
        assertThat(a.focusY!!.toDouble()).isWithin(1e-4).of(0.5 / 6)
        // EXIF
        assertThat(a.capturedAt).isEqualTo(t0)
        assertThat(a.iso).isEqualTo(400)
        assertThat(a.exposure).isEqualTo("1/250")
        assertThat(a.aperture).isEqualTo("f/5.6")
        assertThat(a.focalLength).isEqualTo("50mm")

        assertThat(b.sharpness!!).isLessThan(a.sharpness!! * 0.5)
        assertThat(b.width).isEqualTo(600)
        assertThat(b.height).isEqualTo(400)
        assertThat(b.capturedAt).isEqualTo(t0 + 300) // EXIF 없음 → 그대로
        assertThat(DHash.hamming(a.dhash!!, b.dhash!!)).isAtMost(10)

        // 분석이 검토 상태를 덮어쓰지 않는다
        assertThat(c.sharpness).isNotNull()
        assertThat(c.reviewState).isEqualTo(ReviewState.KEPT)

        assertThat(dao.get(s.corrupt)!!.analysisFailed).isTrue()
        assertThat(dao.get(s.missing)!!.analysisFailed).isTrue()
        assertThat(dao.get(s.trashed)!!.sharpness).isNull()
        assertThat(dao.get(s.trashed)!!.analysisFailed).isFalse()

        val v = dao.get(s.video)!!
        assertThat(v.durationMs).isEqualTo(2_500)
        assertThat(v.width).isEqualTo(1080) // 90° 회전
        assertThat(v.height).isEqualTo(1920)
        assertThat(v.analysisFailed).isFalse()
        val bv = dao.get(s.badVideo)!!
        assertThat(bv.analysisFailed).isTrue()
        assertThat(bv.durationMs).isNull() // 길이 0으로 '실수 촬영'에 잘못 들어가지 않게

        // 그룹: A+B 연사, 베스트 A. C는 혼자라 그룹 없음
        val groups = db.groupDao().all()
        assertThat(groups).hasSize(1)
        val g = groups.single()
        assertThat(g.kind).isEqualTo(GroupKind.BURST)
        assertThat(g.bestItemId).isEqualTo(s.a)
        assertThat(g.bestPinned).isFalse()
        assertThat(g.count).isEqualTo(2)
        assertThat(g.startAt).isEqualTo(t0)
        assertThat(g.endAt).isEqualTo(t0 + 300)
        assertThat(dao.get(s.a)!!.groupId).isEqualTo(g.id)
        assertThat(dao.get(s.b)!!.groupId).isEqualTo(g.id)
        assertThat(dao.get(s.c)!!.groupId).isNull()

        // 진행 상태: 사진 5 + 영상 2 처리, 남은 것 없음
        val st = controller.status.value
        assertThat(st.running).isFalse()
        assertThat(st.done).isEqualTo(7)
        assertThat(st.total).isEqualTo(7)
        assertThat(dao.observePendingAnalysisCount().first()).isEqualTo(0)

        // 다시 돌려도 실패 항목을 무한 반복하지 않고 바로 끝난다
        controller.kick()
        idle()
        assertThat(db.groupDao().all().single().id).isEqualTo(g.id)
    }

    @Test
    fun regroup_keepsPinnedBestAndGroupId_andFollowsSettings() = runBlocking {
        val s = seed()
        controller.kick()
        idle()
        val g = db.groupDao().all().single()

        // 사용자가 흐린 B를 베스트로 고정 → 다시 묶어도 유지, 그룹 id도 그대로
        db.groupDao().pinBest(g.id, s.b)
        controller.regroup()
        idle()
        val pinned = db.groupDao().all().single()
        assertThat(pinned.id).isEqualTo(g.id)
        assertThat(pinned.bestItemId).isEqualTo(s.b)
        assertThat(pinned.bestPinned).isTrue()

        // 연사 간격을 0.1초로 줄이면 A·B(0.3초)는 연사가 아니라 '비슷한 사진'(dHash)으로 묶인다. 설정 변경만으로 다시 계산된다.
        settings.setBurstGapMs(100)
        withTimeout(20_000) {
            while (db.groupDao().all().singleOrNull()?.kind != GroupKind.SIMILAR) delay(50)
        }
        val similar = db.groupDao().all().single()
        assertThat(similar.id).isEqualTo(g.id)
        assertThat(similar.bestItemId).isEqualTo(s.b) // 고정 유지

        // 새 사진이 들어와 그룹에 합류해도 같은 그룹 id
        settings.setBurstGapMs(1_000)
        val late = insert("IMG_0009.JPG", writeJpeg("IMG_0009.JPG", sceneGray()), capturedAt = t0 + 900)
        controller.kick()
        idle()
        val grown = db.groupDao().all().single()
        assertThat(grown.id).isEqualTo(g.id)
        assertThat(grown.kind).isEqualTo(GroupKind.BURST)
        assertThat(grown.count).isEqualTo(3)
        assertThat(db.mediaDao().get(late)!!.groupId).isEqualTo(g.id)

        // 휴지통으로 옮겨 1장만 남으면 그룹이 사라진다
        db.mediaDao().setReviewState(listOf(s.b, late), ReviewState.TRASHED)
        controller.regroup()
        idle()
        assertThat(db.groupDao().all()).isEmpty()
        assertThat(db.mediaDao().get(s.a)!!.groupId).isNull()
    }

    // ---- 그룹 재계산: 바뀐 행만 쓰기, 계산 중 고정한 베스트 유지 ----

    /** media_item.groupId를 SET한 행을 기록하는 트리거(쓰기 범위 확인용) */
    private fun installGroupIdWriteLog() {
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TABLE IF NOT EXISTS test_groupid_log(itemId INTEGER NOT NULL)")
        sql.execSQL(
            "CREATE TRIGGER IF NOT EXISTS test_log_groupid AFTER UPDATE OF groupId ON media_item " +
                "BEGIN INSERT INTO test_groupid_log VALUES (NEW.id); END",
        )
        sql.execSQL("DELETE FROM test_groupid_log")
    }

    private fun groupIdWrites(): Set<Long> =
        db.openHelper.readableDatabase.query("SELECT DISTINCT itemId FROM test_groupid_log").use { c ->
            buildSet { while (c.moveToNext()) add(c.getLong(0)) }
        }

    @Test
    fun regroup_writesOnlyRowsWhoseGroupChanged() = runBlocking {
        val s = seed()
        controller.kick()
        idle()
        val g = db.groupDao().all().single()

        installGroupIdWriteLog()
        // 새 사진이 그룹에 합류: 그 사진만 groupId가 바뀐다. 이미 그룹에 있던 A·B는 다시 쓰지 않는다
        // (전부 지우고 다시 쓰면 그룹에 든 모든 행이 바뀌어 쓰기 잠금이 길고 모든 목록이 다시 조회된다)
        val late = insert("IMG_0009.JPG", writeJpeg("IMG_0009.JPG", sceneGray()), capturedAt = t0 + 900)
        controller.kick()
        idle()

        assertThat(db.mediaDao().get(late)!!.groupId).isEqualTo(g.id)
        assertThat(db.groupDao().all().single().count).isEqualTo(3)
        assertThat(groupIdWrites()).containsNoneOf(s.a, s.b, s.c)

        // 아무것도 바뀌지 않으면 쓰지 않는다
        db.openHelper.writableDatabase.execSQL("DELETE FROM test_groupid_log")
        controller.regroup()
        idle()
        assertThat(groupIdWrites()).isEmpty()
    }

    @Test
    fun regroup_keepsBestPinnedWhileItWasComputing() = runBlocking {
        val s = seed()
        controller.kick()
        idle()
        val g = db.groupDao().all().single()
        assertThat(g.bestItemId).isEqualTo(s.a)

        // 새 사진 합류로 그룹 계산(트랜잭션 밖)이 끝난 직후, 쓰기 전에 사용자가 흐린 B를 베스트로 고정
        controller.beforeRegroupWrite = {
            controller.beforeRegroupWrite = null
            assertThat(db.groupDao().pinBest(g.id, s.b)).isEqualTo(1)
        }
        val late = insert("IMG_0009.JPG", writeJpeg("IMG_0009.JPG", sceneGray()), capturedAt = t0 + 900)
        controller.kick()
        idle()

        assertThat(controller.beforeRegroupWrite).isNull() // 위 시나리오가 실제로 일어났다
        val after = db.groupDao().all().single()
        assertThat(after.id).isEqualTo(g.id)
        assertThat(after.count).isEqualTo(3)
        assertThat(after.bestItemId).isEqualTo(s.b) // 고정이 계산 결과에 덮이지 않는다
        assertThat(after.bestPinned).isTrue()
        assertThat(db.mediaDao().get(late)!!.groupId).isEqualTo(g.id)
    }

    @Test
    fun pinBest_ignoresItemOutsideTheGroup() = runBlocking {
        val s = seed()
        controller.kick()
        idle()
        val g = db.groupDao().all().single()
        // C는 그룹 멤버가 아니다(다시 묶여 빠진 경우와 같음)
        assertThat(db.groupDao().pinBest(g.id, s.c)).isEqualTo(0)
        val after = db.groupDao().all().single()
        assertThat(after.bestItemId).isEqualTo(s.a)
        assertThat(after.bestPinned).isFalse()
    }

    // ---- 진행 상태 ----

    @Test
    fun progress_keepsCountingAcrossShortPauses() = runBlocking {
        insert("IMG_0101.JPG", writeJpeg("IMG_0101.JPG", sceneGray()), capturedAt = t0)
        controller.kick()
        idle()
        assertThat(controller.status.value).isEqualTo(AnalysisStatus(running = false, done = 1, total = 1))

        // 가져오는 중 큰 영상을 복사하느라 잠깐 쉬었다가 다음 사진: 0부터 다시 세지 않는다
        insert("IMG_0102.JPG", writeJpeg("IMG_0102.JPG", sceneGray()), capturedAt = t0 + 60_000)
        controller.kick()
        idle()
        assertThat(controller.status.value).isEqualTo(AnalysisStatus(running = false, done = 2, total = 2))
    }

    @Test
    fun progress_restartsAfterLongIdle() = runBlocking {
        val fresh = AnalysisControllerImpl(context, scope, db, settings, lingerMs = 0, progressResetIdleMs = 0)
        insert("IMG_0101.JPG", writeJpeg("IMG_0101.JPG", sceneGray()), capturedAt = t0)
        fresh.kick()
        withTimeout(60_000) { fresh.awaitIdle(); fresh.status.first { !it.running } }
        insert("IMG_0102.JPG", writeJpeg("IMG_0102.JPG", sceneGray()), capturedAt = t0 + 60_000)
        fresh.kick()
        withTimeout(60_000) { fresh.awaitIdle(); fresh.status.first { !it.running } }
        assertThat(fresh.status.value).isEqualTo(AnalysisStatus(running = false, done = 1, total = 1))
    }

    // ---- 멈춘 파일 ----

    /** 이름에 HANG이 들어간 영상은 네이티브 호출이 멈춘 것처럼 스레드를 붙잡는다(취소로 풀리지 않음) */
    private class HangingVideoAnalyzer(private val release: CountDownLatch) : VideoAnalyzer() {
        override suspend fun analyze(path: String, currentCapturedAt: Long): VideoAnalysis {
            if ("HANG" in path) release.await()
            return super.analyze(path, currentCapturedAt)
        }
    }

    @Test
    fun hungItem_timesOutAndWorkerContinues() = runBlocking {
        val release = CountDownLatch(1)
        try {
            val fresh = AnalysisControllerImpl(
                context, scope, db, settings,
                videoAnalyzer = HangingVideoAnalyzer(release),
                lingerMs = 0,
                itemTimeoutMs = 500,
            )
            val hung = insert("MVI_HANG.MP4", File(dir, "MVI_HANG.MP4").apply { writeBytes(ByteArray(64)) }, capturedAt = t0, type = MediaType.VIDEO)
            val fv = File(dir, "MVI_0010.MP4").apply { writeBytes(ByteArray(64)) }
            ShadowMediaMetadataRetriever.addMetadata(fv.path, MediaMetadataRetriever.METADATA_KEY_DURATION, "4000")
            val ok = insert("MVI_0010.MP4", fv, capturedAt = t0 + 10_000, type = MediaType.VIDEO)

            fresh.kick()
            withTimeout(30_000) { fresh.awaitIdle(); fresh.status.first { !it.running } }

            val h = db.mediaDao().get(hung)!!
            assertThat(h.analysisFailed).isTrue()
            assertThat(h.durationMs).isNull()
            assertThat(db.mediaDao().get(ok)!!.durationMs).isEqualTo(4_000)
            assertThat(fresh.status.value.done).isEqualTo(2)
        } finally {
            release.countDown()
        }
    }
}
