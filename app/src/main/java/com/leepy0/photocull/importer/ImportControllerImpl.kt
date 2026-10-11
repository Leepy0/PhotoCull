package com.leepy0.photocull.importer

import android.content.Context
import android.util.Log
import com.leepy0.photocull.analysis.AnalysisController
import com.leepy0.photocull.data.db.AppDatabase
import com.leepy0.photocull.data.settings.SettingsRepository
import com.leepy0.photocull.storage.CardMonitor
import com.leepy0.photocull.storage.CardSource
import com.leepy0.photocull.storage.CardState
import com.leepy0.photocull.storage.StoragePaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** 서비스가 [ImportControllerImpl.runImport]로 받는 결과 */
sealed interface RunOutcome {
    /** 이미 다른 가져오기가 진행 중 */
    data object AlreadyRunning : RunOutcome

    /** 시작 전에 취소됨(복사한 파일 없음) */
    data object CancelledBeforeStart : RunOutcome

    /** 시작하지 못함(카드 없음, 공간 부족 등) */
    data class NotStarted(val error: ImportError) : RunOutcome

    data class Done(val outcome: EngineOutcome) : RunOutcome
}

/**
 * 가져오기 상태 보관(앱 단위 싱글턴). 화면은 [state]를 보고, 실제 복사는 [ImportService]가 [runImport]로 돌린다.
 * 상태: Idle → Scanning → Ready(요약) | Failed → (start) Running → Finished | Failed(CardRemoved 등)
 */
