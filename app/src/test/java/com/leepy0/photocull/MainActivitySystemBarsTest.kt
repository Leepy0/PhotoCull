package com.leepy0.photocull

import android.os.Looper
import androidx.core.view.WindowCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.data.settings.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import java.time.Duration

/**
 * 상태·내비게이션 바 아이콘 색은 시스템 모드가 아니라 앱 테마 설정을 따른다.
 * (Robolectric 기본 = 시스템 라이트 모드)
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MainActivitySystemBarsTest {

    private val container get() = PhotoCullApp.container(ApplicationProvider.getApplicationContext())

    @After
    fun tearDown() = runBlocking { container.settings.setThemeMode(ThemeMode.SYSTEM) }

    @Test
    fun barIconsFollowAppThemeNotSystem() {
        // Environment.isExternalStorageManager()가 외부 저장소 경로를 찾을 수 있게(Robolectric 기본은 비어 있음)
        ShadowEnvironment.addExternalDir("emulated-0")
        runBlocking { container.settings.setThemeMode(ThemeMode.DARK) }
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)

        // 라이트 폰 + 앱 다크: 어두운 바탕이므로 아이콘은 밝게(= light appearance 아님)
        awaitMain { !bars.isAppearanceLightStatusBars }
        assertThat(bars.isAppearanceLightStatusBars).isFalse()
        assertThat(bars.isAppearanceLightNavigationBars).isFalse()

        // 설정을 라이트로 바꾸면 바로 어두운 아이콘으로
        runBlocking { container.settings.setThemeMode(ThemeMode.LIGHT) }
        awaitMain { bars.isAppearanceLightStatusBars }
        assertThat(bars.isAppearanceLightStatusBars).isTrue()
        assertThat(bars.isAppearanceLightNavigationBars).isTrue()
    }

    /** DataStore 값이 다른 스레드에서 와서 재구성될 때까지 메인 루퍼를 돌린다 */
    private fun awaitMain(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(20)
        }
    }
}
