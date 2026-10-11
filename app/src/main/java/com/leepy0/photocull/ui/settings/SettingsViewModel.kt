package com.leepy0.photocull.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leepy0.photocull.AppContainer
import com.leepy0.photocull.BuildConfig
import com.leepy0.photocull.data.settings.AppSettings
import com.leepy0.photocull.data.settings.BlurSensitivity
import com.leepy0.photocull.data.settings.SettingsRepository
import com.leepy0.photocull.data.settings.ThemeMode
import com.leepy0.photocull.diagnostics.CrashReporter
import com.leepy0.photocull.util.Permissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 설정 화면. 모든 값은 SettingsRepository로 저장하고, 저장은 앱 범위 코루틴에서 끝까지 진행한다.
 * - 연사 간격을 바꾸면 그룹을 다시 계산한다.
 * - 보관 기간을 줄여서 바로 지워질 항목이 있으면 먼저 확인받고, 바꾼 뒤 곧바로 정리한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    private val container: AppContainer,
    context: Context,
) : ViewModel() {
    private val app = context.applicationContext
    private val settings: SettingsRepository get() = container.settings

    private val permissions = MutableStateFlow(PermissionStatus())
    private val crash = MutableStateFlow<String?>(null)
    private val retentionConfirm = MutableStateFlow<RetentionConfirm?>(null)
    private val reload = MutableStateFlow(0)

    private val _messages = Channel<SettingsMessage>(Channel.BUFFERED)
    val messages: Flow<SettingsMessage> = _messages.receiveAsFlow()

    private val versionLine: String = with(container.update) {
        "v$currentVersionName ($currentVersionCode) · $channel · ${BuildConfig.GIT_SHA}"
    }

    private val loaded: Flow<Result<AppSettings>> = reload.flatMapLatest {
        settings.settings.map { Result.success(it) }.catch { e ->
            if (e is CancellationException) throw e
            emit(Result.failure(e))
        }
    }

    val state: StateFlow<SettingsUiState> = combine(
        loaded,
        permissions,
        crash,
        container.update.state,
        retentionConfirm,
    ) { result, perms, lastCrash, update, confirm ->
        val value = result.getOrNull()
        SettingsUiState(
            phase = if (value == null) SettingsPhase.ERROR else SettingsPhase.READY,
            settings = value ?: AppSettings(),
            permissions = perms,
            versionLine = versionLine,
            update = update,
            lastCrash = lastCrash,
            exampleMonth = currentMonth(),
            retentionConfirm = confirm,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState(versionLine = versionLine))

    init {
        refresh()
    }

    /** 권한·오류 기록 다시 확인(화면에 돌아올 때마다) */
    fun refresh() {
        permissions.value = PermissionStatus(
            allFiles = runCatching { Permissions.hasAllFilesAccess() }.getOrDefault(false),
            notifications = runCatching { Permissions.hasNotificationPermission(app) }.getOrDefault(false),
            installPackages = runCatching { container.update.canInstallPackages() }.getOrDefault(false),
        )
        viewModelScope.launch {
            crash.value = withContext(Dispatchers.IO) { runCatching { CrashReporter.lastCrash(app) }.getOrNull() }
        }
    }

    fun retry() {
        reload.update { it + 1 }
        refresh()
    }

    // ---- 가져오기 ----

    fun setDestFolder(input: String) {
        val valid = FolderNameRules.check(input) as? FolderNameRules.Check.Valid ?: return
        write { setDestFolderName(valid.name) }
    }

    fun setMonthly(value: Boolean) = write { setMonthlySubfolders(value) }
    fun setMoveMode(value: Boolean) = write { this.setMoveMode(value) }
    fun setCardNotification(value: Boolean) = write { this.setCardNotification(value) }

    // ---- 검토 ----

    fun setBlurSensitivity(value: BlurSensitivity) = write { this.setBlurSensitivity(value) }

    fun setBurstGap(ms: Long) {
        if (ms == state.value.settings.burstGapMs) return
        write(after = { container.analysis.regroup() }) { setBurstGapMs(ms) }
    }

    fun setShortVideo(ms: Long) = write { setShortVideoMs(ms) }

    // ---- 휴지통 ----

    fun requestRetention(days: Int) {
        val current = state.value.settings.trashRetentionDays
        if (days == current) return
        if (!RetentionRules.isShortening(current, days)) {
            write { setTrashRetentionDays(days) }
            return
        }
        viewModelScope.launch {
            val trashed = runCatching { container.review.trashed().first() }.getOrDefault(emptyList())
            val expiring = RetentionRules.expiring(trashed, days, System.currentTimeMillis())
            if (expiring.isEmpty()) {
                write { setTrashRetentionDays(days) }
            } else {
                retentionConfirm.value = RetentionConfirm(days, expiring.size, expiring.sumOf { it.bytes })
            }
        }
    }

    fun confirmRetention() {
        val confirm = retentionConfirm.value ?: return
        retentionConfirm.value = null
        write(after = { container.trash.purgeExpired() }) { setTrashRetentionDays(confirm.days) }
    }

    fun dismissRetention() {
        retentionConfirm.value = null
    }

    // ---- 화면·앱 정보 ----

    fun setTheme(value: ThemeMode) = write { setThemeMode(value) }
    fun setAutoUpdate(value: Boolean) = write { setAutoUpdateCheck(value) }

    fun checkUpdate() {
        container.update.check(userInitiated = true)
    }

    fun clearCrash() {
        crash.value = null
        container.appScope.launch(Dispatchers.IO) { runCatching { CrashReporter.clear(app) } }
    }

    /** 저장은 화면을 떠나도 끝나도록 앱 범위에서. 실패하면 스낵바로 알린다. */
    private fun write(after: suspend () -> Unit = {}, block: suspend SettingsRepository.() -> Unit) {
        container.appScope.launch {
            try {
                settings.block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.trySend(SettingsMessage.SaveFailed)
                return@launch
            }
            runCatching { after() }
        }
    }

    private fun currentMonth(): String = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"))

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
