package com.leepy0.photocull.ui.home

import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.data.db.ImportSessionEntity
import com.leepy0.photocull.data.db.SessionState
import com.leepy0.photocull.data.review.ReviewCounts
import com.leepy0.photocull.importer.ImportError
import com.leepy0.photocull.importer.ImportState
import com.leepy0.photocull.storage.CardState
import com.leepy0.photocull.ui.components.UiState
import com.leepy0.photocull.ui.importer.ImportFakes
import com.leepy0.photocull.ui.nav.ReviewTab
import org.junit.Test

class HomeModelsTest {
    private val card = CardState.Ready(ImportFakes.volume)
    private val needsAccess = CardState.NeedsAccess("3A1B-27F0", "EOS_DIGITAL")

    // ---- 카드 상태 카드

    @Test fun importing_winsOverEverything() {
        val running = ImportState.Running(ImportFakes.progress())
        assertThat(HomeCardStatus.of(CardState.NoPermission, running)).isInstanceOf(HomeCardStatus.Importing::class.java)
        assertThat(HomeCardStatus.of(card, running)).isEqualTo(HomeCardStatus.Importing(ImportFakes.progress(), "EOS_DIGITAL"))
        assertThat(HomeCardStatus.of(CardState.NotConnected, running)).isEqualTo(HomeCardStatus.Importing(ImportFakes.progress(), null))
    }

    @Test fun permissionProblems_winOverResults() {
        val finished = ImportState.Finished(ImportFakes.result())
        assertThat(HomeCardStatus.of(CardState.NoPermission, finished)).isEqualTo(HomeCardStatus.NoPermission)
        assertThat(HomeCardStatus.of(needsAccess, ImportState.Failed(ImportError.NoPermission)))
            .isEqualTo(HomeCardStatus.NeedsAccess("3A1B-27F0", "EOS_DIGITAL"))
    }

    @Test fun finished_shownEvenWhenCardRemoved() {
        val result = ImportFakes.result()
        assertThat(HomeCardStatus.of(CardState.NotConnected, ImportState.Finished(result))).isEqualTo(HomeCardStatus.Finished(result))
    }

    @Test fun notConnected_carriesInterruptedImport() {
        val removed = ImportError.CardRemoved(120, 300)
        assertThat(HomeCardStatus.of(CardState.NotConnected, ImportState.Failed(removed))).isEqualTo(HomeCardStatus.NotConnected(removed))
        assertThat(HomeCardStatus.of(CardState.NotConnected, ImportState.Failed(ImportError.NoCard))).isEqualTo(HomeCardStatus.NotConnected())
        assertThat(HomeCardStatus.of(CardState.NotConnected, ImportState.Idle)).isEqualTo(HomeCardStatus.NotConnected())
    }

    @Test fun readyCard_states() {
        assertThat(HomeCardStatus.of(card, ImportState.Idle)).isEqualTo(HomeCardStatus.Checking("EOS_DIGITAL"))
        assertThat(HomeCardStatus.of(card, ImportState.Scanning)).isEqualTo(HomeCardStatus.Checking("EOS_DIGITAL"))
        val summary = ImportFakes.summary()
        assertThat(HomeCardStatus.of(card, ImportState.Ready(summary))).isEqualTo(HomeCardStatus.CardReady(summary))
        val other = CardState.Ready(ImportFakes.volume.copy(id = "OTHER"))
        assertThat(HomeCardStatus.of(other, ImportState.Ready(summary))).isInstanceOf(HomeCardStatus.Checking::class.java)
        val unexpected = ImportError.Unexpected("x")
        assertThat(HomeCardStatus.of(card, ImportState.Failed(unexpected))).isEqualTo(HomeCardStatus.Failed(unexpected, "EOS_DIGITAL"))
    }

    // ---- 주요 행동

    private val counts = ReviewCounts(blur = 0, groups = 3, groupPhotos = 12, videos = 2)

