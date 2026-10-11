package com.leepy0.photocull.importer

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.leepy0.photocull.notify.NotificationChannels
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.shadows.ShadowPowerManager

/** 실제 앱 컨테이너·카드 모니터·알림으로 서비스 연결부를 확인한다(카드 없음 경로). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ImportServiceTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() {
        ShadowEnvironment.setExternalStorageDirectory(tmp.newFolder("emulated0").toPath())
        ShadowEnvironment.addExternalDir("emulated0")
        // Environment.isExternalStorageManager()는 앱 op가 기본값이면 권한 허용 여부를 본다
        shadowOf(app).grantPermissions(Manifest.permission.MANAGE_EXTERNAL_STORAGE, Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20)
        }
        error("timed out waiting for $what")
    }

    @Test fun startFromCardNotificationWithoutCardExplainsAndStops() {
        val controller = Robolectric.buildService(ImportService::class.java, ImportService.startFromCardIntent(app))
            .create()
            .startCommand(0, 1)
        val service = shadowOf(controller.get())
        // 시작 즉시 포그라운드(진행 알림)
        assertThat(service.lastForegroundNotificationId).isEqualTo(NotificationChannels.ID_IMPORT_PROGRESS)

        val nm = shadowOf(app.getSystemService(NotificationManager::class.java))
        waitUntil("result notification") { nm.getNotification(NotificationChannels.ID_IMPORT_RESULT) != null }
        val n = nm.getNotification(NotificationChannels.ID_IMPORT_RESULT)
        assertThat(n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()).isEqualTo("카메라 카드를 찾지 못했습니다")
        assertThat(n.channelId).isEqualTo(NotificationChannels.RESULT)
        assertThat(n.contentIntent).isNotNull()

        waitUntil("service stop") { service.isStoppedBySelf }
        assertThat(ShadowPowerManager.getLatestWakeLock()?.isHeld ?: false).isFalse()
    }

    @Test fun cancelWhileIdleStopsImmediately() {
        val controller = Robolectric.buildService(ImportService::class.java, ImportService.cancelIntent(app))
            .create()
            .startCommand(0, 1)
        assertThat(shadowOf(controller.get()).isStoppedBySelf).isTrue()
    }

    @Test fun nullIntentRestartStops() {
        val controller = Robolectric.buildService(ImportService::class.java, null).create()
        controller.get().onStartCommand(null, 0, 1)
        assertThat(shadowOf(controller.get()).isStoppedBySelf).isTrue()
    }
}
