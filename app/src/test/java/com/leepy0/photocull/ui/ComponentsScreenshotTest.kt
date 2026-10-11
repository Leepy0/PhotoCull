package com.leepy0.photocull.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.leepy0.photocull.ui.components.EmptyState
import com.leepy0.photocull.ui.components.ErrorState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ComponentsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyState() = compose.capture("components_empty") {
        EmptyState(Icons.Outlined.PhotoLibrary, "검토할 사진이 없습니다", "카드에서 사진을 가져오면 흐린 사진과 연사를 골라 보여줍니다", "가져오기", {})
    }

    @Test fun errorStateDark() = compose.capture("components_error_dark", dark = true) {
        ErrorState("카드를 읽을 수 없습니다", "카드가 분리되었거나 손상되었습니다", "카드를 다시 꽂고 다시 시도하세요", onRetry = {})
    }
}
