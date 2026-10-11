package com.leepy0.photocull.analysis

import com.leepy0.photocull.data.settings.BlurSensitivity

/**
 * 흐림 판정 기준값을 한 곳에서 관리한다.
 * 점수 = 긴 변 약 3000px로 축소한 그레이스케일 영상을 6x4 타일로 나눠 구한 Laplacian 분산의 상위 3개 평균.
 */
object BlurThresholds {
    /** 단독 사진: 점수가 이 값 미만이면 흐림 후보 */
    fun threshold(sensitivity: BlurSensitivity): Double = when (sensitivity) {
        BlurSensitivity.LOW -> 40.0
        BlurSensitivity.NORMAL -> 80.0
        BlurSensitivity.HIGH -> 150.0
    }

    /** 그룹 안: 그룹 최고 점수의 이 비율 미만이면 상대적으로 흐린 사진 */
    const val GROUP_RELATIVE_RATIO = 0.6
}
