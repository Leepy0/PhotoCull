package com.leepy0.photocull.ui.update

import androidx.compose.runtime.Composable

/**
 * 업데이트 바텀시트. [visible]이면 현재 업데이트 상태(확인/받기/설치)를 보여준다.
 * 업데이트 직후 첫 실행이면 visible과 상관없이 "vX로 업데이트됨" 변경 내용을 한 번 보여준다.
 */
// TODO(update): 실제 구현으로 교체
@Composable
fun UpdateSheetHost(visible: Boolean, onDismiss: () -> Unit) {
}
