package com.leepy0.photocull.ui.review

import com.leepy0.photocull.AppContainer
import com.leepy0.photocull.analysis.AnalysisStatus
import com.leepy0.photocull.data.review.GroupCard
import com.leepy0.photocull.data.review.MediaCard
import com.leepy0.photocull.data.settings.AppSettings
import com.leepy0.photocull.data.settings.BlurSensitivity
import com.leepy0.photocull.trash.TrashBatch
import com.leepy0.photocull.trash.TrashResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 검토 화면이 쓰는 데이터와 동작. 화면 로직을 가짜 구현으로 시험할 수 있게 한 겹 둔다.
 * 실제 구현은 [ContainerReviewDataSource](ReviewRepository + TrashManager + SettingsRepository).
 */
interface ReviewDataSource {
    fun blurCandidates(): Flow<List<MediaCard>>
    fun groups(): Flow<List<GroupCard>>
    fun group(groupId: Long): Flow<GroupCard?>
    fun videos(): Flow<List<MediaCard>>
    fun settings(): Flow<AppSettings>
    val analysis: StateFlow<AnalysisStatus>

    /**
     * 파일 이동·상태 변경처럼 중간에 끊기면 안 되는 작업을 돌리는 범위(앱 프로세스 범위).
     * 화면을 떠나도 작업은 끝까지 진행된다.
     */
    val workScope: CoroutineScope

    suspend fun setBlurSensitivity(value: BlurSensitivity)
    suspend fun keep(ids: Collection<Long>)
    suspend fun unkeep(ids: Collection<Long>)
    suspend fun moveToTrash(ids: Collection<Long>, keepIds: Collection<Long>): TrashResult
    suspend fun undoTrash(batch: TrashBatch)
    suspend fun pinBest(groupId: Long, itemId: Long)

    /** 연사/유사 그룹을 다시 묶는다(백그라운드에서, 기다리지 않음) */
    fun regroup()
}

/** 스낵바 [실행 취소]: 휴지통 이동·유지를 되돌린다. 일괄 정리였으면 그룹도 다시 묶는다. */
internal suspend fun ReviewDataSource.undo(message: ReviewMessage) {
    when (message) {
        is ReviewMessage.Trashed -> {
            undoTrash(message.batch)
            if (message.regroupOnUndo) regroup()
        }
        is ReviewMessage.Kept -> unkeep(message.ids)
        else -> Unit
    }
}

class ContainerReviewDataSource(private val container: AppContainer) : ReviewDataSource {
    override fun blurCandidates(): Flow<List<MediaCard>> = container.review.blurCandidates()
    override fun groups(): Flow<List<GroupCard>> = container.review.groups()
    override fun group(groupId: Long): Flow<GroupCard?> = container.review.group(groupId)
    override fun videos(): Flow<List<MediaCard>> = container.review.videos()
    override fun settings(): Flow<AppSettings> = container.settings.settings
    override val analysis: StateFlow<AnalysisStatus> get() = container.analysis.status
    override val workScope: CoroutineScope get() = container.appScope

    override suspend fun setBlurSensitivity(value: BlurSensitivity) = container.settings.setBlurSensitivity(value)
    override suspend fun keep(ids: Collection<Long>) = container.review.keep(ids)
    override suspend fun unkeep(ids: Collection<Long>) = container.review.unkeep(ids)
    override suspend fun moveToTrash(ids: Collection<Long>, keepIds: Collection<Long>): TrashResult =
        container.trash.moveToTrash(ids, keepIds)

    override suspend fun undoTrash(batch: TrashBatch) = container.trash.undo(batch)
    override suspend fun pinBest(groupId: Long, itemId: Long) = container.review.pinBest(groupId, itemId)
    override fun regroup() = container.analysis.regroup()
}
