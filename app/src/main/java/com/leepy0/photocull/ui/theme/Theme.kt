package com.leepy0.photocull.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import com.leepy0.photocull.data.settings.ThemeMode

// radius 3종: 4 / 8 / 16
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

// 폰트 1종(시스템), 크기는 M3 type scale 그대로.
// 한국어를 낱말 중간("확인하/세요")이 아니라 어절 단위로 줄바꿈한다(Android 13+, 한국어 로캘에서 동작).
private val AppTypography = Typography().withPhraseLineBreaks()

private fun Typography.withPhraseLineBreaks(): Typography {
    val lineBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)
    fun TextStyle.phrases() = copy(lineBreak = lineBreak)
    return copy(
        displayLarge = displayLarge.phrases(),
        displayMedium = displayMedium.phrases(),
        displaySmall = displaySmall.phrases(),
        headlineLarge = headlineLarge.phrases(),
        headlineMedium = headlineMedium.phrases(),
        headlineSmall = headlineSmall.phrases(),
        titleLarge = titleLarge.phrases(),
        titleMedium = titleMedium.phrases(),
        titleSmall = titleSmall.phrases(),
        bodyLarge = bodyLarge.phrases(),
        bodyMedium = bodyMedium.phrases(),
        bodySmall = bodySmall.phrases(),
        labelLarge = labelLarge.phrases(),
        labelMedium = labelMedium.phrases(),
        labelSmall = labelSmall.phrases(),
    )
}

@Composable
fun isAppInDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** 업무 앱이라 dynamic color를 쓰지 않고 고정 색상표를 쓴다. */
@Composable
fun PhotoCullTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = isAppInDarkTheme(themeMode)
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatusColors else LightStatusColors) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
