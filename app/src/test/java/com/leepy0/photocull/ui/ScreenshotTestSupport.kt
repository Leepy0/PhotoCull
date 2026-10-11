package com.leepy0.photocull.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.github.takahirom.roborazzi.captureRoboImage
import com.leepy0.photocull.data.settings.ThemeMode
import com.leepy0.photocull.ui.theme.PhotoCullTheme

/**
 * 화면 스크린샷 테스트 공통. 결과는 app/build/outputs/roborazzi/<name>.png
 * 실행: ./gradlew :app:testDebugUnitTest --tests '*Screenshot*' -Proborazzi.test.record=true
 *
 * 테스트 클래스에는 아래 애너테이션을 붙인다.
 *   @RunWith(AndroidJUnit4::class)
 *   @GraphicsMode(GraphicsMode.Mode.NATIVE)
 *   @Config(sdk = [35], qualifiers = PHONE)     // 폴드 접힘(커버) 화면 근사
 *   또는 qualifiers = UNFOLDED                  // 폴드 펼침 화면 근사
 *
 * Coil 이미지(MediaThumbnail 등)는 실제 파일 대신 경로별로 다른 가짜 사진으로 그려진다.
 */
const val PHONE = "w392dp-h860dp-xxhdpi"
const val UNFOLDED = "w840dp-h900dp-land-xxhdpi"

fun ComposeContentTestRule.capture(
    name: String,
    dark: Boolean = false,
    content: @Composable () -> Unit,
) {
    setContent { ScreenshotFrame(dark, content) }
    waitForIdle()
    onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
}

@Composable
fun ScreenshotFrame(dark: Boolean = false, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalInspectionMode provides true,
        LocalAsyncImagePreviewHandler provides fakePhotoHandler,
    ) {
        PhotoCullTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
            Surface(Modifier.fillMaxSize()) { content() }
        }
    }
}

private val fakePhotoHandler = AsyncImagePreviewHandler { request -> fakePhoto(request.data.toString()).asImage() }

/** 경로 해시로 색이 다른 가짜 사진(하늘 그라데이션 + 피사체 원) */
fun fakePhoto(seed: String, width: Int = 300, height: Int = 200): Bitmap {
    val h = seed.hashCode()
    val hue = ((h ushr 1) % 360).toFloat()
    val top = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.35f, 0.95f))
    val bottom = android.graphics.Color.HSVToColor(floatArrayOf((hue + 40) % 360, 0.6f, 0.45f))
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
    p.shader = null
    p.color = android.graphics.Color.HSVToColor(floatArrayOf((hue + 180) % 360, 0.5f, 0.6f))
    val cx = width * (0.3f + ((h ushr 9) % 40) / 100f)
    c.drawCircle(cx, height * 0.6f, height * 0.22f, p)
    return bmp
}