    @Test fun primary_permissionFirst() {
        assertThat(HomePrimary.of(HomeCardStatus.NoPermission, counts)).isEqualTo(HomePrimary.GrantPermission)
        assertThat(HomePrimary.of(HomeCardStatus.NeedsAccess("v", "l"), counts)).isEqualTo(HomePrimary.GrantFolderAccess("v"))
    }

    @Test fun primary_importWhenCardHasNewFiles() {
        val summary = ImportFakes.summary()
        assertThat(HomePrimary.of(HomeCardStatus.CardReady(summary), counts))
            .isEqualTo(HomePrimary.Import(summary.newFileCount, summary.newBytes))
        assertThat(HomePrimary.of(HomeCardStatus.Checking("l"), counts)).isEqualTo(HomePrimary.Import(null, null))
    }

    @Test fun primary_nothingNewOnCard_fallsBackToReview() {
        val empty = ImportFakes.summary(newFiles = 0, newBytes = 0)
        assertThat(HomePrimary.of(HomeCardStatus.CardReady(empty), counts)).isEqualTo(HomePrimary.ContinueReview(ReviewTab.BURST))
        assertThat(HomePrimary.of(HomeCardStatus.CardReady(empty), ReviewCounts())).isEqualTo(HomePrimary.None)
    }

    @Test fun primary_reviewAfterFinish_andWhileImporting() {
        assertThat(HomePrimary.of(HomeCardStatus.Finished(ImportFakes.result()), counts)).isEqualTo(HomePrimary.StartReview(ReviewTab.BURST))
        assertThat(HomePrimary.of(HomeCardStatus.Importing(ImportFakes.progress(), null), counts)).isEqualTo(HomePrimary.ContinueReview(ReviewTab.BURST))
        assertThat(HomePrimary.of(HomeCardStatus.NotConnected(), ReviewCounts(videos = 1))).isEqualTo(HomePrimary.ContinueReview(ReviewTab.VIDEO))
        assertThat(HomePrimary.of(HomeCardStatus.NotConnected(), ReviewCounts())).isEqualTo(HomePrimary.None)
        // 검토 건수를 아직 모르면 버튼을 내지 않는다
        assertThat(HomePrimary.of(HomeCardStatus.NotConnected(), null)).isEqualTo(HomePrimary.None)
    }

    @Test fun firstReviewTab_order() {
        assertThat(HomePrimary.firstReviewTab(ReviewCounts(blur = 1, groups = 1, videos = 1))).isEqualTo(ReviewTab.BLUR)
        assertThat(HomePrimary.firstReviewTab(ReviewCounts(groups = 1, videos = 1))).isEqualTo(ReviewTab.BURST)
        assertThat(HomePrimary.firstReviewTab(ReviewCounts())).isNull()
    }

    // ---- 기록

    @Test fun lastImport_fromSession() {
        val running = ImportSessionEntity(id = 1, volumeId = "v", volumeLabel = "EOS", startedAt = 1, moveMode = true)
        assertThat(LastImport.from(running)).isNull()
        val done = running.copy(finishedAt = 10, state = SessionState.INTERRUPTED, successCount = 5, failedCount = 1)
        assertThat(LastImport.from(done)).isEqualTo(
            LastImport(10, "EOS", true, savedCount = 5, duplicateCount = 0, failedCount = 1, sourceDeleteFailedCount = 0, state = SessionState.INTERRUPTED),
        )
    }

    @Test fun lastImport_hiddenWhileResultCardShows() {
        val last = HomeFakes.lastImport
        val finished = HomeUiState(card = card, import = ImportState.Finished(ImportFakes.result()), lastImport = last, review = UiState.Content(counts))
        assertThat(finished.visibleLastImport).isNull()
        assertThat(finished.copy(import = ImportState.Idle).visibleLastImport).isEqualTo(last)
    }
}
