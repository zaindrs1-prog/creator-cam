package com.creatorcam.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.creatorcam.app.compose.DualLayout
import com.creatorcam.app.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "creatorcam")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val LAYOUT = stringPreferencesKey("default_layout")
        val RESOLUTION = stringPreferencesKey("resolution")
        val FRAME_RATE = stringPreferencesKey("frame_rate")
        val MIRROR_FRONT = booleanPreferencesKey("mirror_front")
        val STABILIZATION = stringPreferencesKey("stabilization")
        val DEFAULT_MODE = stringPreferencesKey("default_mode")
        val MIC = booleanPreferencesKey("mic_enabled")
        val COUNTDOWN = intPreferencesKey("countdown_seconds")
        val KEEP_SOURCES = booleanPreferencesKey("keep_sources")
        val WATERMARK = booleanPreferencesKey("watermark_enabled")
        val WATERMARK_TEXT = stringPreferencesKey("watermark_text")
        val DATE_OVERLAY = booleanPreferencesKey("date_overlay")
        val THEME = stringPreferencesKey("theme")
    }

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { e ->
            Logger.w("Settings", "Failed to read settings, using defaults", e)
            emit(androidx.datastore.preferences.core.emptyPreferences())
        }
        .map { p ->
            AppSettings(
                defaultLayout = p.enum(Keys.LAYOUT, DualLayout.SPLIT_50_50),
                resolution = p.enum(Keys.RESOLUTION, VideoResolution.AUTO),
                frameRate = p.enum(Keys.FRAME_RATE, FrameRate.AUTO),
                mirrorFront = p[Keys.MIRROR_FRONT] ?: true,
                stabilization = p.enum(Keys.STABILIZATION, StabilizationMode.STANDARD),
                defaultMode = p.enum(Keys.DEFAULT_MODE, CameraMode.DUAL),
                micEnabled = p[Keys.MIC] ?: true,
                countdownSeconds = (p[Keys.COUNTDOWN] ?: 3).coerceIn(0, 10),
                keepSourceFiles = p[Keys.KEEP_SOURCES] ?: false,
                watermarkEnabled = p[Keys.WATERMARK] ?: false,
                watermarkText = (p[Keys.WATERMARK_TEXT] ?: "CreatorCam").take(32),
                dateTimeOverlay = p[Keys.DATE_OVERLAY] ?: false,
                theme = p.enum(Keys.THEME, AppTheme.DARK),
            )
        }

    suspend fun setLayout(layout: DualLayout) =
        context.dataStore.edit { it[Keys.LAYOUT] = layout.name }

    suspend fun setResolution(resolution: VideoResolution) =
        context.dataStore.edit { it[Keys.RESOLUTION] = resolution.name }

    suspend fun setFrameRate(frameRate: FrameRate) =
        context.dataStore.edit { it[Keys.FRAME_RATE] = frameRate.name }

    suspend fun setMirrorFront(enabled: Boolean) =
        context.dataStore.edit { it[Keys.MIRROR_FRONT] = enabled }

    suspend fun setStabilization(mode: StabilizationMode) =
        context.dataStore.edit { it[Keys.STABILIZATION] = mode.name }

    suspend fun setDefaultMode(mode: CameraMode) =
        context.dataStore.edit { it[Keys.DEFAULT_MODE] = mode.name }

    suspend fun setMicEnabled(enabled: Boolean) =
        context.dataStore.edit { it[Keys.MIC] = enabled }

    suspend fun setCountdown(seconds: Int) =
        context.dataStore.edit { it[Keys.COUNTDOWN] = seconds.coerceIn(0, 10) }

    suspend fun setKeepSources(enabled: Boolean) =
        context.dataStore.edit { it[Keys.KEEP_SOURCES] = enabled }

    suspend fun setWatermark(enabled: Boolean, text: String) =
        context.dataStore.edit {
            it[Keys.WATERMARK] = enabled
            it[Keys.WATERMARK_TEXT] = text.take(32)
        }

    suspend fun setDateOverlay(enabled: Boolean) =
        context.dataStore.edit { it[Keys.DATE_OVERLAY] = enabled }

    suspend fun setTheme(theme: AppTheme) =
        context.dataStore.edit { it[Keys.THEME] = theme.name }

    private inline fun <reified T : Enum<T>> Preferences.enum(
        key: Preferences.Key<String>,
        default: T,
    ): T = get(key)?.let { name ->
        runCatching { enumValueOf<T>(name) }.getOrDefault(default)
    } ?: default
}
