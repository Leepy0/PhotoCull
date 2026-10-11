package com.leepy0.photocull.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** 설정 파일이 깨지거나 읽히지 않아도 앱이 시작마다 죽지 않는다. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SettingsRepositoryTest {

    private lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        dir = File(context.cacheDir, "settings-test").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun corruptedFile_fallsBackToDefaults_andCanBeWrittenAgain() = runBlocking<Unit> {
        val file = File(dir, "settings.preferences_pb").apply { writeBytes(byteArrayOf(0x0A, 0x7F, 0x13, 0x00, 0x55, 0x66)) }
        val store = PreferenceDataStoreFactory.create(
            corruptionHandler = settingsCorruptionHandler,
            scope = scope,
            produceFile = { file },
        )
        val repo = SettingsRepository(store)

        assertThat(repo.current()).isEqualTo(AppSettings())
        repo.setThemeMode(ThemeMode.DARK)
        assertThat(repo.current().themeMode).isEqualTo(ThemeMode.DARK)
    }

    private class FailingStore(private val error: Throwable) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw error }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw error
    }

    @Test
    fun readIoError_usesDefaults() = runBlocking<Unit> {
        val repo = SettingsRepository(FailingStore(IOException("disk error")))
        assertThat(repo.current()).isEqualTo(AppSettings())
    }

    @Test
    fun otherErrors_areNotHidden() {
        val repo = SettingsRepository(FailingStore(IllegalStateException("bug")))
        assertThrows(IllegalStateException::class.java) { runBlocking { repo.current() } }
    }
}
