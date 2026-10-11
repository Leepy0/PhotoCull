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
    val movedCount: Int,
    val failedCount: Int,
    val freedBytes: Long,
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

    /** 휴지통에서 복원(남기기로 표시) */
    suspend fun restore(ids: Collection<Long>): Int

    /** 영구 삭제 */
    suspend fun deleteForever(ids: Collection<Long>): Int

    suspend fun emptyTrash(): Int

    /** 보관 기간이 지난 항목 영구 삭제 */
    suspend fun purgeExpired(): Int
}
