package com.leepy0.photocull.ui.importer

import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.analysis.AnalysisStatus
import com.leepy0.photocull.data.review.ReviewCounts
import com.leepy0.photocull.importer.FailureReason
import com.leepy0.photocull.importer.ImportError
import com.leepy0.photocull.importer.ImportState
import com.leepy0.photocull.storage.CardState
import org.junit.Test

class ImportViewTest {
    private val ready = CardState.Ready(ImportFakes.volume)
    private val needsAccess = CardState.NeedsAccess("3A1B-27F0", "EOS_DIGITAL")

    @Test fun idle_followsCardState() {
        assertThat(ImportView.of(ImportState.Idle, ready)).isEqualTo(ImportView.Checking)
        assertThat(ImportView.of(ImportState.Idle, CardState.NotConnected)).isEqualTo(ImportView.NoCard())
        assertThat(ImportView.of(ImportState.Idle, CardState.NotCameraCard("USB"))).isEqualTo(ImportView.NoCard("USB"))
        assertThat(ImportView.of(ImportState.Idle, CardState.NoPermission)).isEqualTo(ImportView.NeedsPermission)
        assertThat(ImportView.of(ImportState.Idle, needsAccess)).isEqualTo(ImportView.NeedsFolderAccess("3A1B-27F0", "EOS_DIGITAL"))
    }

    @Test fun activeStates_winOverCard() {
        val progress = ImportFakes.progress()
        assertThat(ImportView.of(ImportState.Running(progress), CardState.NotConnected)).isEqualTo(ImportView.Running(progress))
        assertThat(ImportView.of(ImportState.Scanning, CardState.NotConnected)).isEqualTo(ImportView.Checking)
        val result = ImportFakes.result()
        assertThat(ImportView.of(ImportState.Finished(result), CardState.NotConnected)).isEqualTo(ImportView.Finished(result))
    }

    @Test fun noPermissionFailure_withFolderApprovalNeeded_showsFolderFlow() {
        val failed = ImportState.Failed(ImportError.NoPermission)
        assertThat(ImportView.of(failed, needsAccess)).isEqualTo(ImportView.NeedsFolderAccess("3A1B-27F0", "EOS_DIGITAL"))
        assertThat(ImportView.of(failed, CardState.NoPermission)).isEqualTo(ImportView.NeedsPermission)
        assertThat(ImportView.of(failed, ready)).isEqualTo(ImportView.Failed(ImportError.NoPermission))
    }

    @Test fun noCardFailure_withCardGone_showsConnectGuidance() {
        val failed = ImportState.Failed(ImportError.NoCard)
        assertThat(ImportView.of(failed, CardState.NotConnected)).isEqualTo(ImportView.NoCard())
        assertThat(ImportView.of(failed, ready)).isEqualTo(ImportView.Failed(ImportError.NoCard))
    }

    @Test fun cardRemoved_staysAnError() {
        val error = ImportError.CardRemoved(120, 300)
        assertThat(ImportView.of(ImportState.Failed(error), CardState.NotConnected)).isEqualTo(ImportView.Failed(error))
    }

    @Test fun preparing_isZeroProgressBeforeServiceStarts() {
        assertThat(ImportFakes.preparing.isPreparing).isTrue()
        assertThat(ImportFakes.progress().isPreparing).isFalse()
        assertThat(ImportFakes.progress(done = 0, doneBytes = 1024, speed = 0).isPreparing).isFalse()
    }

    @Test fun failures_areSplitByReason() {
        val result = ImportFakes.result(failed = 2, deleteFailed = 2)
        assertThat(result.copyFailures.map { it.reason }).containsExactly(FailureReason.VERIFY_FAILED, FailureReason.READ_ERROR)
        assertThat(result.deleteFailures.map { it.reason }).containsExactly(FailureReason.SOURCE_DELETE_FAILED, FailureReason.SOURCE_DELETE_FAILED)
        assertThat(result.hasProblems).isTrue()
        assertThat(ImportFakes.result(failed = 0, deleteFailed = 0).hasProblems).isFalse()
    }

    @Test fun reviewWork() {
        val idle = AnalysisStatus()
        assertThat((null as ReviewCounts?).hasWork(idle)).isFalse()
        assertThat(ReviewCounts().hasWork(idle)).isFalse()
        assertThat(ReviewCounts().hasWork(AnalysisStatus(running = true))).isTrue()
        assertThat(ReviewCounts(videos = 1).hasWork(idle)).isTrue()
    }

    @Test fun durationParts_rounds() {
        assertThat(durationParts(252_000)).isEqualTo(DurationParts(0, 4, 12))
        assertThat(durationParts(3_725_400)).isEqualTo(DurationParts(1, 2, 5))
        assertThat(durationParts(-5)).isEqualTo(DurationParts(0, 0, 0))
    }
}
