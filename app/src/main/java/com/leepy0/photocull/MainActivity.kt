package com.leepy0.photocull

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.leepy0.photocull.data.settings.AppSettings
import com.leepy0.photocull.ui.LocalAppContainer
import com.leepy0.photocull.ui.nav.AppNavHost
import com.leepy0.photocull.ui.theme.PhotoCullTheme
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
