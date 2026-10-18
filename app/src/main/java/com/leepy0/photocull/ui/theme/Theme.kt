package com.leepy0.photocull.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.sp
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

// 폰트 1종(시스템 sans), iOS text style 크기: LargeTitle 34 / Title 28·22 / Headline 17 / Body 17 / Callout 16 / Subhead 15 / Footnote 13 / Caption 12·11.
// 한국어를 낱말 중간("확인하/세요")이 아니라 어절 단위로 줄바꿈한다(Android 13+, 한국어 로캘에서 동작).
private fun ios(size: Int, weight: FontWeight, line: Int, tracking: Double = 0.0) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp,
)

private val AppTypography = Typography(
    displayLarge = ios(34, FontWeight.Bold, 41, 0.37),
    displayMedium = ios(34, FontWeight.Bold, 41, 0.37),
    displaySmall = ios(28, FontWeight.Bold, 34, 0.36),
    headlineLarge = ios(34, FontWeight.Bold, 41, 0.37),
    headlineMedium = ios(28, FontWeight.Bold, 34, 0.36),
    headlineSmall = ios(22, FontWeight.Bold, 28, 0.35),
    titleLarge = ios(22, FontWeight.SemiBold, 28, 0.35),
    titleMedium = ios(17, FontWeight.SemiBold, 22, -0.4),
    titleSmall = ios(15, FontWeight.SemiBold, 20, -0.24),
    bodyLarge = ios(17, FontWeight.Normal, 22, -0.4),
    bodyMedium = ios(15, FontWeight.Normal, 20, -0.24),
    bodySmall = ios(13, FontWeight.Normal, 18, -0.08),
    labelLarge = ios(16, FontWeight.Medium, 21, -0.32),
    labelMedium = ios(12, FontWeight.Medium, 16),
    labelSmall = ios(11, FontWeight.Medium, 13, 0.07),
).withPhraseLineBreaks()

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
    // iOS는 물결 대신 눌린 면이 살짝 어두워진다: 약한 하이라이트 알파
    val highlight = RippleConfiguration(rippleAlpha = RippleAlpha(0.10f, 0.10f, 0.10f, 0.10f))
    CompositionLocalProvider(
        LocalStatusColors provides if (dark) DarkStatusColors else LightStatusColors,
        LocalRippleConfiguration provides highlight,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
