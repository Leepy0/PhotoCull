package com.leepy0.photocull.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.leepy0.photocull.BuildConfig
import com.leepy0.photocull.PhotoCullApp
import com.leepy0.photocull.R
import com.leepy0.photocull.data.settings.SettingsRepository
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
    /** 받아서 SHA-256까지 확인한 APK의 versionCode(메인 스레드에서 디스크를 보지 않고 상태를 되돌리기 위함) */
    @Volatile private var verifiedVersionCode: Int? = null

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

    override fun check(userInitiated: Boolean) {
        synchronized(lock) {
            if (checkJob?.isActive == true) {
                if (userInitiated) userWaiting = true
                return
            }
            if (_state.value.isBusy()) return
            userWaiting = userInitiated
            checkJob = scope.launch(io) { performCheck(userInitiated = userInitiated, respectInterval = !userInitiated) }
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
        if (previous.isBusy()) return null
        if (userInitiated || previous !is UpdateState.Available) _state.value = UpdateState.Checking

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
                val ready = runCatching { downloader.verifiedApk(result.info) }.getOrNull()
                verifiedVersionCode = if (ready != null) result.info.versionCode else null
                setIfNotBusy(if (ready != null) UpdateState.ReadyToInstall(result.info) else UpdateState.Available(result.info))
            }
            is CheckResult.UpToDate -> {
                runCatching { prefs.setLastCheckAt(now) }
                setIfNotBusy(UpdateState.UpToDate(now))
            }
            is CheckResult.Failed -> {
                val show = userInitiated || userWaiting
                setIfNotBusy(
                    when {
                        show -> failed(checkFailureText(result.reason), info = null)
                        previous is UpdateState.Available -> previous
                        else -> UpdateState.Idle
                    },
                )
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
            _state.value = UpdateState.Downloading(info, if (info.sizeBytes != null) 0f else -1f)
            downloadJob = scope.launch(io) {
                runCatching { UpdateFiles.writeNotes(context, info) }
                val result = try {
                    downloader.download(info) { p -> updateIfDownloading(info) { UpdateState.Downloading(info, p) } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DownloadResult.NetworkError
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
                updateIfDownloading(info) { next }
            }
        }
    }

    override fun cancelDownload() {
        synchronized(lock) {
            val s = _state.value as? UpdateState.Downloading ?: return
            downloadJob?.cancel()
            downloadJob = null
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
                _state.value = failed(FailureText(R.string.update_err_no_permission, R.string.update_fix_no_permission), info)
                return
            }
            if (isImportRunning()) {
                _state.value = failed(FailureText(R.string.update_err_importing, R.string.update_fix_importing), info)
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

    /** 시스템 설치 확인 화면이 떴다. 사용자가 고르는 동안 기다린다. */
    fun onInstallAwaitingUser() {
        val s = _state.value as? UpdateState.Installing ?: return
        armInstallWatchdog(s.info)
    }

    /** [InstallResultReceiver]가 실패를 알려 줄 때 */
    fun onInstallFailed(text: FailureText) {
        installWatchdog?.cancel()
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

    // ---- 기타 ----

    override fun dismissError() {
        synchronized(lock) {
            val s = _state.value as? UpdateState.Failed ?: return
            val info = s.info
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

    override fun canInstallPackages(): Boolean = runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

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

    private fun setIfNotBusy(next: UpdateState) {
        _state.update { cur -> if (cur.isBusy()) cur else next }
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

        private fun UpdateState.isBusy(): Boolean =
            this is UpdateState.Downloading || this is UpdateState.Installing || this is UpdateState.ReadyToInstall

        private fun defaultImportRunning(context: Context): Boolean = runCatching {
            PhotoCullApp.container(context).importController.state.value is ImportState.Running
        }.getOrDefault(false)
    }
}
