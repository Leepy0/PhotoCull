package com.leepy0.photocull.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 검토 화면용 행: 대표 파일 + 같은 촬영 단위(JPG+CR3) 전체 용량과 RAW 존재 여부 */
data class ReviewRow(
    @Embedded val item: MediaItemEntity,
    val unitBytes: Long,
    val rawCount: Int,
)

/** 이미 가져온 파일인지 대조하는 키 (스캔 단계에서 카드 파일과 비교) */
data class ImportedKey(
    val srcRelPath: String,
    val srcSize: Long,
    val srcLastModified: Long,
    val importStatus: ImportStatus,
    val dstPath: String?,
)

/** 그룹 계산 입력 */
data class GroupingRow(
    val id: Long,
    val capturedAt: Long,
    val srcRelPath: String,
    val sharpness: Double?,
    val dhash: Long?,
    val groupId: Long?,
    val reviewState: ReviewState,
)

/** 검토 대기 그룹 수(2장 이상 + 대기 1장 이상)와 그 그룹들의 사진 수 */
data class GroupCounts(val groupCount: Int, val photoCount: Int)

/** 대기 영상 수와 그중 짧은 영상(실수 촬영 후보) 수 */
data class VideoCounts(val total: Int, val shortCount: Int)

/** 그룹에 속한 행 하나: 사진 id → 그룹 id */
data class GroupRef(val id: Long, val groupId: Long)

/**
 * 그룹 재계산 결과 중 바뀐 부분만. 전부 지우고 다시 쓰면 그룹에 든 모든 행이 바뀌어
 * 쓰기 잠금이 길어지고 media_item을 보는 모든 Flow가 다시 조회되기 때문이다.
 */
data class GroupingDiff(
    /** 새 그룹(id = 0)과 그 멤버 */
    val inserts: List<Pair<MediaGroupEntity, List<Long>>> = emptyList(),
    /** 내용(베스트·종류·수·기간)이 바뀐 기존 그룹 */
    val updates: List<MediaGroupEntity> = emptyList(),
    /** 없어진 기존 그룹 */
    val deleteGroupIds: List<Long> = emptyList(),
    /** 기존 그룹 id → 그 그룹으로 옮겨야 하는(지금 groupId가 다른) 사진 */
    val moves: Map<Long, List<Long>> = emptyMap(),
    /** 더 이상 어느 그룹에도 속하지 않는 사진 */
    val clearItemIds: List<Long> = emptyList(),
) {
    val isEmpty: Boolean
        get() = inserts.isEmpty() && updates.isEmpty() && deleteGroupIds.isEmpty() && moves.isEmpty() && clearItemIds.isEmpty()
}

private const val UNIT_COLUMNS = """
    (SELECT COALESCE(SUM(u.srcSize), 0) FROM media_item u
        WHERE u.unitKey = m.unitKey AND u.reviewState != 'DELETED') AS unitBytes,
    (SELECT COUNT(*) FROM media_item r
        WHERE r.unitKey = m.unitKey AND r.type = 'RAW' AND r.reviewState != 'DELETED') AS rawCount
"""

/** 폰에 안전하게 저장된 상태 */
private const val STORED = "('VERIFIED','SOURCE_DELETED','DUPLICATE','SOURCE_DELETE_FAILED')"

/**
 * 검토 화면에 보여도 되는 행: 복사·검증이 끝나 폰에 실제 파일이 있는 것.
 * 가져오기는 복사 전에 PENDING 행을 먼저 넣으므로(.part 복사 중), 이 조건이 없으면 깨진 썸네일이 보이고 개수에 잡힌다.
 */
private const val STORED_M = "m.importStatus IN $STORED AND m.dstPath IS NOT NULL"

/** 흐림 탭 조건(목록과 개수가 같은 조건을 쓰게 한 곳에 둔다). 그룹에 속한 사진은 연사 탭에서 상대 비교로 다룬다. */
private const val BLUR_WHERE = """m.isPrimary = 1 AND m.type = 'PHOTO' AND m.reviewState = 'PENDING'
    AND m.groupId IS NULL AND m.sharpness IS NOT NULL AND m.sharpness < :threshold AND $STORED_M"""

/** 영상 탭 조건 */
private const val VIDEO_WHERE = "m.isPrimary = 1 AND m.type = 'VIDEO' AND m.reviewState = 'PENDING' AND $STORED_M"

