package com.leepy0.photocull

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leepy0.photocull.data.settings.AppSettings
import com.leepy0.photocull.ui.LocalAppContainer
import com.leepy0.photocull.ui.nav.AppNavHost
import com.leepy0.photocull.ui.theme.PhotoCullTheme
import com.leepy0.photocull.ui.theme.isAppInDarkTheme
import com.leepy0.photocull.util.Permissions

class MainActivity : ComponentActivity() {

    private var pendingDestination by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = PhotoCullApp.container(this)
        if (savedInstanceState == null) pendingDestination = intent.getStringExtra(AppDestinations.EXTRA_DESTINATION)
        // 필수 권한이 없으면 시작 화면부터
        val startOnboarding = !Permissions.hasAllFilesAccess()

        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            // 상태·내비게이션 바 아이콘 색을 시스템 모드가 아니라 앱 테마(설정의 라이트/다크)에 맞춘다.
            // 기본 enableEdgeToEdge()는 시스템 모드를 따라서, 라이트 폰에서 앱만 다크면 어두운 바탕에 어두운 아이콘이 된다.
            val dark = isAppInDarkTheme(settings.themeMode)
            DisposableEffect(dark) {
                applySystemBarStyle(dark)
                onDispose {}
            }
            CompositionLocalProvider(LocalAppContainer provides container) {
                PhotoCullTheme(themeMode = settings.themeMode) {
                    AppNavHost(
                        startOnboarding = startOnboarding,
                        pendingDestination = pendingDestination,
                        onDestinationConsumed = { pendingDestination = null },
                    )
                }
            }
        }
    }

    /** 바는 투명(내용이 뒤로 그려짐), 아이콘은 [dark]면 밝게. 3버튼 내비게이션의 대비 막은 시스템이 아이콘 색에 맞춰 그린다. */
    private fun applySystemBarStyle(dark: Boolean) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(AppDestinations.EXTRA_DESTINATION)?.let { pendingDestination = it }
    }

    override fun onResume() {
        super.onResume()
        // 설정 화면에서 권한을 바꾸고 돌아온 경우 등
        PhotoCullApp.container(this).cardMonitor.refresh()
    }
}
