package com.leepy0.photocull.ui.settings

import com.leepy0.photocull.data.settings.AppSettings
import com.leepy0.photocull.storage.StoragePaths
import com.leepy0.photocull.trash.PurgePreview
import com.leepy0.photocull.update.UpdateState
import java.util.Locale

enum class SettingsPhase { LOADING, ERROR, READY }

/** 권한 상태(화면에 돌아올 때마다 다시 확인) */
data class PermissionStatus(
    val allFiles: Boolean = false,
    val notifications: Boolean = false,
    val installPackages: Boolean = false,
)

/** 보관 기간을 줄이면 바로 영구 삭제될 항목이 있을 때 확인 */
data class RetentionConfirm(val days: Int, val count: Int, val bytes: Long)

data class SettingsUiState(
    val phase: SettingsPhase = SettingsPhase.LOADING,
    val settings: AppSettings = AppSettings(),
    val permissions: PermissionStatus = PermissionStatus(),
    /** "v0.1.0 (100) · release · abc1234" */
    val versionLine: String = "",
    val update: UpdateState = UpdateState.Idle,
    /** 마지막 비정상 종료 기록(없으면 null) */
    val lastCrash: String? = null,
    /** 월별 하위 폴더 예시에 쓰는 이번 달 "2026-10" */
    val exampleMonth: String = "",
    val retentionConfirm: RetentionConfirm? = null,
)

/** 설정 화면의 선택지 */
object SettingsOptions {
    val burstGapsMs: List<Long> = AppSettings.BURST_GAP_OPTIONS_MS
    val shortVideosMs: List<Long> = listOf(2_000L, 3_000L, 5_000L)
    val retentionDays: List<Int> = AppSettings.TRASH_RETENTION_OPTIONS

    /** 500 → "0.5", 1000 → "1" */
    fun seconds(ms: Long): String =
        if (ms % 1000 == 0L) (ms / 1000).toString() else String.format(Locale.US, "%.1f", ms / 1000.0)
}

/** 저장 폴더 이름 검사. 실제 정리 규칙은 [StoragePaths.sanitizeFolderName] 한 곳에 있다. */
object FolderNameRules {
    const val MAX_LENGTH = 60

    sealed interface Check {
        /** [name] = 실제로 저장할 이름, [replaced] = 입력에서 바뀐 글자가 있음 */
        data class Valid(val name: String, val replaced: Boolean) : Check
        data object Blank : Check
        data object TooLong : Check
        data object OnlyDots : Check
    }

    fun check(input: String): Check {
        val trimmed = input.trim()
        return when {
            trimmed.isEmpty() -> Check.Blank
            trimmed.length > MAX_LENGTH -> Check.TooLong
            trimmed.trimStart('.').isBlank() -> Check.OnlyDots
            else -> {
                val name = StoragePaths.sanitizeFolderName(trimmed)
                Check.Valid(name, replaced = name != trimmed)
            }
        }
    }
}

/** 보관 기간 변경 규칙 */
object RetentionRules {
    /** 더 짧아지는가(무기한 → 기간, 긴 기간 → 짧은 기간) */
    fun isShortening(current: Int, next: Int): Boolean = next > 0 && (current <= 0 || next < current)

    sealed interface Decision {
        /** 바로 지워질 것이 없다 → 확인 없이 저장 */
        data object Save : Decision

        /** 바로 지워질 것이 있다 → 확인 대화상자 */
        data class Confirm(val confirm: RetentionConfirm) : Decision

        /** 지워질 것을 세지 못했다 → 바꾸지 않고 저장 실패를 알린다(확인 없이 지우는 일이 없게) */
        data object Fail : Decision
    }

    /**
     * 기간을 [days]로 줄일 때. [preview]는 TrashManager.previewPurge 결과로, 실제 정리(purgeExpired)와
     * 같은 기준(단위 수·용량)으로 센 것이다.
     */
    fun decide(days: Int, preview: Result<PurgePreview?>): Decision {
        val p = preview.getOrNull() ?: return Decision.Fail
        return if (p.count <= 0 && p.bytes <= 0L) Decision.Save else Decision.Confirm(RetentionConfirm(days, p.count, p.bytes))
    }
}

sealed interface SettingsMessage {
    data object SaveFailed : SettingsMessage
}
