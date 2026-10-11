package com.leepy0.photocull.storage

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

/** 카드 원본에 접근하는 방법. DIRECT = 파일 경로(/storage/XXXX-XXXX), SAF = 사용자가 승인한 문서 트리 */
enum class CardAccess { DIRECT, SAF }

data class CardVolume(
    /** StorageVolume UUID(예: "3A1B-27F0"). 카드를 포맷하면 바뀐다. */
    val id: String,
    /** 사용자에게 보여줄 이름(볼륨 설명 또는 라벨) */
    val label: String,
    /** DIRECT 접근 시 루트 경로 */
    val rootPath: String?,
    /** SAF 접근 시 승인된 트리 URI */
    val treeUri: String?,
    val access: CardAccess,
)

sealed interface CardState {
    /** 모든 파일 접근 권한이 없어 카드를 확인할 수 없음 */
    data object NoPermission : CardState

    data object NotConnected : CardState

    /** 이동식 저장소가 연결됐지만 DCIM 폴더나 사진·영상이 없음 */
    data class NotCameraCard(val label: String) : CardState

    /** 파일 경로로 읽을 수 없어 폴더 승인(SAF)이 필요함 */
    data class NeedsAccess(val volumeId: String, val label: String) : CardState

    data class Ready(val volume: CardVolume) : CardState
}

/** 연결된 카드의 상태를 실시간으로 알려준다. 앱 단위 싱글턴. */
interface CardMonitor {
    val state: StateFlow<CardState>

    /** 권한 변경, 화면 복귀 등에서 다시 확인 */
    fun refresh()

    /** [CardState.NeedsAccess]일 때 해당 볼륨 폴더 승인 화면을 여는 인텐트 */
    fun createAccessIntent(volumeId: String): Intent?

    /** 폴더 승인 결과를 저장(영구 권한)하고 상태를 갱신 */
    fun onAccessGranted(volumeId: String, treeUri: Uri)
}
