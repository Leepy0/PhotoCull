package com.leepy0.photocull.ui.importer

import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.importer.ImportError
import com.leepy0.photocull.importer.ImportState
import com.leepy0.photocull.storage.CardState
import org.junit.Test

class AutoScanPolicyTest {
    private val policy = AutoScanPolicy()
    private val card = CardState.Ready(ImportFakes.volume)
    private val otherCard = CardState.Ready(ImportFakes.volume.copy(id = "9999-0000", label = "OTHER"))
    private val summary = ImportState.Ready(ImportFakes.summary())

    private fun scan(c: CardState, i: ImportState) = policy.shouldScan(c, i)

    @Test fun noCard_neverScans() {
        assertThat(scan(CardState.NotConnected, ImportState.Idle)).isFalse()
        assertThat(scan(CardState.NoPermission, ImportState.Idle)).isFalse()
        assertThat(scan(CardState.NeedsAccess("v", "l"), ImportState.Idle)).isFalse()
        assertThat(scan(CardState.NotCameraCard("l"), ImportState.Idle)).isFalse()
    }

    @Test fun newCard_scansOnce() {
        assertThat(scan(card, ImportState.Idle)).isTrue()
        assertThat(scan(card, ImportState.Scanning)).isFalse()
        assertThat(scan(card, summary)).isFalse()
        assertThat(scan(card, summary)).isFalse()
    }

    @Test fun sameCardIdleAgain_withoutActivity_doesNotScan() {
        assertThat(scan(card, ImportState.Idle)).isTrue()
        // 아직 상태가 바뀌기 전 같은 값이 다시 와도 두 번 확인하지 않는다
        assertThat(scan(card, ImportState.Idle)).isFalse()
    }

    @Test fun afterImportAndReset_rescansOnce() {
        assertThat(scan(card, ImportState.Idle)).isTrue()
        scan(card, ImportState.Scanning)
        scan(card, summary)
        scan(card, ImportState.Running(ImportFakes.progress()))
        assertThat(scan(card, ImportState.Finished(ImportFakes.result()))).isFalse()
        assertThat(scan(card, ImportState.Idle)).isTrue()
        assertThat(scan(card, ImportState.Idle)).isFalse()
    }

    @Test fun failedScan_isNotRetriedAutomatically() {
        assertThat(scan(card, ImportState.Idle)).isTrue()
        scan(card, ImportState.Scanning)
        repeat(3) { assertThat(scan(card, ImportState.Failed(ImportError.Unexpected("x")))).isFalse() }
    }

    @Test fun failedThenUserResets_rescans() {
        scan(card, ImportState.Idle)
        scan(card, ImportState.Scanning)
        scan(card, ImportState.Failed(ImportError.Unexpected("x")))
        assertThat(scan(card, ImportState.Idle)).isTrue()
    }

    @Test fun cardRemovedAndReconnected_scansAgain() {
        assertThat(scan(card, ImportState.Idle)).isTrue()
        scan(card, summary)
        assertThat(scan(CardState.NotConnected, ImportState.Idle)).isFalse()
        assertThat(scan(card, ImportState.Idle)).isTrue()
    }

    @Test fun reconnectedAfterCardRemovedError_scansToResume() {
        scan(card, ImportState.Running(ImportFakes.progress()))
        val removed = ImportState.Failed(ImportError.CardRemoved(120, 300))
        assertThat(scan(CardState.NotConnected, removed)).isFalse()
        assertThat(scan(card, removed)).isTrue()
    }

    @Test fun folderApproved_scansFromNoPermissionFailure() {
        val failed = ImportState.Failed(ImportError.NoPermission)
        assertThat(scan(CardState.NeedsAccess(card.volume.id, "EOS"), failed)).isFalse()
        assertThat(scan(card, failed)).isTrue()
    }

    @Test fun differentCard_scans() {
        assertThat(scan(card, ImportState.Idle)).isTrue()
        scan(card, summary)
        assertThat(scan(otherCard, ImportState.Idle)).isTrue()
    }

    @Test fun cardConnectedWhileAnotherScreenScans_doesNotScanTwice() {
        assertThat(scan(card, ImportState.Scanning)).isFalse()
        assertThat(scan(card, summary)).isFalse()
    }

    @Test fun openedDuringImport_waitsForReset() {
        assertThat(scan(card, ImportState.Running(ImportFakes.progress()))).isFalse()
        assertThat(scan(card, ImportState.Finished(ImportFakes.result()))).isFalse()
        assertThat(scan(card, ImportState.Idle)).isTrue()
    }

    @Test fun scanCancelled_rescansOnce() {
        assertThat(scan(card, ImportState.Idle)).isTrue()
        scan(card, ImportState.Scanning)
        assertThat(scan(card, ImportState.Idle)).isTrue()
    }
}
