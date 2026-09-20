package com.maslarski.iptv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maslarski.iptv.data.local.entity.ReminderEntity
import com.maslarski.iptv.domain.license.LicenseRepository
import com.maslarski.iptv.domain.license.LicenseState
import com.maslarski.iptv.domain.reminder.PlayRequest
import com.maslarski.iptv.domain.reminder.ReminderManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** App-wide events that outlive any single screen: licence gating, reminder alarms and deferred play requests. */
@HiltViewModel
class AppViewModel @Inject constructor(
    license: LicenseRepository,
    private val reminders: ReminderManager,
) : ViewModel() {
    val license: StateFlow<LicenseState?> = license.state.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val firedReminders: SharedFlow<ReminderEntity> = reminders.fired
    val pendingPlay: StateFlow<PlayRequest?> = reminders.pendingPlay

    fun consumePendingPlay() = reminders.consumePendingPlay()

    /** Keeps reminders firing on time while the app is visible even if the exact alarm was deferred by the OS. */
    private var ticker: Job? = null
    fun tickReminders() {
        if (ticker?.isActive == true) return
        ticker = viewModelScope.launch { reminders.tickWhileVisible() }
    }
}
