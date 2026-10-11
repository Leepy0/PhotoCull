package com.leepy0.photocull.ui.settings

import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.trash.PurgePreview
import org.junit.Test

class SettingsRulesTest {

    @Test fun folderNameValid() {
        assertThat(FolderNameRules.check("  R10  ")).isEqualTo(FolderNameRules.Check.Valid("R10", replaced = false))
        assertThat(FolderNameRules.check("여행 2026")).isEqualTo(FolderNameRules.Check.Valid("여행 2026", replaced = false))
    }

    @Test fun folderNameReplacesPathCharacters() {
        assertThat(FolderNameRules.check("R10/여행")).isEqualTo(FolderNameRules.Check.Valid("R10_여행", replaced = true))
        assertThat(FolderNameRules.check(".hidden")).isEqualTo(FolderNameRules.Check.Valid("hidden", replaced = true))
        assertThat(FolderNameRules.check("a:b*c?")).isEqualTo(FolderNameRules.Check.Valid("a_b_c_", replaced = true))
    }

    @Test fun folderNameRejectsBlankDotsAndTooLong() {
        assertThat(FolderNameRules.check("   ")).isEqualTo(FolderNameRules.Check.Blank)
        assertThat(FolderNameRules.check("...")).isEqualTo(FolderNameRules.Check.OnlyDots)
        assertThat(FolderNameRules.check("x".repeat(FolderNameRules.MAX_LENGTH + 1))).isEqualTo(FolderNameRules.Check.TooLong)
    }

    @Test fun secondsLabels() {
        assertThat(SettingsOptions.seconds(500)).isEqualTo("0.5")
        assertThat(SettingsOptions.seconds(1_000)).isEqualTo("1")
        assertThat(SettingsOptions.seconds(2_000)).isEqualTo("2")
    }

    @Test fun retentionShortening() {
        assertThat(RetentionRules.isShortening(current = 30, next = 7)).isTrue()
        assertThat(RetentionRules.isShortening(current = 0, next = 30)).isTrue()
        assertThat(RetentionRules.isShortening(current = 7, next = 30)).isFalse()
        assertThat(RetentionRules.isShortening(current = 30, next = 0)).isFalse()
    }

    @Test fun retentionDecisionUsesPurgeCountAndNeverSavesBlindly() {
        // 바로 지워질 것이 없으면 확인 없이 저장
        assertThat(RetentionRules.decide(7, Result.success(PurgePreview(0, 0)))).isEqualTo(RetentionRules.Decision.Save)
        // 있으면 실제 정리와 같은 기준으로 센 수·용량으로 확인
        assertThat(RetentionRules.decide(7, Result.success(PurgePreview(3, 900))))
            .isEqualTo(RetentionRules.Decision.Confirm(RetentionConfirm(days = 7, count = 3, bytes = 900)))
        // 세지 못했으면(조회 실패·알 수 없음) 바꾸지 않는다 — 확인 없이 영구 삭제되는 일이 없게
        assertThat(RetentionRules.decide(7, Result.failure(IllegalStateException("db")))).isEqualTo(RetentionRules.Decision.Fail)
        assertThat(RetentionRules.decide(7, Result.success(null))).isEqualTo(RetentionRules.Decision.Fail)
    }
}
