package com.leepy0.photocull.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// 색은 이 파일에서만 정의한다. 화면 코드는 MaterialTheme.colorScheme / LocalStatusColors만 쓴다.
// 회색조 중심 + 강조 1색(파랑) + 상태 3색(정상/경고/오류).

internal val LightColors = lightColorScheme(
    primary = Color(0xFF2C5BC7),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE4FF),
    onPrimaryContainer = Color(0xFF0A1F55),
    secondary = Color(0xFF565E71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF131C2B),
    tertiary = Color(0xFF565E71),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDAE2F9),
    onTertiaryContainer = Color(0xFF131C2B),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF8F9FB),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFF8F9FB),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE0E2E8),
    onSurfaceVariant = Color(0xFF44474E),
    surfaceTint = Color(0xFF2C5BC7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F3F6),
    surfaceContainer = Color(0xFFECEEF1),
    surfaceContainerHigh = Color(0xFFE6E8EB),
    surfaceContainerHighest = Color(0xFFE1E2E5),
    surfaceBright = Color(0xFFF8F9FB),
    surfaceDim = Color(0xFFD9DADD),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6CF),
    inverseSurface = Color(0xFF2F3033),
    inverseOnSurface = Color(0xFFF1F0F4),
    inversePrimary = Color(0xFFB2C5FF),
    scrim = Color(0xFF000000),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFB2C5FF),
    onPrimary = Color(0xFF002B74),
    primaryContainer = Color(0xFF224499),
    onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFFBEC6DC),
    onSecondary = Color(0xFF283141),
    secondaryContainer = Color(0xFF3E4759),
    onSecondaryContainer = Color(0xFFDAE2F9),
    tertiary = Color(0xFFBEC6DC),
    onTertiary = Color(0xFF283141),
    tertiaryContainer = Color(0xFF3E4759),
    onTertiaryContainer = Color(0xFFDAE2F9),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111316),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF111316),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF44474E),
    onSurfaceVariant = Color(0xFFC4C6CF),
    surfaceTint = Color(0xFFB2C5FF),
    surfaceContainerLowest = Color(0xFF0C0E11),
    surfaceContainerLow = Color(0xFF1A1C1F),
    surfaceContainer = Color(0xFF1E2023),
    surfaceContainerHigh = Color(0xFF282A2D),
    surfaceContainerHighest = Color(0xFF333538),
    surfaceBright = Color(0xFF37393C),
    surfaceDim = Color(0xFF111316),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474E),
    inverseSurface = Color(0xFFE2E2E6),
    inverseOnSurface = Color(0xFF2F3033),
    inversePrimary = Color(0xFF2C5BC7),
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
    success = Color(0xFF1E7B45),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFCDEFD8),
    onSuccessContainer = Color(0xFF00210F),
    warning = Color(0xFF8A5300),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFDDB3),
    onWarningContainer = Color(0xFF2C1600),
)

internal val DarkStatusColors = StatusColors(
    success = Color(0xFF7FD99F),
    onSuccess = Color(0xFF00391C),
    successContainer = Color(0xFF00522B),
    onSuccessContainer = Color(0xFFCDEFD8),
    warning = Color(0xFFFFB95C),
    onWarning = Color(0xFF492900),
    warningContainer = Color(0xFF693D00),
    onWarningContainer = Color(0xFFFFDDB3),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

/** 사진 뷰어는 테마와 상관없이 어두운 배경(사진 색 판단을 방해하지 않게) */
object ViewerColors {
    val background = Color(0xFF000000)
    val scrim = Color(0xB3000000)
    val onScrim = Color(0xFFFFFFFF)
    val onScrimMuted = Color(0xFFC4C6CF)
}
