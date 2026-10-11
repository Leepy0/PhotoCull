package com.leepy0.photocull.ui.trash

import com.leepy0.photocull.AppContainer
import com.leepy0.photocull.data.review.MediaCard
import com.leepy0.photocull.trash.RestoreReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 휴지통 화면이 쓰는 데이터와 동작. 화면 로직을 가짜 구현으로 시험할 수 있게 한 겹 둔다.
 * 실제 구현은 [ContainerTrashDataSource](ReviewRepository.trashed + TrashManager + SettingsRepository).
 */
interface TrashDataSource {
    fun trashed(): Flow<List<MediaCard>>
    fun retentionDays(): Flow<Int>

    /** 파일 작업을 돌리는 범위(앱 프로세스 범위). 화면을 떠나도 작업은 끝까지 진행된다. */
    val workScope: CoroutineScope

    suspend fun restore(ids: Collection<Long>): RestoreReport

    /** 영구 삭제. 휴지통 비우기도 확인 대화상자에 보였던 목록의 id로 부른다 */
    suspend fun deleteForever(ids: Collection<Long>): Int

    /** 보관 기간이 지난 항목 정리(화면을 열 때). 앱 시작 때만 돌면 오래 켜 둔 앱에서 기간을 넘긴 항목이 남는다 */
    suspend fun purgeExpired(): Int = 0

    fun now(): Long = System.currentTimeMillis()
}

class ContainerTrashDataSource(private val container: AppContainer) : TrashDataSource {
    override fun trashed(): Flow<List<MediaCard>> = container.review.trashed()
    override fun retentionDays(): Flow<Int> = container.settings.settings.map { it.trashRetentionDays }.distinctUntilChanged()
    override val workScope: CoroutineScope get() = container.appScope
    override suspend fun restore(ids: Collection<Long>): RestoreReport = container.trash.restoreDetailed(ids)
    override suspend fun deleteForever(ids: Collection<Long>): Int = container.trash.deleteForever(ids)
    override suspend fun purgeExpired(): Int = container.trash.purgeExpired()
}
