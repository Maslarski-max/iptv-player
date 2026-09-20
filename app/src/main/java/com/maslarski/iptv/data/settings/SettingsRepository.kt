package com.maslarski.iptv.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class AspectRatioMode { FIT, RATIO_16_9, RATIO_4_3, ZOOM, STRETCH }

data class AppSettings(
    val languageTag: String = "",
    val parentalPinHash: String? = null,
    val autoUpdateOnLaunch: Boolean = true,
    val hardwareAcceleration: Boolean = true,
    val aspectRatio: AspectRatioMode = AspectRatioMode.FIT,
    val epgRetentionDays: Int = 7,
    val tmdbApiKey: String = "",
) {
    val isParentalEnabled: Boolean get() = parentalPinHash != null
}

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private object Keys {
        val LANGUAGE = stringPreferencesKey("language")
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val AUTO_UPDATE = booleanPreferencesKey("auto_update")
        val HW_ACCEL = booleanPreferencesKey("hw_accel")
        val ASPECT = stringPreferencesKey("aspect")
        val EPG_DAYS = intPreferencesKey("epg_days")
        val TMDB_KEY = stringPreferencesKey("tmdb_api_key")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { p ->
        AppSettings(
            languageTag = p[Keys.LANGUAGE] ?: "",
            parentalPinHash = p[Keys.PIN_HASH],
            autoUpdateOnLaunch = p[Keys.AUTO_UPDATE] ?: true,
            hardwareAcceleration = p[Keys.HW_ACCEL] ?: true,
            aspectRatio = p[Keys.ASPECT]?.let { runCatching { AspectRatioMode.valueOf(it) }.getOrNull() } ?: AspectRatioMode.FIT,
            epgRetentionDays = p[Keys.EPG_DAYS] ?: 7,
            tmdbApiKey = p[Keys.TMDB_KEY] ?: "",
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setLanguage(tag: String) = context.settingsStore.edit { it[Keys.LANGUAGE] = tag }
    suspend fun setAutoUpdate(enabled: Boolean) = context.settingsStore.edit { it[Keys.AUTO_UPDATE] = enabled }
    suspend fun setHardwareAcceleration(enabled: Boolean) = context.settingsStore.edit { it[Keys.HW_ACCEL] = enabled }
    suspend fun setAspectRatio(mode: AspectRatioMode) = context.settingsStore.edit { it[Keys.ASPECT] = mode.name }
    suspend fun setEpgRetentionDays(days: Int) = context.settingsStore.edit { it[Keys.EPG_DAYS] = days }
    suspend fun setTmdbApiKey(key: String) = context.settingsStore.edit { it[Keys.TMDB_KEY] = key.trim() }

    suspend fun setPin(pin: String?) = context.settingsStore.edit {
        if (pin == null) it.remove(Keys.PIN_HASH) else it[Keys.PIN_HASH] = hash(pin)
    }

    suspend fun verifyPin(pin: String): Boolean = current().parentalPinHash == hash(pin)

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("iptv:$pin".toByteArray()).joinToString("") { "%02x".format(it) }
}
