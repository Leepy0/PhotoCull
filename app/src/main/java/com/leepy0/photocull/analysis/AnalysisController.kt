package com.leepy0.photocull.analysis

import kotlinx.coroutines.flow.StateFlow

data class AnalysisStatus(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
)

/**
 * 가져온 사진의 선명도·dHash·EXIF, 영상 길이를 계산하고 연사/유사 그룹을 다시 묶는다.
 * 앱 프로세스 범위 코루틴에서 돈다. 가져오는 동안에는 포그라운드 서비스가 프로세스를 유지한다.
 */
interface AnalysisController {
    val status: StateFlow<AnalysisStatus>

    /** 분석 안 된 항목이 있으면 처리 시작. 이미 실행 중이면 새 항목을 이어서 처리한다. */
    fun kick()

    /** 설정(연사 간격 등)이 바뀌었을 때 그룹만 다시 계산 */
    fun regroup()

    /** 현재 대기 중인 분석이 모두 끝날 때까지 대기 */
    suspend fun awaitIdle()
}
