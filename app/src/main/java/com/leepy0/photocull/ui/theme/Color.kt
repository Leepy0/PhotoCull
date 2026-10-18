package com.leepy0.photocull.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// 색은 이 파일에서만 정의한다. 화면 코드는 MaterialTheme.colorScheme / LocalStatusColors만 쓴다.
// 회색조 중심 + 강조 1색(파랑) + 상태 3색(정상/경고/오류).

// iOS system colors. 그룹 배경 #F2F2F7 위에 흰 셀, 다크는 검정 위에 #1C1C1E 셀.
private val Blue = Color(0xFF007AFF)
private val BlueDark = Color(0xFF0A84FF)
private val Red = Color(0xFFFF3B30)
private val RedDark = Color(0xFFFF453A)

internal val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E8FF),
    onPrimaryContainer = Color(0xFF003A80),
    secondary = Color(0x993C3C43),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE5E5EA),
    onSecondaryContainer = Color(0xFF000000),
    tertiary = Color(0x4D3C3C43),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE5E5EA),
    onTertiaryContainer = Color(0xFF000000),
    error = Red,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE1DF),
    onErrorContainer = Color(0xFF8C1007),
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFF2F2F7),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFE5E5EA),
    onSurfaceVariant = Color(0x993C3C43), // secondary label
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF), // 셀
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFE5E5EA),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD1D1D6),
    outline = Color(0x4A3C3C43), // separator
    outlineVariant = Color(0x4A3C3C43),
    inverseSurface = Color(0xFF1C1C1E),
    inverseOnSurface = Color(0xFFFFFFFF),
    inversePrimary = BlueDark,
    scrim = Color(0xFF000000),
)

internal val DarkColors = darkColorScheme(
    primary = BlueDark,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF0A3A73),
    onPrimaryContainer = Color(0xFFD6E8FF),
    secondary = Color(0x99EBEBF5),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF2C2C2E),
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0x4DEBEBF5),
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF2C2C2E),
    onTertiaryContainer = Color(0xFFFFFFFF),
    error = RedDark,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF5C1A16),
    onErrorContainer = Color(0xFFFFD9D6),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0x99EBEBF5),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF1C1C1E),
    surfaceContainer = Color(0xFF1C1C1E), // 셀
    surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = Color(0xFF3A3A3C),
    surfaceBright = Color(0xFF2C2C2E),
    surfaceDim = Color(0xFF000000),
    outline = Color(0xA6545458),
    outlineVariant = Color(0xA6545458),
    inverseSurface = Color(0xFFFFFFFF),
    inverseOnSurface = Color(0xFF000000),
    inversePrimary = Blue,
    scrim = Color(0xFF000000),
)

/** 상태 색. 항상 아이콘·텍스트와 함께 쓴다(색만으로 의미 전달 금지). 오류는 colorScheme.error를 쓴다. */
@Immutable
data class StatusColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

internal val LightStatusColors = StatusColors(
    success = Color(0xFF34C759),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFDDF6E4),
    onSuccessContainer = Color(0xFF0B3D1A),
    warning = Color(0xFFFF9500),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFEBCC),
    onWarningContainer = Color(0xFF5C3600),
)

internal val DarkStatusColors = StatusColors(
    success = Color(0xFF30D158),
    onSuccess = Color(0xFF000000),
    successContainer = Color(0xFF0F3D1D),
    onSuccessContainer = Color(0xFFDDF6E4),
    warning = Color(0xFFFF9F0A),
    onWarning = Color(0xFF000000),
    warningContainer = Color(0xFF4A2E00),
    onWarningContainer = Color(0xFFFFEBCC),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

/** 사진 뷰어는 테마와 상관없이 어두운 배경(사진 색 판단을 방해하지 않게) */
object ViewerColors {
    val background = Color(0xFF000000)
    val scrim = Color(0xB3000000)
    val onScrim = Color(0xFFFFFFFF)
    val onScrimMuted = Color(0xFFC4C6CF)
}
