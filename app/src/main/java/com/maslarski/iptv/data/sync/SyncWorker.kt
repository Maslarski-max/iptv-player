package com.maslarski.iptv.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.maslarski.iptv.data.repository.PlaylistRepository
import com.maslarski.iptv.data.settings.RefreshMode
import com.maslarski.iptv.data.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val playlists: PlaylistRepository,
    private val syncer: PlaylistSyncer,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val onlyIfStale = inputData.getBoolean(KEY_ONLY_IF_STALE, true)
        if (onlyIfStale && !settings.current().autoUpdateOnLaunch) return Result.success()
        val playlistId = inputData.getLong(KEY_PLAYLIST_ID, -1L)
        val targets = if (playlistId > 0) listOfNotNull(playlists.getById(playlistId)) else playlists.getAll()
        val now = System.currentTimeMillis()
        var failed = false
        for (playlist in targets) {
            val stale = playlist.lastSyncedAt?.let { now - it > STALE_AFTER_MS } ?: true
            if (onlyIfStale && !stale) continue
            if (syncer.sync(playlist).isFailure) failed = true
        }
        return if (failed && runAttemptCount < 2) Result.retry() else Result.success()
    }

    companion object {
        const val KEY_PLAYLIST_ID = "playlist_id"
        const val KEY_ONLY_IF_STALE = "only_if_stale"
        const val STALE_AFTER_MS = 6 * 60 * 60 * 1000L
        const val LAUNCH_WORK = "sync_on_launch"
        const val PERIODIC_WORK = "sync_periodic"
    }
}

@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    suspend fun scheduleOnLaunch() {
        val mode = settings.current().refreshMode
        if (mode != RefreshMode.MANUAL) {
            val launch = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(SyncWorker.KEY_ONLY_IF_STALE to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(SyncWorker.LAUNCH_WORK, ExistingWorkPolicy.KEEP, launch)
        }
        applyRefreshMode(mode, replace = false)
    }

    fun applyRefreshMode(mode: RefreshMode, replace: Boolean = true) {
        val wm = WorkManager.getInstance(context)
        val hours = mode.periodHours
        if (hours == null) {
            wm.cancelUniqueWork(SyncWorker.PERIODIC_WORK)
            return
        }
        val periodic = PeriodicWorkRequestBuilder<SyncWorker>(hours, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setInputData(workDataOf(SyncWorker.KEY_ONLY_IF_STALE to true))
            .build()
        val policy = if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP
        wm.enqueueUniquePeriodicWork(SyncWorker.PERIODIC_WORK, policy, periodic)
    }

    fun syncAllNow() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(SyncWorker.KEY_ONLY_IF_STALE to false))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("sync_all", ExistingWorkPolicy.REPLACE, request)
    }

    fun syncNow(playlistId: Long) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(SyncWorker.KEY_PLAYLIST_ID to playlistId, SyncWorker.KEY_ONLY_IF_STALE to false))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("sync_$playlistId", ExistingWorkPolicy.REPLACE, request)
    }
}
