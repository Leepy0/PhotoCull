package com.leepy0.photocull.trash

/** 휴지통으로 보낸 묶음. 실행 취소에 쓴다. */
data class TrashBatch(
    /** 휴지통으로 옮긴 대표 파일 id */
    val trashedIds: List<Long>,
    /** 같은 동작에서 '남기기'로 바뀐 id (실행 취소 시 PENDING으로 되돌림) */
    val keptIds: List<Long> = emptyList(),
)

data class TrashResult(
    val batch: TrashBatch,
    /** 실제로 휴지통으로 옮긴 단위 수(= [TrashBatch.trashedIds] 수). 실행 취소로 되돌릴 수 있다. */
    val movedCount: Int,
    /** 옮기지 못한 단위 수. 파일과 기록은 그대로다([importingCount] 포함). */
    val failedCount: Int,
    val freedBytes: Long,
    /** 폰에서 이미 지워져 있어 목록에서만 뺀 단위 수. 휴지통에 없고 실행 취소 대상도 아니다. */
    val goneCount: Int = 0,
    /** [failedCount] 중 아직 가져오는 중이라 지우지 않은 단위 수("아직 가져오는 중인 파일은 지울 수 없습니다") */
    val importingCount: Int = 0,
)

/** 휴지통에서 복원한 결과 */
data class RestoreReport(
    /** 원래 폴더로 돌려놓은 단위 수 */
    val restored: Int,
    /** 휴지통 파일이 이미 없어(다른 앱이 지움 등) 목록에서만 뺀 단위 수 */
    val gone: Int = 0,
    /** 복원하지 못한 단위 수(파일·기록은 휴지통 상태 그대로) */
    val failed: Int = 0,
)

/** 보관 기간을 [retentionDays]로 바꾸면 바로 영구 삭제될 것 */
data class PurgePreview(
    /** 촬영 단위 수(JPG+CR3 = 1개) */
    val count: Int,
    /** 지워질 휴지통 파일 전체 크기 */
    val bytes: Long,
)

/**
 * 삭제 = DCIM/.PhotoCullTrash/로 이동(같은 저장소라 즉시). JPG를 지우면 짝인 CR3도 함께 이동한다.
 * 모든 id는 대표 파일 id이며 같은 촬영 단위(unitKey) 파일을 함께 처리한다.
 */
interface TrashManager {
    /** [ids]를 휴지통으로, [keepIds]는 '남기기'로 표시 */
    suspend fun moveToTrash(ids: Collection<Long>, keepIds: Collection<Long> = emptyList()): TrashResult

    /** 방금 한 삭제 되돌리기: 원래 위치로 복원하고 검토 대기 상태로 */
    suspend fun undo(batch: TrashBatch)

    /** 휴지통에서 복원(남기기로 표시). 복원한 단위 수 */
    suspend fun restore(ids: Collection<Long>): Int

    /** 휴지통에서 복원하고 결과를 나눠 알려준다(복원 / 이미 지워짐 / 실패) */
    suspend fun restoreDetailed(ids: Collection<Long>): RestoreReport = RestoreReport(restored = restore(ids))

    /** 영구 삭제. 화면은 사용자가 확인한 목록의 id만 넘긴다(휴지통 비우기 포함). */
    suspend fun deleteForever(ids: Collection<Long>): Int

    /** 지금 휴지통에 있는 것을 모두 영구 삭제. 확인 없이 부르지 않는다(화면은 [deleteForever]를 쓴다). */
    suspend fun emptyTrash(): Int

    /** 보관 기간이 지난 항목 영구 삭제 */
    suspend fun purgeExpired(): Int

    /** 보관 기간을 [retentionDays]로 바꾸면 [purgeExpired]가 지울 것(같은 기준으로 센다). 알 수 없으면 null */
    suspend fun previewPurge(retentionDays: Int): PurgePreview? = null

    /**
     * 파일과 기록 맞추기(앱 시작 시, [purgeExpired]보다 먼저). 파일을 옮긴 뒤 기록하기 전에 앱이 끝났거나
     * 기록에 실패해 어긋난 항목을 고친다. 고친 행 수. 모르는 파일은 지우지 않는다.
     */
    suspend fun reconcile(): Int = 0
}
