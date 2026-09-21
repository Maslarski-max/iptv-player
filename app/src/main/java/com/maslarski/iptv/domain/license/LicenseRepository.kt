package com.maslarski.iptv.domain.license

import android.content.Context
import android.provider.Settings
import com.maslarski.iptv.data.remote.firebase.DeviceAccount
import com.maslarski.iptv.data.remote.firebase.DeviceAccountRepository
import com.maslarski.iptv.data.settings.AppSettings
import com.maslarski.iptv.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

enum class SubscriptionStatus { TRIAL, ACTIVE, EXPIRED }

enum class LicensePlan(val code: String, val months: Int?) {
    LIFETIME("LIFE", null),
    MONTHLY("M001", 1),
    QUARTERLY("M003", 3),
    HALF_YEAR("M006", 6),
    YEARLY("M012", 12);

    companion object {
        fun fromCode(code: String): LicensePlan? = entries.firstOrNull { it.code == code }
    }
}

data class LicenseState(
    val status: SubscriptionStatus,
    val plan: LicensePlan?,
    val expiresAt: Long?,
    val deviceId: String,
    /** True when the status was validated against the device's Firestore record (server clock). */
    val remote: Boolean = false,
    val clockOffsetMs: Long = 0L,
) {
    val isLifetime: Boolean get() = status == SubscriptionStatus.ACTIVE && plan == LicensePlan.LIFETIME
    val isUnlocked: Boolean get() = status != SubscriptionStatus.EXPIRED

    /** Whole days left, rounded up so "23 hours left" still reads as 1 day. */
    fun daysLeft(now: Long = System.currentTimeMillis() + clockOffsetMs): Int? =
        expiresAt?.let { ceil((it - now).coerceAtLeast(0L) / DAY_MS.toDouble()).toInt() }

    companion object { const val DAY_MS = 24 * 60 * 60 * 1000L }
}

/**
 * Offline activation keys of the form `PLAN-XXXX-XXXX-XXXX`, where PLAN is a [LicensePlan.code] and
 * the twelve hex characters are the start of HMAC-SHA256(secret, "deviceId:PLAN"). A backend (or the
 * bundled tools/license_key.py) generates keys per device ID; the app verifies them without network.
 */
object LicenseKeys {
    private const val SECRET = "iptv-player-activation-v1"

    /** Universal master keys accepted on every device: 30 days of premium, or a permanent activation. */
    const val MASTER_MONTH = "MAX-1M-MASTER-2026"
    const val MASTER_LIFETIME = "MAX-LIFE-VIP-PERM"

    private val masterKeys = mapOf(MASTER_MONTH to LicensePlan.MONTHLY, MASTER_LIFETIME to LicensePlan.LIFETIME)

    fun normalize(raw: String): String = raw.trim().uppercase(Locale.ROOT).replace(" ", "").replace("_", "-")

    fun isMaster(key: String): Boolean = normalize(key) in masterKeys

    fun planFor(key: String, deviceId: String): LicensePlan? {
        masterKeys[normalize(key)]?.let { return it }
        val parts = normalize(key).split("-")
        if (parts.size != 4) return null
        val plan = LicensePlan.fromCode(parts[0]) ?: return null
        val expected = signature(deviceId, plan)
        return if (parts.drop(1).joinToString("") == expected) plan else null
    }

    fun signature(deviceId: String, plan: LicensePlan): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SECRET.toByteArray(), "HmacSHA256"))
        return mac.doFinal("$deviceId:${plan.code}".toByteArray())
            .joinToString("") { "%02X".format(it) }
            .take(12)
    }
}

