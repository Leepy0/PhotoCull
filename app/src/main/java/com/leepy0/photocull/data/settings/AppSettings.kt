package com.leepy0.photocull.data.settings

/**
 * 흐림 민감도. 값은 선명도 점수(1/2 축소 영상의 타일별 Laplacian 분산 상위 3개 평균) 임계값이다.
 * 민감도가 높을수록 임계값이 높아 더 많은 사진이 후보로 잡힌다.
 * 실제 값은 analysis/BlurThresholds.kt 한 곳에서 관리한다.
 */
enum class BlurSensitivity { LOW, NORMAL, HIGH }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    /** DCIM 아래 저장 폴더 이름 */
    val destFolderName: String = DEFAULT_DEST_FOLDER,
    /** true면 DCIM/<폴더>/2026-10/ 처럼 월별 하위 폴더 */
    val monthlySubfolders: Boolean = false,
    /** true = 검증 후 카드 원본 삭제(이동), false = 카드에 남김(복사) */
    val moveMode: Boolean = true,
    val cardNotification: Boolean = true,
    val blurSensitivity: BlurSensitivity = BlurSensitivity.NORMAL,
    val burstGapMs: Long = 1_000,
    val similarGapMs: Long = 10_000,
    val similarMaxDistance: Int = 10,
    val shortVideoMs: Long = 3_000,
    /** 0 = 무기한 */
    val trashRetentionDays: Int = 30,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val autoUpdateCheck: Boolean = true,
    val lastUpdateCheckAt: Long = 0,
    /** 업데이트 후 첫 실행에서 "vX로 업데이트됨"을 한 번 보여주기 위한 마지막 실행 버전 */
    val lastRunVersionCode: Int = 0,
    val onboardingDone: Boolean = false,
) {
    companion object {
        const val DEFAULT_DEST_FOLDER = "R10"
        val TRASH_RETENTION_OPTIONS = listOf(7, 30, 0)
        val BURST_GAP_OPTIONS_MS = listOf(500L, 1_000L, 2_000L)
    }
}
