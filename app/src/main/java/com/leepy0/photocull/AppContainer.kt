package com.leepy0.photocull

import android.content.Context
import com.leepy0.photocull.analysis.AnalysisController
import com.leepy0.photocull.analysis.AnalysisControllerImpl
import com.leepy0.photocull.data.db.AppDatabase
import com.leepy0.photocull.data.review.ReviewRepository
import com.leepy0.photocull.data.settings.SettingsRepository
import com.leepy0.photocull.importer.ImportController
import com.leepy0.photocull.importer.ImportControllerImpl
import com.leepy0.photocull.storage.CardMonitor
import com.leepy0.photocull.storage.CardMonitorImpl
import com.leepy0.photocull.trash.TrashManager
import com.leepy0.photocull.trash.TrashManagerImpl
import com.leepy0.photocull.update.UpdateController
import com.leepy0.photocull.update.UpdateControllerImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 앱 전역 객체를 만드는 곳(수동 DI). 테스트에서는 필요한 것만 바꿔 끼운다. */
interface AppContainer {
    val appScope: CoroutineScope
    val db: AppDatabase
    val settings: SettingsRepository
    val cardMonitor: CardMonitor
    val importController: ImportController
    val analysis: AnalysisController
    val trash: TrashManager
    val review: ReviewRepository
    val update: UpdateController
}

class DefaultAppContainer(context: Context) : AppContainer {
    private val app = context.applicationContext
    override val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val db: AppDatabase by lazy { AppDatabase.create(app) }
    override val settings: SettingsRepository by lazy { SettingsRepository(app) }
    override val cardMonitor: CardMonitor by lazy { CardMonitorImpl(app) }
    override val analysis: AnalysisController by lazy { AnalysisControllerImpl(app, appScope, db, settings) }
    override val importController: ImportController by lazy {
        ImportControllerImpl(app, appScope, db, settings, cardMonitor, analysis)
    }
    override val trash: TrashManager by lazy { TrashManagerImpl(app, db, settings) }
    override val review: ReviewRepository by lazy { ReviewRepository(db, settings) }
    override val update: UpdateController by lazy { UpdateControllerImpl(app, appScope, settings) }
}
