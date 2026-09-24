package com.maslarski.iptv.data.remote.firebase

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.maslarski.iptv.BuildConfig
import com.maslarski.iptv.data.settings.SettingsRepository
import com.maslarski.iptv.domain.license.LicensePlan
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** Mirror of the `devices/{deviceId}` Firestore document. */
data class DeviceAccount(
    val deviceId: String,
    val createdAt: Long?,
    val subscriptionStatus: String,
    val plan: LicensePlan?,
    val expiresAt: Long?,
    val remotePlaylistUrl: String?,
    val remotePlaylistName: String?,
    val remoteEpgUrl: String?,
    val fromCache: Boolean,
) {
    val isActive: Boolean get() = subscriptionStatus == STATUS_ACTIVE
    val isExpired: Boolean get() = subscriptionStatus == STATUS_EXPIRED

    companion object {
        const val STATUS_TRIAL = "trial"
        const val STATUS_ACTIVE = "active"
        const val STATUS_EXPIRED = "expired"
    }
}

/**
 * Device-keyed account record in Cloud Firestore. Enabled only when Firebase was initialised from a
 * bundled google-services.json; otherwise every call is a no-op and [account] stays `null` so callers
 * fall back to the local licence logic. Firestore's persistent cache serves the last known document
 * while offline.
 */
@Singleton
class DeviceAccountRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    val enabled: Boolean = BuildConfig.FIREBASE_CONFIGURED && FirebaseApp.getApps(context).isNotEmpty()

    private val firestore: FirebaseFirestore? = if (enabled) {
        FirebaseFirestore.getInstance().apply {
            firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                .build()
        }
    } else null

    private val _serverClockOffsetMs = MutableStateFlow(0L)
    /** Server time minus local time; add it to `System.currentTimeMillis()` to get trusted "now". */
    val serverClockOffsetMs: StateFlow<Long> = _serverClockOffsetMs

    private fun doc(deviceId: String): DocumentReference? = firestore?.collection(COLLECTION)?.document(deviceId)

    fun observe(deviceId: String): Flow<DeviceAccount?> {
        val ref = doc(deviceId) ?: return flowOf(null)
        return callbackFlow {
            val registration = ref.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "device document listener failed", error)
                    return@addSnapshotListener
                }
                trySend(snapshot?.takeIf { it.exists() }?.toAccount(deviceId))
            }
            awaitClose { registration.remove() }
        }
    }

    /**
     * Registers the device on first launch (server-side `createdAt`), stamps `lastSeenAt` on every
     * launch, and re-learns the server clock offset from the round-trip. Safe to call without network.
     */
    suspend fun registerOrTouch(deviceId: String) {
        val ref = doc(deviceId) ?: return
        _serverClockOffsetMs.value = settings.current().serverClockOffsetMs
        runCatching {
            val existing = runCatching { ref.get(Source.SERVER).await() }.getOrNull()
            val base = mapOf(
                FIELD_DEVICE_ID to deviceId,
                FIELD_LAST_SEEN to FieldValue.serverTimestamp(),
                FIELD_APP_VERSION to BuildConfig.VERSION_NAME,
                FIELD_MODEL to "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            )
            if (existing == null || !existing.exists()) {
                ref.set(base + mapOf(FIELD_CREATED to FieldValue.serverTimestamp(), FIELD_STATUS to DeviceAccount.STATUS_TRIAL), SetOptions.merge()).await()
            } else {
                ref.set(base, SetOptions.merge()).await()
            }
            val fresh = ref.get(Source.SERVER).await()
            fresh.getTimestamp(FIELD_LAST_SEEN)?.let { learnClockOffset(it) }
        }.onFailure { Log.w(TAG, "device registration deferred (offline?)", it) }
    }

    /** Records a locally verified activation so the backend sees the same status the device enforces. */
    suspend fun recordActivation(deviceId: String, plan: LicensePlan, expiresAt: Long?) {
        val ref = doc(deviceId) ?: return
        runCatching {
            ref.set(
                mapOf(
                    FIELD_STATUS to DeviceAccount.STATUS_ACTIVE,
                    FIELD_PLAN to plan.code,
                    FIELD_EXPIRES to expiresAt?.let { Timestamp(it / 1000, ((it % 1000) * 1_000_000).toInt()) },
                    FIELD_ACTIVATED to FieldValue.serverTimestamp(),
                ),
                SetOptions.merge(),
            ).await()
        }.onFailure { Log.w(TAG, "activation sync deferred", it) }
    }

    private suspend fun learnClockOffset(serverNow: Timestamp) {
        val offset = serverNow.toDate().time - System.currentTimeMillis()
        if (abs(offset - _serverClockOffsetMs.value) > 5_000) {
            _serverClockOffsetMs.value = offset
            settings.setServerClockOffset(offset)
        }
    }

    private fun DocumentSnapshot.toAccount(deviceId: String) = DeviceAccount(
        deviceId = deviceId,
        createdAt = getTimestamp(FIELD_CREATED)?.toDate()?.time,
        subscriptionStatus = getString(FIELD_STATUS) ?: DeviceAccount.STATUS_TRIAL,
        plan = getString(FIELD_PLAN)?.let(LicensePlan::fromCode),
        expiresAt = getTimestamp(FIELD_EXPIRES)?.toDate()?.time,
        remotePlaylistUrl = getString(FIELD_REMOTE_PLAYLIST)?.trim()?.takeIf { it.isNotEmpty() },
        remotePlaylistName = getString(FIELD_REMOTE_PLAYLIST_NAME)?.trim()?.takeIf { it.isNotEmpty() },
        remoteEpgUrl = getString(FIELD_REMOTE_EPG)?.trim()?.takeIf { it.isNotEmpty() },
        fromCache = metadata.isFromCache,
    )

    companion object {
        private const val TAG = "DeviceAccount"
        const val COLLECTION = "devices"
        const val FIELD_DEVICE_ID = "deviceId"
        const val FIELD_CREATED = "createdAt"
        const val FIELD_LAST_SEEN = "lastSeenAt"
        const val FIELD_ACTIVATED = "activatedAt"
        const val FIELD_STATUS = "subscriptionStatus"
        const val FIELD_PLAN = "plan"
        const val FIELD_EXPIRES = "expiresAt"
        const val FIELD_REMOTE_PLAYLIST = "remotePlaylistUrl"
        const val FIELD_REMOTE_PLAYLIST_NAME = "remotePlaylistName"
        const val FIELD_REMOTE_EPG = "remoteEpgUrl"
        const val FIELD_APP_VERSION = "appVersion"
        const val FIELD_MODEL = "model"
    }
}
