package com.maslarski.iptv.domain.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.maslarski.iptv.MainActivity
import com.maslarski.iptv.R
import com.maslarski.iptv.data.local.IptvDatabase
import com.maslarski.iptv.data.local.entity.ReminderEntity
import com.maslarski.iptv.domain.model.Channel
import com.maslarski.iptv.domain.model.EpgProgram
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** A request to start live playback, raised by reminders, notifications or the startup zap setting. */
data class PlayRequest(
    val playlistId: Long,
    val channelId: String,
    val categoryId: String?,
    val favoritesOnly: Boolean = false,
)

@Singleton
class ReminderManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: IptvDatabase,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val alarmManager = context.getSystemService<AlarmManager>()

    /** Reminders that fired while the UI is visible; the UI shows a prompt or auto-switches. */
    private val _fired = MutableSharedFlow<ReminderEntity>(extraBufferCapacity = 8)
    val fired: SharedFlow<ReminderEntity> = _fired

    private val _pendingPlay = MutableStateFlow<PlayRequest?>(null)
    val pendingPlay: StateFlow<PlayRequest?> = _pendingPlay.asStateFlow()

    @Volatile var inForeground: Boolean = false

    val upcoming: Flow<List<ReminderEntity>> = db.reminderDao().observeUpcoming(System.currentTimeMillis())

    fun requestPlay(request: PlayRequest) { _pendingPlay.value = request }
    fun consumePendingPlay() { _pendingPlay.value = null }

    suspend fun find(program: EpgProgram): ReminderEntity? = db.reminderDao().find(program.epgChannelId, program.startMillis)

    suspend fun set(channel: Channel, program: EpgProgram, autoSwitch: Boolean): ReminderEntity {
        val existing = find(program)
        val entity = ReminderEntity(
            id = existing?.id ?: 0,
            playlistId = channel.playlistId,
            channelId = channel.id,
            channelName = channel.name,
            categoryId = channel.categoryId,
            epgChannelId = program.epgChannelId,
            programTitle = program.title,
            startMillis = program.startMillis,
            endMillis = program.endMillis,
            autoSwitch = autoSwitch,
        )
        val id = db.reminderDao().insert(entity)
        val saved = entity.copy(id = id)
        schedule(saved)
        return saved
    }

    suspend fun remove(id: Long) {
        cancelAlarm(id)
        db.reminderDao().delete(id)
    }

    /** Re-arms alarms after a process restart and purges finished programs. */
    fun rescheduleAll() {
        scope.launch {
            val now = System.currentTimeMillis()
            db.reminderDao().deleteExpired(now)
            db.reminderDao().upcoming(now).forEach { schedule(it) }
        }
    }

    /** Foreground safety net: alarms may be deferred, so poll while the app is visible. */
    suspend fun tickWhileVisible() {
        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            db.reminderDao().upcoming(now).filter { it.startMillis <= now }.forEach { fire(it.id) }
            delay(10_000)
        }
    }

    suspend fun fire(id: Long) {
        val reminder = db.reminderDao().getById(id) ?: return
        db.reminderDao().delete(id)
        if (inForeground) {
            _fired.tryEmit(reminder)
        } else {
            notify(reminder)
        }
    }

    private fun schedule(reminder: ReminderEntity) {
        val am = alarmManager ?: return
        val pi = alarmIntent(reminder.id)
        val at = reminder.startMillis.coerceAtLeast(System.currentTimeMillis() + 1_000)
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exactAllowed) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private fun cancelAlarm(id: Long) {
        alarmManager?.cancel(alarmIntent(id))
    }

    private fun alarmIntent(id: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        id.toInt(),
        Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_REMINDER_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notify(reminder: ReminderEntity) {
        val nm = context.getSystemService<NotificationManager>() ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.reminder_channel_name), NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(
            context,
            reminder.id.toInt(),
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_PLAY_PLAYLIST, reminder.playlistId)
                .putExtra(EXTRA_PLAY_CHANNEL, reminder.channelId)
                .putExtra(EXTRA_PLAY_CATEGORY, reminder.categoryId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.reminder_starting_now, reminder.programTitle))
            .setContentText(reminder.channelName)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { nm.notify(reminder.id.toInt(), notification) }
    }

    companion object {
        const val CHANNEL_ID = "reminders"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_PLAY_PLAYLIST = "play_playlist_id"
        const val EXTRA_PLAY_CHANNEL = "play_channel_id"
        const val EXTRA_PLAY_CATEGORY = "play_category_id"

        fun playRequestFrom(intent: Intent?): PlayRequest? {
            if (intent == null || !intent.hasExtra(EXTRA_PLAY_CHANNEL)) return null
            val channel = intent.getStringExtra(EXTRA_PLAY_CHANNEL) ?: return null
            return PlayRequest(
                playlistId = intent.getLongExtra(EXTRA_PLAY_PLAYLIST, 0L),
                channelId = channel,
                categoryId = intent.getStringExtra(EXTRA_PLAY_CATEGORY),
            )
        }
    }
}

@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {
    @Inject lateinit var reminders: ReminderManager

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ReminderManager.EXTRA_REMINDER_ID, -1L)
        if (id < 0) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                reminders.fire(id)
            } finally {
                pending.finish()
            }
        }
    }
}
