package com.leepy0.photocull.ui.theme

import androidx.compose.ui.unit.dp

/** 간격은 4의 배수만 쓴다. 버튼 사이 8, 컨트롤-라벨 12, 가장자리 16. */
object Spacing {
    val zero = 0.dp
    val xxs = 4.dp
    val xs = 8.dp
    val s = 12.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 32.dp
    val xxl = 48.dp

    /** 최소 터치 타깃 */
    val touchTarget = 44.dp

    /** 큰 주요 버튼 높이(엄지 영역) */
    val primaryButtonHeight = 50.dp

    /** 썸네일 그리드 최소 칸 너비(폴드 펼침에서 자동으로 열 수가 늘어난다) */
    val gridCellMin = 112.dp
    val gridGap = 4.dp

    /** 큰 상태 아이콘 */
    val iconLarge = 48.dp
    val iconMedium = 24.dp
    val iconSmall = 16.dp
}
