package com.leepy0.photocull.ui.onboarding

import androidx.lifecycle.ViewModel
import com.leepy0.photocull.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 시작 화면 동작. 권한 상태는 시스템에서 읽으므로 화면이 직접 확인한다(화면 복귀 때마다). */
class OnboardingViewModel(
    private val workScope: CoroutineScope,
    private val markDone: suspend () -> Unit,
    private val refreshCard: () -> Unit,
) : ViewModel() {

    constructor(container: AppContainer) : this(
        workScope = container.appScope,
        markDone = { container.settings.setOnboardingDone(true) },
        refreshCard = { container.cardMonitor.refresh() },
    )

    /** [계속]: 시작 완료 기록 + 카드 다시 확인(방금 권한을 받았으므로). 저장은 화면을 떠나도 끝까지 한다. */
    fun complete(onDone: () -> Unit) {
        workScope.launch {
            try {
                markDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 기록 실패는 다음 실행에서 시작 화면을 한 번 더 보는 정도라 무시한다
            }
        }
        refreshCard()
        onDone()
    }
}
