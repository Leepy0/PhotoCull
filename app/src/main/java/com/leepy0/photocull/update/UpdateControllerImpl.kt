package com.leepy0.photocull.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.leepy0.photocull.BuildConfig
import com.leepy0.photocull.PhotoCullApp
import com.leepy0.photocull.R
import com.leepy0.photocull.data.settings.SettingsRepository
import com.leepy0.photocull.importer.ImportController
import com.leepy0.photocull.importer.ImportControllerImpl
import com.leepy0.photocull.importer.ImportState
import com.leepy0.photocull.util.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** 업데이트 컨트롤러가 쓰는 설정 값. 기본은 [SettingsRepository], 테스트에서는 메모리 구현. */
interface UpdatePrefsStore {
    val autoUpdateCheck: Flow<Boolean>
    suspend fun autoUpdateCheckEnabled(): Boolean
    suspend fun lastCheckAt(): Long
    suspend fun setLastCheckAt(v: Long)
    suspend fun lastRunVersionCode(): Int
    suspend fun setLastRunVersionCode(v: Int)
}

class SettingsUpdatePrefs(private val settings: SettingsRepository) : UpdatePrefsStore {
    override val autoUpdateCheck: Flow<Boolean> = settings.settings.map { it.autoUpdateCheck }
    override suspend fun autoUpdateCheckEnabled(): Boolean = settings.current().autoUpdateCheck
    override suspend fun lastCheckAt(): Long = settings.current().lastUpdateCheckAt
    override suspend fun setLastCheckAt(v: Long) = settings.setLastUpdateCheckAt(v)
    override suspend fun lastRunVersionCode(): Int = settings.current().lastRunVersionCode
    override suspend fun setLastRunVersionCode(v: Int) = settings.setLastRunVersionCode(v)
}

/** 받는 중 취소(계약 [UpdateController] 밖의 보조 기능). 업데이트 시트가 지원 여부를 확인해 버튼을 보인다. */
interface CancellableUpdateDownload {
    fun cancelDownload()
}

/**
 * 시스템 설치 확인 화면 띄우기. 앱이 보이지 않을 때는 시스템이 액티비티 시작을 조용히 막으므로
 * 알림으로도 열 수 있게 한다. 테스트에서는 가짜로 바꿔 끼운다.
 */
interface InstallPrompter {
    /** 확인 화면을 띄운다. 실패하면 false */
    fun launch(confirm: Intent): Boolean

    /** "탭하여 설치를 마치세요" 알림(탭하면 확인 화면) */
    fun notify(confirm: Intent, versionName: String?)

    fun cancelNotification()
}