/** 그룹 멤버로 보일 수 있는 행 */
private const val GROUP_MEMBER_WHERE =
    "m.isPrimary = 1 AND m.groupId IS NOT NULL AND m.reviewState IN ('PENDING','KEPT') AND $STORED_M"

/** 대기(PENDING) 대표 사진 p의 조건. 모두 판단한 그룹은 검토 목록에서 읽지도 않는다. */
private const val PENDING_P = "p.isPrimary = 1 AND p.reviewState = 'PENDING' AND p.importStatus IN $STORED AND p.dstPath IS NOT NULL"

@Dao
interface SessionDao {
    @Insert suspend fun insert(session: ImportSessionEntity): Long
    @Update suspend fun update(session: ImportSessionEntity)
    @Query("SELECT * FROM import_session WHERE id = :id") suspend fun get(id: Long): ImportSessionEntity?

    @Query("SELECT * FROM import_session WHERE finishedAt IS NOT NULL ORDER BY finishedAt DESC LIMIT 1")
    fun observeLatestFinished(): Flow<ImportSessionEntity?>

    /** 앱이 강제 종료돼 RUNNING으로 남은 세션 정리 (앱 시작 시) */
    @Query("UPDATE import_session SET state = 'INTERRUPTED', finishedAt = :now WHERE state = 'RUNNING'")
    suspend fun markRunningInterrupted(now: Long)
}

@Dao
interface MediaDao {
    @Insert suspend fun insert(item: MediaItemEntity): Long
    @Update suspend fun update(item: MediaItemEntity)
    @Update suspend fun updateAll(items: List<MediaItemEntity>)

    @Query("SELECT * FROM media_item WHERE id = :id") suspend fun get(id: Long): MediaItemEntity?
    @Query("SELECT * FROM media_item WHERE id IN (:ids)") suspend fun getAll(ids: List<Long>): List<MediaItemEntity>
    @Query("SELECT * FROM media_item WHERE unitKey = :unitKey") suspend fun unitMembers(unitKey: String): List<MediaItemEntity>
    @Query("SELECT * FROM media_item WHERE unitKey IN (:unitKeys)") suspend fun unitMembersOf(unitKeys: List<String>): List<MediaItemEntity>

    @Query("SELECT srcRelPath, srcSize, srcLastModified, importStatus, dstPath FROM media_item WHERE volumeId = :volumeId AND importStatus IN $STORED")
    suspend fun importedKeys(volumeId: String): List<ImportedKey>

    @Query("SELECT * FROM media_item WHERE sessionId = :sessionId ORDER BY id")
    suspend fun sessionItems(sessionId: Long): List<MediaItemEntity>

    // ---- 분석 ----

    @Query(
        """SELECT * FROM media_item WHERE isPrimary = 1 AND type = 'PHOTO' AND sharpness IS NULL
           AND analysisFailed = 0 AND dstPath IS NOT NULL AND importStatus IN $STORED
           AND reviewState IN ('PENDING','KEPT') ORDER BY capturedAt"""
    )
    suspend fun pendingPhotoAnalysis(): List<MediaItemEntity>

    @Query(
        """SELECT * FROM media_item WHERE isPrimary = 1 AND type = 'VIDEO' AND durationMs IS NULL AND analysisFailed = 0
           AND dstPath IS NOT NULL AND importStatus IN $STORED AND reviewState IN ('PENDING','KEPT')"""
    )
    suspend fun pendingVideoAnalysis(): List<MediaItemEntity>

    @Query(
        """SELECT COUNT(*) FROM media_item WHERE isPrimary = 1 AND analysisFailed = 0 AND dstPath IS NOT NULL
           AND importStatus IN $STORED AND reviewState IN ('PENDING','KEPT')
           AND ((type = 'PHOTO' AND sharpness IS NULL) OR (type = 'VIDEO' AND durationMs IS NULL))"""
    )
    fun observePendingAnalysisCount(): Flow<Int>

    @Query(
        """SELECT id, capturedAt, srcRelPath, sharpness, dhash, groupId, reviewState FROM media_item
           WHERE isPrimary = 1 AND type = 'PHOTO' AND reviewState IN ('PENDING','KEPT')
           AND importStatus IN $STORED AND dstPath IS NOT NULL
           AND (sharpness IS NOT NULL OR dhash IS NOT NULL) ORDER BY capturedAt, srcRelPath"""
    )
    suspend fun groupingCandidates(): List<GroupingRow>