@Singleton
class LicenseRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val remote: DeviceAccountRepository,
) {
    /** Stable MAC-style identifier derived from ANDROID_ID, e.g. `7A:3F:0C:91:B2:E4`. */
    val deviceId: String by lazy {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        MessageDigest.getInstance("SHA-256").digest("iptv-device:$androidId".toByteArray())
            .take(6)
            .joinToString(":") { "%02X".format(it) }
    }

    /** Live Firestore record for this device; `null` when Firebase is not configured or not yet loaded. */
    val account: Flow<DeviceAccount?> = remote.observe(deviceId)

    /**
     * Firestore is the source of truth when available: the trial counts from the server-side `createdAt`
     * and "now" is corrected by the learned server clock offset, so clearing app data or moving the
     * device clock does not extend the trial. A locally verified activation key still unlocks the app
     * (and is mirrored to Firestore); without Firebase the purely local rules apply.
     */
    val state: Flow<LicenseState> = combine(settings.settings, account, remote.serverClockOffsetMs) { s, acct, offset ->
        val now = System.currentTimeMillis() + offset
        val local = localState(s, now)
        if (acct == null) return@combine local.copy(clockOffsetMs = offset)
        val remoteEnd = acct.expiresAt
        val resolved = when {
            local.status == SubscriptionStatus.ACTIVE -> local.copy(remote = true)
            acct.isActive && acct.plan == LicensePlan.LIFETIME -> LicenseState(SubscriptionStatus.ACTIVE, acct.plan, null, deviceId, remote = true)
            acct.isActive && (remoteEnd == null || remoteEnd > now) -> LicenseState(SubscriptionStatus.ACTIVE, acct.plan, remoteEnd, deviceId, remote = true)
            acct.isActive -> LicenseState(SubscriptionStatus.EXPIRED, acct.plan, remoteEnd, deviceId, remote = true)
            acct.isExpired -> LicenseState(SubscriptionStatus.EXPIRED, acct.plan, remoteEnd ?: local.expiresAt, deviceId, remote = true)
            else -> {
                val start = acct.createdAt ?: (s.trialStartedAt ?: installTime())
                val trialEnd = start + TRIAL_DAYS * LicenseState.DAY_MS
                if (trialEnd > now) LicenseState(SubscriptionStatus.TRIAL, null, trialEnd, deviceId, remote = acct.createdAt != null)
                else LicenseState(SubscriptionStatus.EXPIRED, null, trialEnd, deviceId, remote = acct.createdAt != null)
            }
        }
        resolved.copy(clockOffsetMs = offset)
    }

    private fun localState(s: AppSettings, now: Long): LicenseState {
        val plan = s.licenseKey?.let { if (it.startsWith(PURCHASE_KEY_PREFIX)) LicensePlan.LIFETIME else LicenseKeys.planFor(it, deviceId) }
        val activatedAt = s.licenseActivatedAt
        val trialStart = s.trialStartedAt ?: installTime()
        val trialEnd = trialStart + TRIAL_DAYS * LicenseState.DAY_MS
        return when {
            plan == LicensePlan.LIFETIME -> LicenseState(SubscriptionStatus.ACTIVE, plan, null, deviceId)
            plan != null && activatedAt != null -> {
                val end = planEnd(plan, activatedAt)!!
                if (end > now) LicenseState(SubscriptionStatus.ACTIVE, plan, end, deviceId)
                else LicenseState(SubscriptionStatus.EXPIRED, plan, end, deviceId)
            }
            trialEnd > now -> LicenseState(SubscriptionStatus.TRIAL, null, trialEnd, deviceId)
            else -> LicenseState(SubscriptionStatus.EXPIRED, null, trialEnd, deviceId)
        }
    }

    private fun planEnd(plan: LicensePlan, activatedAt: Long): Long? =
        plan.months?.let { activatedAt + it * 30L * LicenseState.DAY_MS }

    /** Registers/touches the device record; call once per app start. */
    suspend fun syncRemote() = remote.registerOrTouch(deviceId)

    suspend fun current(): LicenseState = state.first()

    suspend fun ensureTrialStarted() = settings.ensureTrialStarted(installTime())

    /** Returns the activated plan, or null when the key is neither a master key nor a match for this device. */
    suspend fun activate(key: String): LicensePlan? {
        val plan = LicenseKeys.planFor(key, deviceId) ?: return null
        persistActivation(LicenseKeys.normalize(key), plan)
        return plan
    }

    /** Unlocks the lifetime plan after a verified Google Play purchase; the purchase token is kept as the local key. */
    suspend fun activatePurchase(purchaseToken: String) =
        persistActivation(PURCHASE_KEY_PREFIX + purchaseToken, LicensePlan.LIFETIME)

    private suspend fun persistActivation(key: String, plan: LicensePlan) {
        val activatedAt = System.currentTimeMillis() + remote.serverClockOffsetMs.value
        settings.setLicense(key, activatedAt)
        remote.recordActivation(deviceId, plan, planEnd(plan, activatedAt))
    }

    private fun installTime(): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
    }.getOrDefault(System.currentTimeMillis())

    companion object {
        const val TRIAL_DAYS = 7
        private const val PURCHASE_KEY_PREFIX = "GPLAY:"
    }
}
