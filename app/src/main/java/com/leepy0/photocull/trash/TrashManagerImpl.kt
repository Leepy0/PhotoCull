package com.leepy0.photocull.trash

import android.content.Context
import com.leepy0.photocull.data.db.AppDatabase
import com.leepy0.photocull.data.settings.SettingsRepository

// TODO(trash-settings): 파일 이동 기반 실제 구현으로 교체
class TrashManagerImpl(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) : TrashManager {
    override suspend fun moveToTrash(ids: Collection<Long>, keepIds: Collection<Long>): TrashResult =
        TrashResult(TrashBatch(ids.toList(), keepIds.toList()), 0, 0, 0)
    override suspend fun undo(batch: TrashBatch) {}
    override suspend fun restore(ids: Collection<Long>): Int = 0
    override suspend fun deleteForever(ids: Collection<Long>): Int = 0
    override suspend fun emptyTrash(): Int = 0
    override suspend fun purgeExpired(): Int = 0
}
