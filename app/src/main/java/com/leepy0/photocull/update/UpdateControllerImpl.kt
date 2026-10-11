package com.leepy0.photocull.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.leepy0.photocull.BuildConfig
import com.leepy0.photocull.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// TODO(update): 매니페스트 확인·다운로드·PackageInstaller 설치 실제 구현으로 교체
class UpdateControllerImpl(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
) : UpdateController {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state
    override val currentVersionName: String = BuildConfig.VERSION_NAME
    override val currentVersionCode: Int = BuildConfig.VERSION_CODE
    override val channel: String = BuildConfig.BUILD_CHANNEL
    private val _justUpdated = MutableStateFlow<String?>(null)
    override val justUpdated: StateFlow<String?> = _justUpdated
    override fun check(userInitiated: Boolean) {}
    override fun download() {}
    override fun install() {}
    override fun dismissError() {}
    override fun consumeJustUpdated() { _justUpdated.value = null }
    override fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()
    override fun installPermissionSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
    override val releasesPageUrl: String = "https://github.com/${BuildConfig.UPDATE_REPO}/releases"
}
