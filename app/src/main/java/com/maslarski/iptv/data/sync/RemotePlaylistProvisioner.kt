package com.maslarski.iptv.data.sync

import android.util.Log
import com.maslarski.iptv.data.remote.firebase.DeviceAccount
import com.maslarski.iptv.data.repository.PlaylistRepository
import com.maslarski.iptv.data.settings.SettingsRepository
import com.maslarski.iptv.domain.license.LicenseRepository
import com.maslarski.iptv.domain.model.Playlist
import com.maslarski.iptv.domain.model.PlaylistType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies the operator-assigned `remotePlaylistUrl` from the device's Firestore document: the playlist
 * is added (or updated), made active and synced without the user typing any credentials. Re-runs only
 * when the assigned URL changes, so a user can still add their own playlists alongside it.
 */
@Singleton
class RemotePlaylistProvisioner @Inject constructor(
    private val license: LicenseRepository,
    private val playlists: PlaylistRepository,
    private val syncer: PlaylistSyncer,
    private val settings: SettingsRepository,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            license.account
                .filterNotNull()
                .map { it.remotePlaylistUrl?.let { url -> RemoteAssignment(url, it.remotePlaylistName, it.remoteEpgUrl) } }
                .distinctUntilChanged()
                .collect { assignment -> apply(assignment) }
        }
    }

    private suspend fun apply(assignment: RemoteAssignment?) {
        val applied = settings.current().appliedRemotePlaylistUrl
        if (assignment == null) {
            if (applied != null) settings.setAppliedRemotePlaylistUrl(null)
            return
        }
        if (assignment.url == applied && playlists.getAll().any { it.url == assignment.url }) return
        runCatching {
            val existing = playlists.getAll().firstOrNull { it.url == assignment.url }
            val playlist = (existing ?: Playlist(name = "", type = PlaylistType.M3U, url = assignment.url)).copy(
                name = assignment.name ?: existing?.name?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME,
                epgUrl = assignment.epgUrl ?: existing?.epgUrl,
                isActive = true,
            )
            val id = playlists.save(playlist)
            playlists.setActive(id)
            settings.setAppliedRemotePlaylistUrl(assignment.url)
            playlists.getById(id)?.let { syncer.sync(it) }
        }.onFailure { Log.w(TAG, "remote playlist provisioning failed", it) }
    }

    private data class RemoteAssignment(val url: String, val name: String?, val epgUrl: String?)

    private companion object {
        const val TAG = "RemotePlaylist"
        const val DEFAULT_NAME = "Provider playlist"
    }
}

/** Convenience for callers that only care whether the backend manages the playlist. */
val DeviceAccount.hasRemotePlaylist: Boolean get() = remotePlaylistUrl != null
