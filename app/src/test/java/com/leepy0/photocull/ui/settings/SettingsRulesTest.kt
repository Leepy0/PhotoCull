package com.leepy0.photocull.ui.settings

import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.data.db.MediaType
import com.leepy0.photocull.data.db.ReviewState
import com.leepy0.photocull.data.review.MediaCard
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

    @Test fun retentionExpiringMatchesPurgeCutoff() {
        val now = 100L * DAY
        val items = listOf(card(1, now - 8 * DAY), card(2, now - 7 * DAY + 1), card(3, now - 20 * DAY), card(4, null))
        assertThat(RetentionRules.expiring(items, 7, now).map { it.id }).containsExactly(1L, 3L)
        assertThat(RetentionRules.expiring(items, 0, now)).isEmpty()
    }

    private fun card(id: Long, trashedAt: Long?) = MediaCard(
        id = id, path = "/x/$id.JPG", fileName = "$id.JPG", type = MediaType.PHOTO, mimeType = "image/jpeg",
        capturedAt = 0, bytes = 1, hasRaw = false, width = null, height = null, sharpness = null, focusX = null,
        focusY = null, durationMs = null, reviewState = ReviewState.TRASHED, groupId = null, trashedAt = trashedAt,
        iso = null, exposure = null, aperture = null, focalLength = null,
    )

    private companion object {
        const val DAY = 86_400_000L
    }
}
