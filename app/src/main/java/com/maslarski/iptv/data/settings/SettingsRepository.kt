package com.maslarski.iptv.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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

enum class RefreshMode(val periodHours: Long?) {
    MANUAL(null),
    ON_LAUNCH(null),
    EVERY_12H(12),
    EVERY_24H(24),
}

data class LastChannel(val playlistId: Long, val channelId: String, val categoryId: String?, val favoritesOnly: Boolean)

data class AppSettings(
    val languageTag: String = "",
    val parentalPinHash: String? = null,
    val refreshMode: RefreshMode = RefreshMode.ON_LAUNCH,
    val hardwareAcceleration: Boolean = true,
    val aspectRatio: AspectRatioMode = AspectRatioMode.FIT,
    val epgRetentionDays: Int = 7,
    val tmdbApiKey: String = "",
    val launchLastChannel: Boolean = false,
    val lastChannel: LastChannel? = null,
    val trialStartedAt: Long? = null,
    val licenseKey: String? = null,
    val licenseActivatedAt: Long? = null,
    /** Firestore server time minus the local clock, learned from the last server round-trip. */
    val serverClockOffsetMs: Long = 0L,
    /** `remotePlaylistUrl` last applied from the device's Firestore document. */
    val appliedRemotePlaylistUrl: String? = null,
) {
    val isParentalEnabled: Boolean get() = parentalPinHash != null
    val autoUpdateOnLaunch: Boolean get() = refreshMode != RefreshMode.MANUAL
}

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private object Keys {
        val LANGUAGE = stringPreferencesKey("language")
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val REFRESH_MODE = stringPreferencesKey("refresh_mode")
        val HW_ACCEL = booleanPreferencesKey("hw_accel")
        val ASPECT = stringPreferencesKey("aspect")
        val EPG_DAYS = intPreferencesKey("epg_days")
        val TMDB_KEY = stringPreferencesKey("tmdb_api_key")
        val LAUNCH_LAST = booleanPreferencesKey("launch_last_channel")
        val LAST_PLAYLIST = longPreferencesKey("last_channel_playlist")
        val LAST_CHANNEL = stringPreferencesKey("last_channel_id")
        val LAST_CATEGORY = stringPreferencesKey("last_channel_category")
        val LAST_FAVORITES = booleanPreferencesKey("last_channel_favorites")
        val TRIAL_STARTED = longPreferencesKey("trial_started_at")
        val LICENSE_KEY = stringPreferencesKey("license_key")
        val LICENSE_ACTIVATED = longPreferencesKey("license_activated_at")
        val CLOCK_OFFSET = longPreferencesKey("server_clock_offset_ms")
        val REMOTE_PLAYLIST = stringPreferencesKey("applied_remote_playlist_url")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { p ->
        val lastPlaylist = p[Keys.LAST_PLAYLIST]
        val lastChannel = p[Keys.LAST_CHANNEL]
        AppSettings(
            languageTag = p[Keys.LANGUAGE] ?: "",
            parentalPinHash = p[Keys.PIN_HASH],
            refreshMode = p[Keys.REFRESH_MODE]?.let { runCatching { RefreshMode.valueOf(it) }.getOrNull() } ?: RefreshMode.ON_LAUNCH,
            hardwareAcceleration = p[Keys.HW_ACCEL] ?: true,
            aspectRatio = p[Keys.ASPECT]?.let { runCatching { AspectRatioMode.valueOf(it) }.getOrNull() } ?: AspectRatioMode.FIT,
            epgRetentionDays = p[Keys.EPG_DAYS] ?: 7,
            tmdbApiKey = p[Keys.TMDB_KEY] ?: "",
            launchLastChannel = p[Keys.LAUNCH_LAST] ?: false,
            lastChannel = if (lastPlaylist != null && lastChannel != null) {
                LastChannel(lastPlaylist, lastChannel, p[Keys.LAST_CATEGORY], p[Keys.LAST_FAVORITES] ?: false)
            } else null,
            trialStartedAt = p[Keys.TRIAL_STARTED],
            licenseKey = p[Keys.LICENSE_KEY],
            licenseActivatedAt = p[Keys.LICENSE_ACTIVATED],
            serverClockOffsetMs = p[Keys.CLOCK_OFFSET] ?: 0L,
            appliedRemotePlaylistUrl = p[Keys.REMOTE_PLAYLIST],
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setLanguage(tag: String) = context.settingsStore.edit { it[Keys.LANGUAGE] = tag }
    suspend fun setRefreshMode(mode: RefreshMode) = context.settingsStore.edit { it[Keys.REFRESH_MODE] = mode.name }
    suspend fun setHardwareAcceleration(enabled: Boolean) = context.settingsStore.edit { it[Keys.HW_ACCEL] = enabled }
    suspend fun setAspectRatio(mode: AspectRatioMode) = context.settingsStore.edit { it[Keys.ASPECT] = mode.name }
    suspend fun setEpgRetentionDays(days: Int) = context.settingsStore.edit { it[Keys.EPG_DAYS] = days }
    suspend fun setTmdbApiKey(key: String) = context.settingsStore.edit { it[Keys.TMDB_KEY] = key.trim() }
    suspend fun setLaunchLastChannel(enabled: Boolean) = context.settingsStore.edit { it[Keys.LAUNCH_LAST] = enabled }

    suspend fun setLastChannel(last: LastChannel) = context.settingsStore.edit {
        it[Keys.LAST_PLAYLIST] = last.playlistId
        it[Keys.LAST_CHANNEL] = last.channelId
        if (last.categoryId == null) it.remove(Keys.LAST_CATEGORY) else it[Keys.LAST_CATEGORY] = last.categoryId
        it[Keys.LAST_FAVORITES] = last.favoritesOnly
    }

    /** Records the trial start once; later calls are no-ops so reinstall-safe callers can pass an earlier timestamp. */
    suspend fun ensureTrialStarted(startedAt: Long) = context.settingsStore.edit {
        val existing = it[Keys.TRIAL_STARTED]
        if (existing == null || startedAt < existing) it[Keys.TRIAL_STARTED] = startedAt
    }

    suspend fun setServerClockOffset(offsetMs: Long) = context.settingsStore.edit { it[Keys.CLOCK_OFFSET] = offsetMs }
    suspend fun setAppliedRemotePlaylistUrl(url: String?) = context.settingsStore.edit {
        if (url == null) it.remove(Keys.REMOTE_PLAYLIST) else it[Keys.REMOTE_PLAYLIST] = url
    }

    suspend fun setLicense(key: String?, activatedAt: Long?) = context.settingsStore.edit {
        if (key == null) it.remove(Keys.LICENSE_KEY) else it[Keys.LICENSE_KEY] = key
        if (activatedAt == null) it.remove(Keys.LICENSE_ACTIVATED) else it[Keys.LICENSE_ACTIVATED] = activatedAt
    }

    suspend fun setPin(pin: String?) = context.settingsStore.edit {
        if (pin == null) it.remove(Keys.PIN_HASH) else it[Keys.PIN_HASH] = hash(pin)
    }

    suspend fun verifyPin(pin: String): Boolean = current().parentalPinHash == hash(pin)

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("iptv:$pin".toByteArray()).joinToString("") { "%02x".format(it) }
}