class SystemInstallPrompter(private val context: Context) : InstallPrompter {
    override fun launch(confirm: Intent): Boolean =
        runCatching { context.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    override fun notify(confirm: Intent, versionName: String?) {
        UpdateNotifications.notifyConfirmInstall(context, confirm, versionName)
    }

    override fun cancelNotification() = UpdateNotifications.cancel(context)
}

/** 업데이트 직후 첫 실행 판정 */
object JustUpdated {
    /** 0 < last < current면 "v0.1.0 → v0.2.0", 아니면 null(첫 설치, 같은 버전, 다운그레이드) */
    fun message(lastRunVersionCode: Int, currentVersionCode: Int, currentVersionName: String): String? =
        if (lastRunVersionCode in 1 until currentVersionCode) {
            "v${Versions.nameOf(lastRunVersionCode)} → v$currentVersionName"
        } else {
            null
        }
}

/**
 * GitHub Releases 기반 자동 업데이트(DESIGN.md §6).
 * 상태 전이: Idle → Checking → UpToDate | Available → Downloading → ReadyToInstall → Installing → (앱 재시작)
 * 어느 단계든 실패하면 Failed(무엇이·왜, 해결 방법). 백그라운드(자동) 확인 실패는 조용히 넘긴다.
 *
 * 앱이 보이는지에 따라 달라지는 것(화면이 [onAppResumed]/[onAppPaused]로 알려 준다):
 * - 설치 확인 화면: 앱이 보이지 않으면 시스템이 막으므로 돌아왔을 때 띄우고, 알림으로도 열 수 있게 한다.
 * - 받기: 앱이 백그라운드에서 얼려지면 연결이 끊겨 실패한다. 그렇게 실패했으면 앱으로 돌아올 때 한 번 다시 받는다.
 * - 권한 없음·가져오는 중 때문에 설치하지 못한 경우: 이유가 풀리면 저절로 [설치] 단계로 돌아간다.
 */
class UpdateControllerImpl(
    private val context: Context,
    private val scope: CoroutineScope,
    settings: SettingsRepository,
    private val prefs: UpdatePrefsStore = SettingsUpdatePrefs(settings),
    http: UpdateHttp = UrlConnectionHttp(userAgent = "PhotoCull/${BuildConfig.VERSION_NAME}"),
    private val scheduler: UpdateScheduler = WorkManagerUpdateScheduler(context),
    private val isImportRunning: () -> Boolean = { defaultImportRunning(context) },
    private val startInstall: (File, UpdateInfo) -> Boolean = ApkInstaller(context)::install,
    override val currentVersionName: String = BuildConfig.VERSION_NAME,
    override val currentVersionCode: Int = BuildConfig.VERSION_CODE,
    override val channel: String = BuildConfig.BUILD_CHANNEL,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    repo: String = BuildConfig.UPDATE_REPO,
    downloadDir: File = UpdateFiles.downloadDir(context),
    private val prompter: InstallPrompter = SystemInstallPrompter(context),
) : UpdateController, CancellableUpdateDownload {

    private val checker = UpdateChecker(http, repo, BuildConfig.UPDATE_MANIFEST, BuildConfig.UPDATE_ASSET)
    private val downloader = ApkDownloader(http, downloadDir)

    private val lock = Any()
    private val checkMutex = Mutex()
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var installWatchdog: Job? = null
    /** 백그라운드 확인 중에 사용자가 확인을 요청했으면 실패를 화면에 보여준다. */
    @Volatile private var userWaiting = false
    /** 자동 확인이 도는 동안 들어온 사용자 확인(자동 확인이 아무것도 안 하고 끝나면 이어서 한다) */
    @Volatile private var userCheckQueued = false
    /** 받아서 SHA-256까지 확인한 APK의 versionCode(메인 스레드에서 디스크를 보지 않고 상태를 되돌리기 위함) */
    @Volatile private var verifiedVersionCode: Int? = null

    /** 앱(화면)이 보이는지. 화면이 [onAppResumed]/[onAppPaused]로 알려 준다. */
    @Volatile private var foreground = true
    /** 받는 동안 앱이 한 번이라도 가려졌는지(프로세스가 얼면 연결이 끊긴다) */
    @Volatile private var backgroundedDuringDownload = false
    /** 이 버전은 이미 자동으로 한 번 다시 받았다 */
    @Volatile private var autoRetriedVersion: Int? = null
    /** 앱으로 돌아오면 다시 받을 버전(백그라운드에서 끊긴 받기) */
    @Volatile private var retryOnResumeVersion: Int? = null

    /** 앱이 가려져 있어 띄우지 못한 설치 확인 화면(돌아오면 띄운다) */
    @Volatile private var pendingConfirm: Intent? = null
    @Volatile private var pendingConfirmInfo: UpdateInfo? = null

    /** 이유가 풀리면 저절로 넘어갈 실패(권한 없음·가져오는 중). 그 실패 상태 객체와 함께 기억한다. */
    private enum class Blocker { PERMISSION, IMPORTING }
    @Volatile private var blockedFailure: UpdateState.Failed? = null
    @Volatile private var blocker: Blocker? = null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state

    private val _justUpdated = MutableStateFlow<String?>(null)
    override val justUpdated: StateFlow<String?> = _justUpdated

    private val _justUpdatedNotes = MutableStateFlow<String?>(null)
    /** 업데이트 직후 보여줄 변경 내용(받을 때 저장해 둔 것). 없으면 null */
    val justUpdatedNotes: StateFlow<String?> = _justUpdatedNotes

    override val releasesPageUrl: String = "https://github.com/$repo/releases"

    init {
        scope.launch(io) { runCatching { downloader.cleanup(currentVersionCode) } }
        scope.launch(io) { runCatching { detectJustUpdated() } }
        scope.launch {
            prefs.autoUpdateCheck.distinctUntilChanged().collect { enabled ->
                runCatching { if (enabled) scheduler.schedule() else scheduler.cancel() }
            }
        }
    }

    // ---- 확인 ----

    /**
     * 확인. 받는 중·설치 중이 아니면 언제든(새 버전을 이미 알고 있어도 더 새 핫픽스가 있을 수 있다).
     * 자동 확인이 도는 중에 사용자가 누르면 기다렸다가, 자동 확인이 간격·설정 때문에 아무것도 하지 않았으면 이어서 확인한다.
     */
    override fun check(userInitiated: Boolean) {
        synchronized(lock) {
            if (checkJob?.isActive == true) {
                if (userInitiated) {
                    userWaiting = true
                    userCheckQueued = true
                }
                return
            }
            if (_state.value.isTransient()) return
            userWaiting = userInitiated
            userCheckQueued = false
            checkJob = scope.launch(io) { runCheck(userInitiated) }
        }
    }

    private suspend fun runCheck(userInitiated: Boolean) {
        val result = performCheck(userInitiated = userInitiated, respectInterval = !userInitiated)
        val rerun = synchronized(lock) {
            val queued = userCheckQueued
            userCheckQueued = false
            val again = queued && result == null && !userInitiated
            // 끝났음을 먼저 알린다: 이 뒤에 들어온 확인 요청은 새로 시작한다(삼켜지지 않게)
            if (!again) checkJob = null
            again
        }
        if (rerun) {
            userWaiting = true
            performCheck(userInitiated = true, respectInterval = false)
            synchronized(lock) { checkJob = null }
        }
    }

    /** WorkManager 주기 확인용. 6시간 간격은 무시한다(주기는 WorkManager가 정함). 확인하지 않았으면 null */
    suspend fun backgroundCheck(): CheckResult? = withContext(io) {
        performCheck(userInitiated = false, respectInterval = false)
    }

    private suspend fun performCheck(userInitiated: Boolean, respectInterval: Boolean): CheckResult? = checkMutex.withLock {
        if (!userInitiated) {
            if (!prefs.autoUpdateCheckEnabled()) return null
            if (respectInterval) {
                val last = prefs.lastCheckAt()
                val now = clock()
                // 시계가 뒤로 간 경우(last > now)는 지난 것으로 본다.
                if (last in 1..now && now - last < AUTO_CHECK_INTERVAL_MS) return null
            }
        }
        val previous = _state.value
        if (previous.isTransient()) return null
        // 이미 새 버전을 알고 있으면(받기/설치 대기) 그 화면을 그대로 두고 조용히 더 새 버전만 찾는다
        val known = previous.knownInfo()
        if (known == null) _state.value = UpdateState.Checking

        val result = try {
            checker.check(currentVersionCode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CheckResult.Failed(CheckFailure.NETWORK)
        }
        val now = clock()
        when (result) {
            is CheckResult.Available -> {
                runCatching { prefs.setLastCheckAt(now) }
                if (known == null || result.info.versionCode > known.versionCode) {
                    val ready = runCatching { downloader.verifiedApk(result.info) }.getOrNull()
                    if (ready != null) verifiedVersionCode = result.info.versionCode
                    setIfNotTransient(if (ready != null) UpdateState.ReadyToInstall(result.info) else UpdateState.Available(result.info))
                }
            }
            is CheckResult.UpToDate -> {
                runCatching { prefs.setLastCheckAt(now) }
                if (known == null) setIfNotTransient(UpdateState.UpToDate(now))
            }
            is CheckResult.Failed -> {
                if (known == null) {
                    val show = userInitiated || userWaiting
                    setIfNotTransient(if (show) failed(checkFailureText(result.reason), info = null) else UpdateState.Idle)
                }
            }
        }
        userWaiting = false
        result
    }

    // ---- 받기 ----

    override fun download() {
        synchronized(lock) {
            if (downloadJob?.isActive == true) return
            val info = when (val s = _state.value) {
                is UpdateState.Available -> s.info
                is UpdateState.Failed -> s.info
                else -> null
            } ?: return
            if (UpdateManifests.normalizeSha256(info.sha256) == null) {
                // 검증할 수 없는 파일은 받지 않는다. 다시 시도하면 새로 확인한다.
                _state.value = failed(FailureText(R.string.update_err_missing_sha, R.string.update_fix_later), info = null)
                return
            }
            retryOnResumeVersion = null
            backgroundedDuringDownload = !foreground
            _state.value = downloadingStart(info)
            downloadJob = scope.launch(io) {
                runCatching { UpdateFiles.writeNotes(context, info) }
                var result = downloadOnce(info)
                var retryLater = false
                if (result == DownloadResult.NetworkError && shouldAutoRetry(info)) {
                    if (foreground) {
                        // 백그라운드에서 끊겼고 지금은 다시 보인다: 바로 한 번 더(처음부터)
                        autoRetriedVersion = info.versionCode
                        backgroundedDuringDownload = false
                        updateIfDownloading(info) { downloadingStart(info) }
                        result = downloadOnce(info)
                    } else {
                        // 아직 가려져 있다: 앱으로 돌아오면 한 번 더
                        retryLater = true
                    }
                }
                if (result is DownloadResult.Success) verifiedVersionCode = info.versionCode
                val next = when (result) {
                    is DownloadResult.Success -> UpdateState.ReadyToInstall(info)
                    DownloadResult.ChecksumMismatch -> failed(FailureText(R.string.update_err_corrupt, R.string.update_fix_retry), info)
                    DownloadResult.MissingChecksum -> failed(FailureText(R.string.update_err_missing_sha, R.string.update_fix_later), null)
                    is DownloadResult.NotEnoughSpace -> UpdateState.Failed(
                        message = context.getString(R.string.update_err_space),
                        fix = context.getString(R.string.update_fix_space, Format.bytes(result.requiredBytes - result.availableBytes.coerceAtLeast(0))),
                        info = info,
                    )
                    // 404 등: 릴리스가 바뀌었을 수 있으니 다시 시도하면 새로 확인한다.
                    is DownloadResult.HttpError -> failed(FailureText(R.string.update_err_download_http, R.string.update_fix_later), null)
                    DownloadResult.NetworkError -> failed(FailureText(R.string.update_err_download_network, R.string.update_fix_network), info)
                }
                val thisJob = coroutineContext[Job]
                synchronized(lock) {
                    updateIfDownloading(info) { next }
                    if (retryLater) retryOnResumeVersion = info.versionCode
                    // 끝났음을 먼저 알려 바로 이어지는 다시 받기가 막히지 않게
                    if (downloadJob === thisJob) downloadJob = null
                }
                // 그사이 앱으로 돌아왔으면 바로
                if (retryLater && foreground) retryDownloadIfPending()
            }
        }
    }

    /** 백그라운드에서 끊긴 받기를 한 번 다시 한다(앱으로 돌아왔을 때) */
    private fun retryDownloadIfPending() {
        synchronized(lock) {
            val retry = retryOnResumeVersion ?: return
            retryOnResumeVersion = null
            val s = _state.value as? UpdateState.Failed
            if (s?.info?.versionCode == retry && downloadJob?.isActive != true) {
                autoRetriedVersion = retry
                dismissError()
                download()
            }
        }
    }

    private suspend fun downloadOnce(info: UpdateInfo): DownloadResult = try {
        downloader.download(info) { p -> updateIfDownloading(info) { UpdateState.Downloading(info, p) } }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DownloadResult.NetworkError
    }

    private fun shouldAutoRetry(info: UpdateInfo): Boolean =
        (backgroundedDuringDownload || !foreground) && autoRetriedVersion != info.versionCode

    private fun downloadingStart(info: UpdateInfo) = UpdateState.Downloading(info, if (info.sizeBytes != null) 0f else -1f)

    override fun cancelDownload() {
        synchronized(lock) {
            val s = _state.value as? UpdateState.Downloading ?: return
            downloadJob?.cancel()
            downloadJob = null
            retryOnResumeVersion = null
            _state.value = UpdateState.Available(s.info)
        }
    }

    private inline fun updateIfDownloading(info: UpdateInfo, crossinline next: () -> UpdateState) {
        _state.update { cur -> if (cur is UpdateState.Downloading && cur.info == info) next() else cur }
    }

    // ---- 설치 ----

    override fun install() {
        synchronized(lock) {
            val info = when (val s = _state.value) {
                is UpdateState.ReadyToInstall -> s.info
                is UpdateState.Failed -> s.info
                else -> null
            } ?: return
            if (!canInstallPackages()) {
                setBlocked(Blocker.PERMISSION, failed(FailureText(R.string.update_err_no_permission, R.string.update_fix_no_permission), info))
                return
            }
            if (isImportRunning()) {
                setBlocked(Blocker.IMPORTING, failed(FailureText(R.string.update_err_importing, R.string.update_fix_importing), info))
                return
            }
            _state.value = UpdateState.Installing(info)
            scope.launch(io) {
                val apk = runCatching { downloader.verifiedApk(info) }.getOrNull()
                if (apk == null) {
                    verifiedVersionCode = null
                    setIfInstalling(failed(FailureText(R.string.update_err_corrupt, R.string.update_fix_retry), info))
                    return@launch
                }
                val started = runCatching { startInstall(apk, info) }.getOrDefault(false)
                if (!started) {
                    setIfInstalling(failed(FailureText(R.string.update_err_install_start, R.string.update_fix_install_generic), info))
                } else {
                    armInstallWatchdog(info)
                }
            }
        }
    }

    /** 가져오는 중이라 지금은 설치할 수 없는지(시트가 [설치]를 끄고 안내한다) */
    fun isInstallBlockedByImport(): Boolean = runCatching { isImportRunning() }.getOrDefault(false)

    /**
     * 시스템이 설치 확인을 요구한다([InstallResultReceiver]). 앱이 보이면 바로 띄우고,
     * 보이지 않으면(시스템이 막음) 돌아올 때 띄우고 알림으로도 열 수 있게 한다.
     * 확인 화면을 띄울 수 없으면 false(실패로 알림).
     */
    fun onConfirmationRequired(confirm: Intent, appInForeground: Boolean): Boolean {
        val info = (_state.value as? UpdateState.Installing)?.info
        if (appInForeground) {
            if (prompter.launch(confirm)) {
                onInstallAwaitingUser()
                return true
            }
            onInstallFailed(FailureText(R.string.update_err_confirm_ui, R.string.update_fix_confirm_ui))
            return false
        }
        pendingConfirm = confirm
        pendingConfirmInfo = info
        runCatching { prompter.notify(confirm, info?.versionName) }
        onInstallAwaitingUser()
        return true
    }

    /** 시스템 설치 확인 화면이 떴다. 사용자가 고르는 동안 기다린다. */
    fun onInstallAwaitingUser() {
        val s = _state.value as? UpdateState.Installing ?: return
        armInstallWatchdog(s.info)
    }

    /** [InstallResultReceiver]가 실패를 알려 줄 때 */
    fun onInstallFailed(text: FailureText) {
        installWatchdog?.cancel()
        pendingConfirm = null
        pendingConfirmInfo = null
        val info = when (val s = _state.value) {
            is UpdateState.Installing -> s.info
            is UpdateState.ReadyToInstall -> s.info
            is UpdateState.Failed -> s.info
            else -> null
        }
        _state.value = failed(text, info)
    }

    /** 결과가 오지 않으면(시스템 이상, 확인 화면을 오래 둠) 다시 설치할 수 있게 되돌린다. */
    private fun armInstallWatchdog(info: UpdateInfo) {
        installWatchdog?.cancel()
        installWatchdog = scope.launch {
            delay(INSTALL_TIMEOUT_MS)
            _state.update { cur -> if (cur is UpdateState.Installing && cur.info == info) UpdateState.ReadyToInstall(info) else cur }
        }
    }

    private fun setIfInstalling(next: UpdateState) {
        _state.update { cur -> if (cur is UpdateState.Installing) next else cur }
    }

    // ---- 앱이 보이는지(화면이 알려 줌) ----

    /**
     * 앱 화면이 다시 보인다: 미뤄 둔 설치 확인 화면을 띄우고, 백그라운드에서 끊긴 받기를 한 번 다시 하고,
     * 권한을 허용하고 돌아왔으면 '권한 없음' 실패를 걷어 [설치] 단계로 돌린다.
     */
    fun onAppResumed() {
        foreground = true
        val confirm = pendingConfirm
        if (confirm != null) {
            val info = pendingConfirmInfo
            pendingConfirm = null
            pendingConfirmInfo = null
            runCatching { prompter.cancelNotification() }
            if (prompter.launch(confirm)) {
                // 기다리다 설치 대기로 돌아갔으면 다시 설치 중으로
                if (info != null) _state.update { cur -> if (cur is UpdateState.ReadyToInstall && cur.info == info) UpdateState.Installing(info) else cur }
                onInstallAwaitingUser()
            } else {
                onInstallFailed(FailureText(R.string.update_err_confirm_ui, R.string.update_fix_confirm_ui))
            }
        }
        refreshBlockedFailure()
        retryDownloadIfPending()
    }

    /** 앱 화면이 가려졌다 */
    fun onAppPaused() {
        foreground = false
        if (_state.value is UpdateState.Downloading) backgroundedDuringDownload = true
    }

    /** 권한 허용·가져오기 끝남으로 설치를 막던 이유가 풀렸으면 [설치] 단계로 돌린다 */
    fun refreshBlockedFailure() {
        synchronized(lock) {
            val s = _state.value as? UpdateState.Failed ?: return
            if (s !== blockedFailure) return
            val cleared = when (blocker) {
                Blocker.PERMISSION -> canInstallPackages()
                Blocker.IMPORTING -> !isInstallBlockedByImport()
                null -> false
            }
            if (cleared) dismissError()
        }
    }

    private fun setBlocked(reason: Blocker, state: UpdateState.Failed) {
        blocker = reason
        blockedFailure = state
        _state.value = state
    }

    // ---- 기타 ----

    override fun dismissError() {
        synchronized(lock) {
            val s = _state.value as? UpdateState.Failed ?: return
            val info = s.info
            blockedFailure = null
            blocker = null
            _state.value = when {
                info == null -> UpdateState.Idle
                info.versionCode == verifiedVersionCode -> UpdateState.ReadyToInstall(info)
                else -> UpdateState.Available(info)
            }
        }
    }

    override fun consumeJustUpdated() {
        if (_justUpdated.value == null) return
        _justUpdated.value = null
        _justUpdatedNotes.value = null
        UpdateNotifications.cancel(context)
        scope.launch(io) { runCatching { prefs.setLastRunVersionCode(currentVersionCode) } }
    }

    /** 앱이 직접 설치하지 않으므로(다운로드 폴더의 APK를 사용자가 연다) 설치 권한이 필요 없다. */
    override fun canInstallPackages(): Boolean = true

    override fun installPermissionSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * 업데이트 직후 첫 실행 판정. 안내를 실제로 보여주고 닫을 때([consumeJustUpdated]) 실행 버전을 저장한다.
     * (설치 직후 WorkManager 등이 화면 없이 프로세스를 띄워도 안내가 사라지지 않게)
     */
    private suspend fun detectJustUpdated() {
        val last = prefs.lastRunVersionCode()
        val text = JustUpdated.message(last, currentVersionCode, currentVersionName)
        if (text != null) {
            _justUpdatedNotes.value = UpdateFiles.readNotes(context, currentVersionCode)
            _justUpdated.value = text
        } else if (last != currentVersionCode) {
            prefs.setLastRunVersionCode(currentVersionCode)
        }
    }

    private fun setIfNotTransient(next: UpdateState) {
        _state.update { cur -> if (cur.isTransient()) cur else next }
    }

    private fun failed(text: FailureText, info: UpdateInfo?) = UpdateState.Failed(
        message = context.getString(text.message),
        fix = text.fix?.let(context::getString),
        info = info,
    )

    companion object {
        /** 자동 확인(앱 시작) 최소 간격 */
        const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        /** 앱 시작 후 자동 확인까지 기다리는 시간 */
        const val AUTO_CHECK_DELAY_MS = 3_000L
        private const val INSTALL_TIMEOUT_MS = 3 * 60 * 1000L

        fun checkFailureText(reason: CheckFailure): FailureText = when (reason) {
            CheckFailure.NETWORK -> FailureText(R.string.update_err_network, R.string.update_fix_network)
            CheckFailure.RATE_LIMITED -> FailureText(R.string.update_err_rate_limited, R.string.update_fix_rate_limited)
            CheckFailure.SERVER -> FailureText(R.string.update_err_server, R.string.update_fix_later)
            CheckFailure.INVALID_RELEASE -> FailureText(R.string.update_err_invalid_release, R.string.update_fix_release_page)
        }

        /** 받는 중·설치 중: 확인 결과로 덮어쓰지 않는다 */
        private fun UpdateState.isTransient(): Boolean = this is UpdateState.Downloading || this is UpdateState.Installing

        /** 이미 알고 있는 새 버전(받기 전/설치 대기) */
        private fun UpdateState.knownInfo(): UpdateInfo? = when (this) {
            is UpdateState.Available -> info
            is UpdateState.ReadyToInstall -> info
            else -> null
        }

        /**
         * 가져오는 중인지(설치하면 앱이 다시 시작돼 가져오기가 끊긴다). 서비스가 막 시작해
         * 상태가 아직 Running이 아닌 순간도 포함한다.
         */
        fun isImportBusy(controller: ImportController): Boolean =
            (controller as? ImportControllerImpl)?.isImporting ?: (controller.state.value is ImportState.Running)

        private fun defaultImportRunning(context: Context): Boolean = runCatching {
            isImportBusy(PhotoCullApp.container(context).importController)
        }.getOrDefault(false)
    }
}
