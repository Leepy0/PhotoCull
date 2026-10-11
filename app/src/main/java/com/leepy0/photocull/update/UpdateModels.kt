package com.leepy0.photocull.update

import android.content.Intent
import kotlinx.coroutines.flow.StateFlow

data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
    /** 소문자 64자리 hex. 없으면 검증 생략 불가 → 다운로드 후 설치 거부 */
    val sha256: String?,
    val sizeBytes: Long?,
    val notes: String,
    val pageUrl: String,
    val publishedAt: String?,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    /** progress: 0..1, 크기를 모르면 -1 */
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class ReadyToInstall(val info: UpdateInfo) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState
    /** message = 무엇이 왜 실패했는지, fix = 사용자가 할 수 있는 해결 방법 */
    data class Failed(val message: String, val fix: String?, val info: UpdateInfo?) : UpdateState
}

/**
 * GitHub Releases 기반 자동 업데이트.
 * 확인: releases/latest/download/PhotoCull.json(매니페스트) → 실패 시 api.github.com 순.
 * 받기: 앱이 직접 내려받아 SHA-256 검증. 설치: PackageInstaller 세션(가능하면 사용자 확인 없이).
 */
interface UpdateController {
    val state: StateFlow<UpdateState>
    val currentVersionName: String
    val currentVersionCode: Int
    /** "release" 또는 "local" */
    val channel: String

    /** 업데이트 직후 첫 실행이면 "0.1.0 → 0.2.0" 같은 문구. 한 번 보여주고 [consumeJustUpdated] */
    val justUpdated: StateFlow<String?>

    /** userInitiated=false면 마지막 확인 후 6시간이 안 지났으면 건너뛴다 */
    fun check(userInitiated: Boolean)
    fun download()
    fun install()
    fun dismissError()
    fun consumeJustUpdated()

    /** 이 앱의 '출처를 알 수 없는 앱 설치' 허용 여부 */
    fun canInstallPackages(): Boolean
    fun installPermissionSettingsIntent(): Intent
    val releasesPageUrl: String
}