class ImportControllerImpl(
    private val context: Context,
    private val scope: CoroutineScope,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val cardMonitor: CardMonitor,
    private val analysis: AnalysisController,
    private val env: ImportEnvironment = AndroidImportEnvironment(context, cardMonitor),
) : ImportController {

    private val ledger: ImportLedger = RoomImportLedger(db)
    private val engine = ImportEngine(
        ledger = ledger,
        io = env.destinationFiles,
        mediaScanner = env.mediaScanner,
        onMediaVerified = { analysis.kick() },
        freeSpace = { env.freeBytes() },
        removalGraceMs = env.removalGraceMs,
    )

    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    override val state: StateFlow<ImportState> = _state.asStateFlow()

    /** 디스크를 바꾸는 작업(가져오기, 남은 .part 정리)은 한 번에 하나 */
    private val diskMutex = Mutex()
    private val importing = AtomicBoolean(false)
    @Volatile private var scanJob: Job? = null

    @Volatile private var activeCancel: CancelSignal? = null
    @Volatile private var cancelRequested = false

    /** 가져오는 중(서비스 실행 중)인지. 카드 연결 알림을 띄울지 판단할 때 쓴다. */
    val isImporting: Boolean get() = importing.get() || _state.value is ImportState.Running

    /** 앱 시작 시 정리 작업(테스트에서 기다릴 수 있게 노출) */
    internal val startupRecovery: Job = scope.launch(Dispatchers.IO) {
        runCatching { diskMutex.withLock { recover() } }.onFailure { logWarn("startup recovery failed", it) }
    }

    init {
        scope.launch {
            cardMonitor.state.collect { card ->
                // 요약을 보여주는 중에 카드가 빠지거나 다른 카드로 바뀌면 처음으로
                val s = _state.value
                if (s is ImportState.Ready && !(card is CardState.Ready && card.volume.id == s.summary.volume.id)) {
                    _state.compareAndSet(s, ImportState.Idle)
                }
            }
        }
    }

    override fun scan() {
        val s = _state.value
        if (importing.get() || s is ImportState.Running) return
        if (s is ImportState.Scanning && scanJob?.isActive == true) return
        scanJob?.cancel()
        _state.value = ImportState.Scanning
        scanJob = scope.launch(Dispatchers.IO) {
            val next = try {
                when (val p = prepare()) {
                    is Prepared.Ok -> ImportState.Ready(p.plan.summary)
                    is Prepared.Err -> ImportState.Failed(p.error)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logWarn("scan failed", e)
                ImportState.Failed(ImportError.Unexpected(e.javaClass.simpleName ?: "scan"))
            }
            // 그사이 서비스가 가져오기를 시작했으면 덮어쓰지 않는다
            if (isActive && !importing.get()) _state.compareAndSet(ImportState.Scanning, next)
        }
    }

    override fun start(moveMode: Boolean) {
        val ready = _state.value as? ImportState.Ready ?: return
        if (importing.get() || !ready.summary.enoughSpace) return
        cancelRequested = false
        _state.value = ImportState.Running(initialProgress(ready.summary))
        try {
            env.startService(moveMode)
        } catch (e: Exception) {
            logWarn("service start failed", e)
            _state.value = ImportState.Failed(ImportError.Unexpected(e.javaClass.simpleName ?: "service"))
        }
    }

    override fun cancel() {
        val signal = activeCancel
        if (signal != null) {
            signal.cancel()
            return
        }
        when (_state.value) {
            is ImportState.Scanning -> if (importing.get()) {
                cancelRequested = true
            } else {
                scanJob?.cancel()
                _state.value = ImportState.Idle
            }
            // 서비스가 아직 시작 전: 시작하자마자 멈추게 표시
            is ImportState.Running -> cancelRequested = true
            else -> Unit
        }
    }

    /** 서비스가 포그라운드로 시작하지 못함(시스템 제한 등): 시작 대기 중이던 화면을 오류로 */
    internal fun onServiceStartFailed() = abandonPendingStart()

    /**
     * 서비스가 멈춤(또는 이 시작 요청을 맡지 않기로 함). 그때 가져오기가 돌고 있지 않은데 화면이 Running이면
     * 아무도 끝내 주지 않으므로 오류로 바꾼다(취소·초기화·스캔이 영영 막히지 않게).
     */
    internal fun onServiceStopped() = abandonPendingStart()

    private fun abandonPendingStart() {
        if (!importing.get() && _state.value is ImportState.Running) {
            _state.value = ImportState.Failed(ImportError.Unexpected("service"))
        }
    }

    override fun reset() {
        if (importing.get() || _state.value is ImportState.Running) return
        scanJob?.cancel()
        _state.value = ImportState.Idle
    }

    /**
     * 서비스에서 호출: 카드를 다시 확인하고(앱 프로세스가 막 시작됐을 수 있다) 가져오기를 끝까지 돌린다.
     * @param moveModeOverride null이면 설정값(카드 알림의 [가져오기])
     */
    internal suspend fun runImport(
        moveModeOverride: Boolean?,
        onProgress: (ImportProgress) -> Unit = {},
    ): RunOutcome {
        if (!importing.compareAndSet(false, true)) return RunOutcome.AlreadyRunning
        try {
            scanJob?.cancel()
            if (_state.value !is ImportState.Running) _state.value = ImportState.Scanning
            return withContext(Dispatchers.IO) { diskMutex.withLock { runLocked(moveModeOverride, onProgress) } }
        } catch (e: CancellationException) {
            val s = _state.value
            if (s is ImportState.Scanning || s is ImportState.Running) _state.value = ImportState.Idle
            throw e
        } catch (e: Exception) {
            logWarn("import failed", e)
            val error = ImportError.Unexpected(e.javaClass.simpleName ?: "import")
            _state.value = ImportState.Failed(error)
            return RunOutcome.NotStarted(error)
        } finally {
            activeCancel = null
            cancelRequested = false
            importing.set(false)
        }
    }

    private suspend fun runLocked(moveModeOverride: Boolean?, onProgress: (ImportProgress) -> Unit): RunOutcome {
        val s = settings.current()
        runCatching { recover() }.onFailure { logWarn("pre-import recovery failed", it) }

        val prepared = prepare()
        if (prepared is Prepared.Err) {
            _state.value = ImportState.Failed(prepared.error)
            return RunOutcome.NotStarted(prepared.error)
        }
        prepared as Prepared.Ok
        val summary = prepared.plan.summary
        if (!summary.enoughSpace) {
            val error = ImportError.InsufficientSpace(summary.requiredBytes, summary.freeBytes)
            _state.value = ImportState.Failed(error)
            return RunOutcome.NotStarted(error)
        }
        if (cancelRequested) {
            _state.value = ImportState.Ready(summary)
            return RunOutcome.CancelledBeforeStart
        }

        val cancel = CancelSignal()
        activeCancel = cancel
        if (cancelRequested) cancel.cancel()
        _state.value = ImportState.Running(initialProgress(summary))
        val request = ImportRequest(
            plan = prepared.plan,
            moveMode = moveModeOverride ?: s.moveMode,
            destFolderName = s.destFolderName,
            monthlySubfolders = s.monthlySubfolders,
            storageRoot = env.storageRoot,
        )
        val outcome = engine.run(request, prepared.source, cancel) { p ->
            _state.value = ImportState.Running(p)
            onProgress(p)
        }
        _state.value = outcome.error?.let { ImportState.Failed(it) } ?: ImportState.Finished(outcome.result)
        return RunOutcome.Done(outcome)
    }

    private sealed interface Prepared {
        data class Ok(val plan: ScanPlan, val source: CardSource) : Prepared
        data class Err(val error: ImportError) : Prepared
    }

    private suspend fun prepare(): Prepared {
        val volume = when (val card = env.currentCard()) {
            CardState.NoPermission -> return Prepared.Err(ImportError.NoPermission)
            CardState.NotConnected, is CardState.NotCameraCard -> return Prepared.Err(ImportError.NoCard)
            // 폴더 승인 필요: 화면은 CardMonitor.state(NeedsAccess)를 보고 [승인]을 안내한다
            is CardState.NeedsAccess -> return Prepared.Err(ImportError.NoPermission)
            is CardState.Ready -> card.volume
        }
        val s = settings.current()
        val source = env.openSource(volume)
        val files = try {
            source.listTargetFiles()
        } catch (e: SecurityException) {
            return Prepared.Err(ImportError.NoPermission)
        } catch (e: IOException) {
            return Prepared.Err(if (source.isPresent()) ImportError.Unexpected("scan") else ImportError.NoCard)
        }
        val keys = ledger.importedKeys(volume.id)
        val plan = CardScanner.plan(volume, files, keys, env.freeBytes(), StoragePaths.destDisplayPath(s.destFolderName))
        return Prepared.Ok(plan, source)
    }

    private suspend fun recover() {
        val s = settings.current()
        val report = ImportRecovery(ledger, env.destinationFiles).run(StoragePaths.destRoot(s.destFolderName, env.storageRoot))
        // 되살린 사진은 다음 가져오기·앱 시작까지 기다리지 않고 바로 분석
        if (report.recoveredItems > 0 || report.restoredPartFiles > 0) runCatching { analysis.kick() }
    }

    private fun initialProgress(summary: CardScanSummary) = ImportProgress(
        sessionId = 0,
        totalFiles = summary.newFileCount,
        doneFiles = 0,
        totalBytes = summary.newBytes,
        doneBytes = 0,
        currentFileName = null,
        bytesPerSecond = 0,
        etaSeconds = null,
        failedCount = 0,
        startedAt = System.currentTimeMillis(),
    )

    private fun logWarn(message: String, e: Throwable) {
        runCatching { Log.w(TAG, message, e) }
    }

    private companion object {
        const val TAG = "ImportController"
    }
}
