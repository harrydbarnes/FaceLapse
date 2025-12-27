package com.facelapse.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    companion object {
        val DATE_OVERLAY_ENABLED = booleanPreferencesKey("date_overlay_enabled")
        val DATE_FONT_SIZE = intPreferencesKey("date_font_size")
        val DATE_FORMAT = stringPreferencesKey("date_format") // e.g., "dd.MM.yyyy"
        val SHOW_DAY_OF_WEEK = booleanPreferencesKey("show_day_of_week")
        val DEFAULT_FPS_FLOAT = floatPreferencesKey("default_fps_v2") // Using v2 for Float migration
        val DEFAULT_EXPORT_GIF = booleanPreferencesKey("default_export_gif")

        // Removed old Int key usage internally but kept if needed for migration logic (not needed here)
    }

    val isDateOverlayEnabled: Flow<Boolean> = dataStore.data.map { it[DATE_OVERLAY_ENABLED] ?: true }
    val dateFontSize: Flow<Int> = dataStore.data.map { it[DATE_FONT_SIZE] ?: 40 }
    val dateFormat: Flow<String> = dataStore.data.map { it[DATE_FORMAT] ?: "dd.MM.yyyy" }
    val showDayOfWeek: Flow<Boolean> = dataStore.data.map { it[SHOW_DAY_OF_WEEK] ?: false }
    val defaultFps: Flow<Float> = dataStore.data.map { it[DEFAULT_FPS_FLOAT] ?: 10f }
    val defaultExportGif: Flow<Boolean> = dataStore.data.map { it[DEFAULT_EXPORT_GIF] ?: false }

    val projectDefaults: Flow<Pair<Float, Boolean>> = dataStore.data.map { preferences ->
        Pair(
            preferences[DEFAULT_FPS_FLOAT] ?: 10f,
            preferences[DEFAULT_EXPORT_GIF] ?: false
        )
    }

    suspend fun setDateOverlayEnabled(enabled: Boolean) {
        dataStore.edit { it[DATE_OVERLAY_ENABLED] = enabled }
    }

    suspend fun setDateFontSize(size: Int) {
        dataStore.edit { it[DATE_FONT_SIZE] = size }
    }

    suspend fun setDateFormat(format: String) {
        dataStore.edit { it[DATE_FORMAT] = format }
    }

    suspend fun setShowDayOfWeek(show: Boolean) {
        dataStore.edit { it[SHOW_DAY_OF_WEEK] = show }
    }

    suspend fun setDefaultFps(fps: Float) {
        dataStore.edit { it[DEFAULT_FPS_FLOAT] = fps }
    }

    suspend fun setDefaultExportGif(exportGif: Boolean) {
        dataStore.edit { it[DEFAULT_EXPORT_GIF] = exportGif }
    }
}
