package com.leepy0.photocull.data.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * 설정 파일이 깨졌으면(전원 차단 중 쓰기 등) 기본값으로 바꿔 계속한다. 이게 없으면 읽을 때마다 예외가 나서
 * 앱이 시작할 때마다 죽는다. 설정은 잃어도 되는 정보다.
 */
internal val settingsCorruptionHandler = ReplaceFileCorruptionHandler { e ->
    Log.w("Settings", "settings file corrupted; resetting to defaults", e)
    emptyPreferences()
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = settingsCorruptionHandler,
)

class SettingsRepository internal constructor(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.dataStore)

    private object Keys {
        val destFolderName = stringPreferencesKey("dest_folder_name")
        val monthlySubfolders = booleanPreferencesKey("monthly_subfolders")
        val moveMode = booleanPreferencesKey("move_mode")
        val cardNotification = booleanPreferencesKey("card_notification")
        val blurSensitivity = stringPreferencesKey("blur_sensitivity")
        val burstGapMs = longPreferencesKey("burst_gap_ms")
        val similarGapMs = longPreferencesKey("similar_gap_ms")
        val similarMaxDistance = intPreferencesKey("similar_max_distance")
        val shortVideoMs = longPreferencesKey("short_video_ms")
        val trashRetentionDays = intPreferencesKey("trash_retention_days")
        val themeMode = stringPreferencesKey("theme_mode")
        val autoUpdateCheck = booleanPreferencesKey("auto_update_check")
        val lastUpdateCheckAt = longPreferencesKey("last_update_check_at")
        val lastRunVersionCode = intPreferencesKey("last_run_version_code")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
    }

    /** 읽기 오류(IOException)는 기본값으로 대신한다. 그 밖의 예외는 버그이므로 그대로 던진다. */
    val settings: Flow<AppSettings> = store.data
        .catch { e ->
            if (e is IOException) {
                Log.w("Settings", "cannot read settings; using defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { p ->
            val d = AppSettings()
            AppSettings(
                destFolderName = p[Keys.destFolderName] ?: d.destFolderName,
                monthlySubfolders = p[Keys.monthlySubfolders] ?: d.monthlySubfolders,
                moveMode = p[Keys.moveMode] ?: d.moveMode,
                cardNotification = p[Keys.cardNotification] ?: d.cardNotification,
                blurSensitivity = p[Keys.blurSensitivity]?.let { runCatching { BlurSensitivity.valueOf(it) }.getOrNull() } ?: d.blurSensitivity,
                burstGapMs = p[Keys.burstGapMs] ?: d.burstGapMs,
                similarGapMs = p[Keys.similarGapMs] ?: d.similarGapMs,
                similarMaxDistance = p[Keys.similarMaxDistance] ?: d.similarMaxDistance,
                shortVideoMs = p[Keys.shortVideoMs] ?: d.shortVideoMs,
                trashRetentionDays = p[Keys.trashRetentionDays] ?: d.trashRetentionDays,
                themeMode = p[Keys.themeMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
                autoUpdateCheck = p[Keys.autoUpdateCheck] ?: d.autoUpdateCheck,
                lastUpdateCheckAt = p[Keys.lastUpdateCheckAt] ?: d.lastUpdateCheckAt,
                lastRunVersionCode = p[Keys.lastRunVersionCode] ?: d.lastRunVersionCode,
                onboardingDone = p[Keys.onboardingDone] ?: d.onboardingDone,
            )
        }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setDestFolderName(v: String) = edit { it[Keys.destFolderName] = v }
    suspend fun setMonthlySubfolders(v: Boolean) = edit { it[Keys.monthlySubfolders] = v }
    suspend fun setMoveMode(v: Boolean) = edit { it[Keys.moveMode] = v }
    suspend fun setCardNotification(v: Boolean) = edit { it[Keys.cardNotification] = v }
    suspend fun setBlurSensitivity(v: BlurSensitivity) = edit { it[Keys.blurSensitivity] = v.name }
    suspend fun setBurstGapMs(v: Long) = edit { it[Keys.burstGapMs] = v }
    suspend fun setSimilarGapMs(v: Long) = edit { it[Keys.similarGapMs] = v }
    suspend fun setSimilarMaxDistance(v: Int) = edit { it[Keys.similarMaxDistance] = v }
    suspend fun setShortVideoMs(v: Long) = edit { it[Keys.shortVideoMs] = v }
    suspend fun setTrashRetentionDays(v: Int) = edit { it[Keys.trashRetentionDays] = v }
    suspend fun setThemeMode(v: ThemeMode) = edit { it[Keys.themeMode] = v.name }
    suspend fun setAutoUpdateCheck(v: Boolean) = edit { it[Keys.autoUpdateCheck] = v }
    suspend fun setLastUpdateCheckAt(v: Long) = edit { it[Keys.lastUpdateCheckAt] = v }
    suspend fun setLastRunVersionCode(v: Int) = edit { it[Keys.lastRunVersionCode] = v }
    suspend fun setOnboardingDone(v: Boolean) = edit { it[Keys.onboardingDone] = v }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        store.edit { block(it) }
    }
}
