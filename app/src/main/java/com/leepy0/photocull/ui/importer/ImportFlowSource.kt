package com.leepy0.photocull.ui.importer

import android.content.Intent
import android.net.Uri
import com.leepy0.photocull.AppContainer
import com.leepy0.photocull.analysis.AnalysisStatus
import com.leepy0.photocull.data.review.ReviewCounts
import com.leepy0.photocull.data.settings.AppSettings
import com.leepy0.photocull.importer.ImportState
import com.leepy0.photocull.storage.CardState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 홈·가져오기 화면이 쓰는 카드·가져오기 상태와 동작. 화면 로직을 가짜 구현으로 시험할 수 있게 한 겹 둔다.
 * 실제 구현은 [ContainerImportFlowSource](CardMonitor + ImportController + ReviewRepository + 설정).
 */
interface ImportFlowSource {
    val cardState: StateFlow<CardState>
    val importState: StateFlow<ImportState>
    val analysis: StateFlow<AnalysisStatus>

    fun reviewCounts(): Flow<ReviewCounts>
    fun settings(): Flow<AppSettings>

    fun scan()
    fun start(moveMode: Boolean)
    fun cancel()
    fun reset()

    fun refreshCard()

    /** [CardState.NeedsAccess]일 때 폴더 승인 화면 인텐트. 볼륨이 사라졌으면 null */
    fun createAccessIntent(volumeId: String): Intent?
    fun onAccessGranted(volumeId: String, treeUri: Uri)
}

open class ContainerImportFlowSource(private val container: AppContainer) : ImportFlowSource {
    override val cardState: StateFlow<CardState> get() = container.cardMonitor.state
    override val importState: StateFlow<ImportState> get() = container.importController.state
    override val analysis: StateFlow<AnalysisStatus> get() = container.analysis.status

    override fun reviewCounts(): Flow<ReviewCounts> = container.review.counts()
    override fun settings(): Flow<AppSettings> = container.settings.settings

    override fun scan() = container.importController.scan()
    override fun start(moveMode: Boolean) = container.importController.start(moveMode)
    override fun cancel() = container.importController.cancel()
    override fun reset() = container.importController.reset()

    override fun refreshCard() = container.cardMonitor.refresh()
    override fun createAccessIntent(volumeId: String): Intent? = container.cardMonitor.createAccessIntent(volumeId)
    override fun onAccessGranted(volumeId: String, treeUri: Uri) = container.cardMonitor.onAccessGranted(volumeId, treeUri)
}
