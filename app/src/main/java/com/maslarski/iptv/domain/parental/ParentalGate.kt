package com.maslarski.iptv.domain.parental

import com.maslarski.iptv.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session-scoped parental lock. Locked categories stay hidden until the correct PIN is entered;
 * the unlock lasts until the process dies or [lock] is called.
 */
@Singleton
class ParentalGate @Inject constructor(private val settings: SettingsRepository) {
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    /** True when locked content must be hidden/guarded right now. */
    val enforcing: Flow<Boolean> = combine(settings.settings, _unlocked) { s, unlocked -> s.isParentalEnabled && !unlocked }

    suspend fun tryUnlock(pin: String): Boolean {
        val ok = settings.verifyPin(pin)
        if (ok) _unlocked.value = true
        return ok
    }

    fun lock() { _unlocked.value = false }
}
