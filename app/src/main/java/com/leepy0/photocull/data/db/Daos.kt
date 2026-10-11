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

private const val UNIT_COLUMNS = """
    (SELECT COALESCE(SUM(u.srcSize), 0) FROM media_item u
        WHERE u.unitKey = m.unitKey AND u.reviewState != 'DELETED') AS unitBytes,
    (SELECT COUNT(*) FROM media_item r
        WHERE r.unitKey = m.unitKey AND r.type = 'RAW' AND r.reviewState != 'DELETED') AS rawCount
"""

/** 폰에 안전하게 저장된 상태 */
private const val STORED = "('VERIFIED','SOURCE_DELETED','DUPLICATE','SOURCE_DELETE_FAILED')"

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
        """SELECT * FROM media_item WHERE type = 'VIDEO' AND durationMs IS NULL AND analysisFailed = 0
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
           AND (sharpness IS NOT NULL OR dhash IS NOT NULL) ORDER BY capturedAt, srcRelPath"""
    )
    suspend fun groupingCandidates(): List<GroupingRow>

    @Query("UPDATE media_item SET groupId = NULL WHERE groupId IS NOT NULL")
    suspend fun clearAllGroupIds()

    @Query("UPDATE media_item SET groupId = :groupId WHERE id IN (:ids)")
    suspend fun setGroupId(ids: List<Long>, groupId: Long)

    // ---- 검토 ----

    /** 그룹에 속하지 않은 흐림 후보. 그룹 안의 사진은 연사 탭에서 상대 비교로 다룬다. */
    @Query(
        """SELECT m.*, $UNIT_COLUMNS FROM media_item m
           WHERE m.isPrimary = 1 AND m.type = 'PHOTO' AND m.reviewState = 'PENDING'
           AND m.groupId IS NULL AND m.sharpness IS NOT NULL AND m.sharpness < :threshold
           ORDER BY m.sharpness ASC"""
    )
    fun observeBlurCandidates(threshold: Double): Flow<List<ReviewRow>>

    /** 그룹에 속한 대표 사진 전부(남긴 것 포함). 표시 여부는 저장소에서 판단한다. */
    @Query(
        """SELECT m.*, $UNIT_COLUMNS FROM media_item m
           WHERE m.isPrimary = 1 AND m.groupId IS NOT NULL AND m.reviewState IN ('PENDING','KEPT')
           ORDER BY m.capturedAt, m.srcRelPath"""
    )
    fun observeGroupedItems(): Flow<List<ReviewRow>>

    @Query(
        """SELECT m.*, $UNIT_COLUMNS FROM media_item m
           WHERE m.isPrimary = 1 AND m.type = 'VIDEO' AND m.reviewState = 'PENDING'
           ORDER BY m.srcSize DESC"""
    )
    fun observePendingVideos(): Flow<List<ReviewRow>>

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
    @Query("SELECT * FROM media_group WHERE id = :id") fun observe(id: Long): Flow<MediaGroupEntity?>
    @Query("DELETE FROM media_group") suspend fun deleteAll()
    @Query("UPDATE media_group SET bestItemId = :itemId, bestPinned = 1 WHERE id = :groupId")
    suspend fun pinBest(groupId: Long, itemId: Long)
}

/** 그룹 재계산을 한 트랜잭션으로 처리 (분석 모듈이 사용) */
@Dao
abstract class GroupingDao {
    @Insert protected abstract suspend fun insertGroup(group: MediaGroupEntity): Long
    @Query("DELETE FROM media_group") protected abstract suspend fun deleteAllGroups()
    @Query("UPDATE media_item SET groupId = NULL WHERE groupId IS NOT NULL") protected abstract suspend fun clearGroupIds()
    @Query("UPDATE media_item SET groupId = :groupId WHERE id IN (:ids)") protected abstract suspend fun setGroupId(ids: List<Long>, groupId: Long)

    /** 기존 그룹을 모두 지우고 [groups]로 교체한다. 각 그룹의 첫 원소는 그룹 정보, 둘째는 멤버 id 목록. */
    @Transaction
    open suspend fun replaceAll(groups: List<Pair<MediaGroupEntity, List<Long>>>) {
        clearGroupIds()
        deleteAllGroups()
        for ((group, memberIds) in groups) {
            val id = insertGroup(group)
            memberIds.chunked(500).forEach { setGroupId(it, id) }
        }
    }
}
