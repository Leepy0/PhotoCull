package com.leepy0.photocull.ui.importer

import com.leepy0.photocull.importer.ImportState
import com.leepy0.photocull.storage.CardState

/**
 * 카드가 연결되면 가져올 사진을 미리 확인(scan)할지 정한다. 홈·가져오기 화면이 같은 규칙을 쓴다.
 *
 * - 카드가 새로 연결(또는 폴더 승인)되면 한 번 확인한다. 가져오기 상태가 Idle이거나 지난 오류(Failed)일 때만.
 * - 같은 카드에서 가져오기·확인을 한 뒤 결과를 닫아 Idle로 돌아오면 한 번 더 확인한다(남은 파일 수가 바뀐다).
 * - 확인이 실패해 Failed에 머무는 동안에는 다시 확인하지 않는다(자동 재시도 반복 방지). 사용자가 [다시 확인]을 누른다.
 * - 가져오는 중·요약을 보여주는 중·결과를 보여주는 중에는 건드리지 않는다.
 */
class AutoScanPolicy {
    /** 지금 연결된 카드 중 이미 처리한 볼륨 */
    private var handledVolumeId: String? = null

    /** 같은 카드에서 상태가 움직였음(확인·가져오기·결과·오류). Idle로 돌아오면 다시 확인한다. */
    private var dirty = false

    fun shouldScan(card: CardState, import: ImportState): Boolean {
        val volume = (card as? CardState.Ready)?.volume
        if (volume == null) {
            handledVolumeId = null
            dirty = false
            return false
        }
        if (handledVolumeId != volume.id) {
            handledVolumeId = volume.id
            dirty = import !is ImportState.Idle && import !is ImportState.Ready
            return when (import) {
                ImportState.Idle, is ImportState.Failed -> {
                    dirty = false
                    true
                }
                else -> false
            }
        }
        if (import is ImportState.Idle) {
            if (!dirty) return false
            dirty = false
            return true
        }
        if (import !is ImportState.Ready) dirty = true
        return false
    }
}