    @Query("UPDATE media_item SET groupId = NULL WHERE groupId IS NOT NULL")
    suspend fun clearAllGroupIds()

    @Query("UPDATE media_item SET groupId = :groupId WHERE id IN (:ids)")
    suspend fun setGroupId(ids: List<Long>, groupId: Long)

    // ---- 검토 ----

    /** 그룹에 속하지 않은 흐림 후보. 그룹 안의 사진은 연사 탭에서 상대 비교로 다룬다. */
    @Query("SELECT m.*, $UNIT_COLUMNS FROM media_item m WHERE $BLUR_WHERE ORDER BY m.sharpness ASC")
    fun observeBlurCandidates(threshold: Double): Flow<List<ReviewRow>>

    @Query("SELECT COUNT(*) FROM media_item m WHERE $BLUR_WHERE")
    fun observeBlurCount(threshold: Double): Flow<Int>

    /**
     * 아직 판단 전인 사진이 남은 그룹의 대표 사진(남긴 것 포함). 모두 판단한 그룹은 읽지 않는다(라이브러리가 커져도 가볍게).
     * 2장 미만 그룹 제외 등 최종 판단은 저장소에서 한다.
     */
    @Query(
        """SELECT m.*, $UNIT_COLUMNS FROM media_item m
           WHERE $GROUP_MEMBER_WHERE AND EXISTS (SELECT 1 FROM media_item p WHERE p.groupId = m.groupId AND $PENDING_P)
           ORDER BY m.capturedAt, m.srcRelPath"""
    )
    fun observeGroupedItems(): Flow<List<ReviewRow>>

    /** 검토 대기 그룹 수와 그 사진 수. [ReviewRepository.buildGroups]와 같은 규칙(2장 이상 + 대기 1장 이상). */
    @Query(
        """SELECT COUNT(*) AS groupCount, COALESCE(SUM(n), 0) AS photoCount FROM (
               SELECT m.groupId, COUNT(*) AS n,
                   SUM(CASE WHEN m.reviewState = 'PENDING' THEN 1 ELSE 0 END) AS pendingCount
               FROM media_item m
               WHERE $GROUP_MEMBER_WHERE AND EXISTS (SELECT 1 FROM media_group g WHERE g.id = m.groupId)
               GROUP BY m.groupId
               HAVING n >= 2 AND pendingCount >= 1
           )"""
    )
    fun observeGroupCounts(): Flow<GroupCounts>

    @Query("SELECT m.*, $UNIT_COLUMNS FROM media_item m WHERE $VIDEO_WHERE ORDER BY m.srcSize DESC")
    fun observePendingVideos(): Flow<List<ReviewRow>>

    /** 대기 영상 수와 그중 [shortMs] 이하(실수 촬영 후보) 수 */
    @Query(
        """SELECT COUNT(*) AS total,
               COALESCE(SUM(CASE WHEN m.durationMs IS NOT NULL AND m.durationMs <= :shortMs THEN 1 ELSE 0 END), 0) AS shortCount
           FROM media_item m WHERE $VIDEO_WHERE"""
    )
    fun observeVideoCounts(shortMs: Long): Flow<VideoCounts>

    @Query(
        """SELECT m.*, $UNIT_COLUMNS FROM media_item m
           WHERE m.isPrimary = 1 AND m.reviewState = 'TRASHED' ORDER BY m.trashedAt DESC"""
    )
    fun observeTrashed(): Flow<List<ReviewRow>>

    @Query("SELECT m.*, $UNIT_COLUMNS FROM media_item m WHERE m.id IN (:ids)")
    suspend fun reviewRows(ids: List<Long>): List<ReviewRow>

    @Query("SELECT * FROM media_item WHERE reviewState = 'TRASHED' AND trashedAt IS NOT NULL AND trashedAt < :before")
    suspend fun trashedBefore(before: Long): List<MediaItemEntity>

    @Query("SELECT * FROM media_item WHERE reviewState = 'TRASHED'")
    suspend fun allTrashed(): List<MediaItemEntity>

    @Query("UPDATE media_item SET reviewState = :state, updatedAt = :now WHERE id IN (:ids)")
    suspend fun setReviewState(ids: List<Long>, state: ReviewState, now: Long = System.currentTimeMillis())

    /** 같은 촬영 단위(JPG+CR3)까지 함께 상태 변경 */
    @Query(
        """UPDATE media_item SET reviewState = :state, updatedAt = :now
           WHERE unitKey IN (SELECT unitKey FROM media_item WHERE id IN (:ids))
           AND reviewState IN ('PENDING','KEPT')"""
    )
    suspend fun setUnitReviewState(ids: List<Long>, state: ReviewState, now: Long = System.currentTimeMillis())
}

