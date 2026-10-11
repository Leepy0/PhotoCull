package com.leepy0.photocull.importer

import android.content.Context
import com.leepy0.photocull.analysis.AnalysisController
import com.leepy0.photocull.data.db.AppDatabase
import com.leepy0.photocull.data.settings.SettingsRepository
import com.leepy0.photocull.storage.CardMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// TODO(storage-import): 스캔·포그라운드 서비스 연동 실제 구현으로 교체
class ImportControllerImpl(
    private val context: Context,
    private val scope: CoroutineScope,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val cardMonitor: CardMonitor,
    private val analysis: AnalysisController,
) : ImportController {
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    override val state: StateFlow<ImportState> = _state
    override fun scan() {}
    override fun start(moveMode: Boolean) {}
    override fun cancel() {}
    override fun reset() { _state.value = ImportState.Idle }
}
