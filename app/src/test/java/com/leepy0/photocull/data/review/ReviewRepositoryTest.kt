package com.leepy0.photocull.data.review

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.data.db.AppDatabase
import com.leepy0.photocull.data.db.GroupKind
import com.leepy0.photocull.data.db.ImportSessionEntity
import com.leepy0.photocull.data.db.ImportStatus
import com.leepy0.photocull.data.db.MediaGroupEntity
import com.leepy0.photocull.data.db.MediaItemEntity
import com.leepy0.photocull.data.db.MediaType
import com.leepy0.photocull.data.db.ReviewState
import com.leepy0.photocull.data.settings.BlurSensitivity
import com.leepy0.photocull.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 검토 질의·목록·건수를 실제 Room(메모리 DB)으로 확인한다. 보통 민감도 = 흐림 임계값 150, 짧은 영상 3초. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ReviewRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var repo: ReviewRepository
    private var sessionId = 0L
    private var nextT = 1_000_000L

    @Before
    fun setUp() = runBlocking<Unit> {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = AppDatabase.inMemory(context)
        settings = SettingsRepository(context)
        // DataStore는 프로세스 단일 인스턴스라 이전 테스트의 값이 남을 수 있다.
        settings.setBlurSensitivity(BlurSensitivity.NORMAL)
        settings.setShortVideoMs(3_000)
        repo = ReviewRepository(db, settings)
        sessionId = db.sessionDao().insert(ImportSessionEntity(volumeId = "vol", volumeLabel = "EOS", startedAt = 0, moveMode = true))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun item(
        name: String,
        type: MediaType = MediaType.PHOTO,
        sharpness: Double? = null,
        durationMs: Long? = null,
        state: ReviewState = ReviewState.PENDING,
        status: ImportStatus = ImportStatus.SOURCE_DELETED,
        primary: Boolean = true,
        groupId: Long? = null,
        size: Long = 10,
    ): Long {
        nextT += 300
        return db.mediaDao().insert(
            MediaItemEntity(
                sessionId = sessionId,
                volumeId = "vol",
                srcRelPath = "DCIM/100CANON/$name",
                srcLocation = "/card/DCIM/100CANON/$name",
                srcSize = size,
                srcLastModified = nextT,
                unitKey = "$sessionId:DCIM/100CANON/${name.substringBeforeLast('.')}",
                isPrimary = primary,
                type = type,
                mimeType = if (type == MediaType.VIDEO) "video/mp4" else "image/jpeg",
                dstPath = "/storage/emulated/0/DCIM/R10/$name",
                fileName = name,
                importStatus = status,
                capturedAt = nextT,
                sharpness = sharpness,
                durationMs = durationMs,
                groupId = groupId,
                reviewState = state,
            ),
        )
    }

    /** 그룹 행 + 멤버. members: 이름 → (선명도, 상태) */
    private suspend fun group(
        id: Long,
        vararg members: Triple<String, Double, ReviewState>,
        bestIndex: Int = 0,
        pinned: Boolean = false,
    ): List<Long> {
        val ids = members.map { (name, sharp, state) -> item(name, sharpness = sharp, state = state, groupId = id) }
        db.groupDao().insert(
            MediaGroupEntity(
                id = id, kind = GroupKind.BURST, bestItemId = ids[bestIndex], bestPinned = pinned,
                count = ids.size, startAt = 0, endAt = 0,
            ),
        )
        return ids
    }

    private fun m(name: String, sharp: Double, state: ReviewState = ReviewState.PENDING) = Triple(name, sharp, state)

    @Test
    fun inFlightRowsAreNeitherListedNorCounted() = runBlocking<Unit> {
        // 가져오기는 복사 전에 PENDING 행(.part로 복사 중)을 먼저 넣는다
        val blurry = item("IMG_0001.JPG", sharpness = 50.0)
        item("IMG_0002.JPG", sharpness = 50.0, status = ImportStatus.PENDING)
        item("IMG_0003.JPG", sharpness = 50.0, status = ImportStatus.FAILED)
        val video = item("MVI_0004.MP4", type = MediaType.VIDEO, durationMs = 10_000)
        item("MVI_0005.MP4", type = MediaType.VIDEO, status = ImportStatus.PENDING)
        item("MVI_0006.MP4", type = MediaType.VIDEO, durationMs = 2_000, status = ImportStatus.COPIED)

        assertThat(repo.blurCandidates().first().map { it.id }).containsExactly(blurry)
        assertThat(repo.videos().first().map { it.id }).containsExactly(video)
        assertThat(repo.counts().first()).isEqualTo(ReviewCounts(blur = 1, videos = 1, shortVideos = 0))
    }

    @Test
    fun pendingVideoAnalysis_onlyPrimaryStoredVideos() = runBlocking<Unit> {
        val ok = item("MVI_0001.MP4", type = MediaType.VIDEO)
        item("MVI_0002.MP4", type = MediaType.VIDEO, primary = false)
        item("MVI_0003.MP4", type = MediaType.VIDEO, status = ImportStatus.PENDING)
        assertThat(db.mediaDao().pendingVideoAnalysis().map { it.id }).containsExactly(ok)
    }

    @Test
    fun groupsSkipFullyReviewedGroups_andCountsMatchLists() = runBlocking<Unit> {
        item("IMG_0001.JPG", sharpness = 40.0)
        item("IMG_0002.JPG", sharpness = 400.0)
        item("MVI_0003.MP4", type = MediaType.VIDEO, durationMs = 1_500)
        item("MVI_0004.MP4", type = MediaType.VIDEO, durationMs = 3_000)
        item("MVI_0005.MP4", type = MediaType.VIDEO, durationMs = 60_000)
        val open = group(10, m("A1", 300.0), m("A2", 100.0))
        val done = group(20, m("B1", 300.0, ReviewState.KEPT), m("B2", 100.0, ReviewState.KEPT))
        group(30, m("C1", 300.0), m("C2", 100.0, ReviewState.TRASHED)) // 남은 1장 → 그룹 아님
        val mixed = group(40, m("D1", 300.0, ReviewState.KEPT), m("D2", 280.0), m("D3", 290.0, ReviewState.KEPT))

        val groups = repo.groups().first()
        assertThat(groups.map { it.id }).containsExactly(10L, 40L).inOrder()
        assertThat(groups.first { it.id == 40L }.members.map { it.id }).containsExactlyElementsIn(mixed).inOrder()
        assertThat(groups.first { it.id == 10L }.members.map { it.id }).containsExactlyElementsIn(open).inOrder()
        // 모두 판단한 그룹의 사진은 읽지도 않는다
        assertThat(db.mediaDao().observeGroupedItems().first().map { it.item.id }).containsNoneIn(done)

        val blur = repo.blurCandidates().first()
        val videos = repo.videos().first()
        val counts = repo.counts().first()
        assertThat(counts).isEqualTo(
            ReviewCounts(
                blur = blur.size,
                groups = groups.size,
                groupPhotos = groups.sumOf { it.members.size },
                videos = videos.size,
                shortVideos = videos.count { ReviewRepository.isShortVideo(it, 3_000) },
            ),
        )
        assertThat(counts).isEqualTo(ReviewCounts(blur = 1, groups = 2, groupPhotos = 5, videos = 3, shortVideos = 2))

        // 설정(짧은 영상 기준)을 바꾸면 건수도 따라간다
        settings.setShortVideoMs(1_000)
        assertThat(repo.counts().first().shortVideos).isEqualTo(0)
    }

    @Test
    fun countsIsOneSharedInstance() {
        // 화면이 매 재구성마다 불러도 같은 Flow라 구독이 새로 시작되지 않는다
        assertThat(repo.counts()).isSameInstanceAs(repo.counts())
    }

    @Test
    fun relativeBlurUsesGroupMaxScore_notPinnedBest() = runBlocking<Unit> {
        // 사용자가 흐린 사진(100)을 베스트로 고정한 그룹: 기준은 여전히 최고 점수 400
        val ids = group(10, m("A1", 400.0), m("A2", 100.0), m("A3", 380.0), bestIndex = 1, pinned = true)
        val g = repo.group(10).first()!!
        assertThat(g.bestId).isEqualTo(ids[1])
        assertThat(g.bestPinned).isTrue()
        // 400의 60% = 240 미만만 흐림. 380은 흐림이 아니다(베스트 기준이면 아무것도 안 잡혔다)
        assertThat(g.relativeBlurIds).containsExactly(ids[1])
        // 최고 점수 400 ≥ 임계값 150: '전체가 흐림'이 아니다(베스트 기준이면 100 < 150으로 잘못 켜졌다)
        assertThat(g.allBlurry).isFalse()
    }

    @Test
    fun allBlurryWhenEvenTheSharpestIsBelowThreshold() = runBlocking<Unit> {
        group(10, m("A1", 120.0), m("A2", 60.0))
        val g = repo.group(10).first()!!
        assertThat(g.allBlurry).isTrue()
        assertThat(g.relativeBlurIds).containsExactly(g.members[1].id)
    }

    @Test
    fun pinBest_onlyForMembersOfThatGroup() = runBlocking<Unit> {
        val a = group(10, m("A1", 300.0), m("A2", 100.0))
        val b = group(20, m("B1", 300.0), m("B2", 100.0))
        repo.pinBest(10, b[1]) // 다른 그룹 사진
        assertThat(repo.group(10).first()!!.bestId).isEqualTo(a[0])
        assertThat(repo.group(10).first()!!.bestPinned).isFalse()
        repo.pinBest(10, a[1])
        assertThat(repo.group(10).first()!!.bestId).isEqualTo(a[1])
        assertThat(repo.group(10).first()!!.bestPinned).isTrue()
    }
}