@Dao
interface GroupDao {
    @Insert suspend fun insert(group: MediaGroupEntity): Long
    @Query("SELECT * FROM media_group") suspend fun all(): List<MediaGroupEntity>
    @Query("SELECT * FROM media_group") fun observeAll(): Flow<List<MediaGroupEntity>>

    /** 아직 판단 전인 사진이 남은 그룹만(검토 목록용) */
    @Query("SELECT g.* FROM media_group g WHERE EXISTS (SELECT 1 FROM media_item p WHERE p.groupId = g.id AND $PENDING_P)")
    fun observeOpen(): Flow<List<MediaGroupEntity>>

    @Query("SELECT * FROM media_group WHERE id = :id") fun observe(id: Long): Flow<MediaGroupEntity?>
    @Query("DELETE FROM media_group") suspend fun deleteAll()

    /**
     * 베스트 고정. 그 사이 다시 묶여 [itemId]가 더 이상 이 그룹 멤버가 아니면 아무것도 바꾸지 않는다
     * (다른 그룹 사진이 베스트로 고정되지 않게). @return 바뀐 그룹 수(0 또는 1)
     */
    @Query(
        """UPDATE media_group SET bestItemId = :itemId, bestPinned = 1 WHERE id = :groupId
           AND EXISTS (SELECT 1 FROM media_item WHERE id = :itemId AND groupId = :groupId)"""
    )
    suspend fun pinBest(groupId: Long, itemId: Long): Int
}

/** 그룹 재계산 결과 쓰기(분석 모듈이 사용) */
@Dao
abstract class GroupingDao {
    @Insert protected abstract suspend fun insertGroup(group: MediaGroupEntity): Long
    @Update protected abstract suspend fun updateGroups(groups: List<MediaGroupEntity>)
    @Query("DELETE FROM media_group") protected abstract suspend fun deleteAllGroups()
    @Query("DELETE FROM media_group WHERE id IN (:ids)") protected abstract suspend fun deleteGroups(ids: List<Long>)
    @Query("UPDATE media_item SET groupId = NULL WHERE groupId IS NOT NULL") protected abstract suspend fun clearGroupIds()
    @Query("UPDATE media_item SET groupId = NULL WHERE id IN (:ids)") protected abstract suspend fun clearGroupIdOf(ids: List<Long>)
    @Query("UPDATE media_item SET groupId = :groupId WHERE id IN (:ids)") protected abstract suspend fun setGroupId(ids: List<Long>, groupId: Long)

    /** 지금 그룹에 속한 모든 행(휴지통에 간 사진 포함) */
    @Query("SELECT id, groupId FROM media_item WHERE groupId IS NOT NULL")
    abstract suspend fun groupRefs(): List<GroupRef>

    /** 기존 그룹을 모두 지우고 [groups]로 교체한다. 각 그룹의 첫 원소는 그룹 정보, 둘째는 멤버 id 목록. */
    @Transaction
    open suspend fun replaceAll(groups: List<Pair<MediaGroupEntity, List<Long>>>) {
        clearGroupIds()
        deleteAllGroups()
        for ((group, memberIds) in groups) {
            val id = insertGroup(group)
            memberIds.chunked(CHUNK).forEach { setGroupId(it, id) }
        }
    }

    /** [diff]의 바뀐 부분만 쓴다. 이미 트랜잭션 안에서 부르면 그 트랜잭션에 합류한다. */
    @Transaction
    open suspend fun applyDiff(diff: GroupingDiff) {
        diff.deleteGroupIds.chunked(CHUNK).forEach { deleteGroups(it) }
        if (diff.updates.isNotEmpty()) updateGroups(diff.updates)
        diff.clearItemIds.chunked(CHUNK).forEach { clearGroupIdOf(it) }
        for ((groupId, itemIds) in diff.moves) itemIds.chunked(CHUNK).forEach { setGroupId(it, groupId) }
        for ((group, memberIds) in diff.inserts) {
            val id = insertGroup(group.copy(id = 0))
            memberIds.chunked(CHUNK).forEach { setGroupId(it, id) }
        }
    }

    private companion object {
        /** SQLite 변수 개수 제한 안쪽 */
        const val CHUNK = 500
    }
}
