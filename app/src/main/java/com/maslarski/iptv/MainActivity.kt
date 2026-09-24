package com.maslarski.iptv

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.maslarski.iptv.data.settings.SettingsRepository
import com.maslarski.iptv.domain.reminder.PlayRequest
import com.maslarski.iptv.domain.reminder.ReminderManager
import com.maslarski.iptv.ui.IptvApp
import com.maslarski.iptv.ui.theme.IptvTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var reminders: ReminderManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            val current = settings.current()
            val tag = current.languageTag
            if (tag.isNotBlank() && AppCompatDelegate.getApplicationLocales().isEmpty) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
            val fromNotification = ReminderManager.playRequestFrom(intent)
            when {
                fromNotification != null -> reminders.requestPlay(fromNotification)
                savedInstanceState == null && current.launchLastChannel -> current.lastChannel?.let { last ->
                    reminders.requestPlay(PlayRequest(last.playlistId, last.channelId, last.categoryId, last.favoritesOnly))
                }
            }
        }
        setContent {
            IptvTheme { IptvApp() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        ReminderManager.playRequestFrom(intent)?.let(reminders::requestPlay)
    }

    override fun onStart() {
        super.onStart()
        reminders.inForeground = true
    }

    override fun onStop() {
        reminders.inForeground = false
        super.onStop()
    }
}
