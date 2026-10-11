package com.leepy0.photocull.analysis

import android.content.Context
import com.leepy0.photocull.data.db.AppDatabase
import com.leepy0.photocull.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// TODO(analysis): 선명도·dHash·그룹 계산 실제 구현으로 교체
class AnalysisControllerImpl(
    private val context: Context,
    private val scope: CoroutineScope,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) : AnalysisController {
    private val _status = MutableStateFlow(AnalysisStatus())
    override val status: StateFlow<AnalysisStatus> = _status
    override fun kick() {}
    override fun regroup() {}
    override suspend fun awaitIdle() {}
}
