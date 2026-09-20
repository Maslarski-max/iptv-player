package com.maslarski.iptv.data.repository

import androidx.room.withTransaction
import com.maslarski.iptv.data.local.IptvDatabase
import com.maslarski.iptv.data.mapper.toDomain
import com.maslarski.iptv.data.mapper.toEntity
import com.maslarski.iptv.domain.model.ContentType
import com.maslarski.iptv.domain.model.Playlist
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistRepository @Inject constructor(private val db: IptvDatabase) {

    val playlists: Flow<List<Playlist>> = db.playlistDao().observeAll().map { list -> list.map { it.toDomain() } }
    val activePlaylist: Flow<Playlist?> = db.playlistDao().observeActive().map { it?.toDomain() }

    suspend fun getActive(): Playlist? = db.playlistDao().getActive()?.toDomain()
    suspend fun getById(id: Long): Playlist? = db.playlistDao().getById(id)?.toDomain()
    suspend fun getAll(): List<Playlist> = db.playlistDao().getAll().map { it.toDomain() }

    suspend fun save(playlist: Playlist): Long = db.withTransaction {
        val makeActive = playlist.isActive || db.playlistDao().count() == 0
        val existing = if (playlist.id != 0L) db.playlistDao().getById(playlist.id) else null
        val entity = playlist.copy(
            isActive = makeActive || existing?.isActive == true,
            lastSyncedAt = existing?.lastSyncedAt,
            channelCount = existing?.channelCount ?: 0,
            movieCount = existing?.movieCount ?: 0,
            seriesCount = existing?.seriesCount ?: 0,
        ).toEntity()
        val insertedId = db.playlistDao().insert(entity)
        val resolved = if (playlist.id == 0L) insertedId else playlist.id
        if (makeActive) db.playlistDao().setActive(resolved)
        resolved
    }

    suspend fun setActive(id: Long) = db.playlistDao().setActive(id)

    suspend fun delete(id: Long) = db.withTransaction {
        val wasActive = db.playlistDao().getById(id)?.isActive == true
        db.channelDao().deleteFor(id)
        db.movieDao().deleteFor(id)
        db.seriesDao().deleteFor(id)
        db.episodeDao().deleteFor(id)
        ContentType.entries.forEach { db.categoryDao().deleteFor(id, it) }
        db.favoriteDao().deleteFor(id)
        db.watchProgressDao().deleteFor(id)
        db.tmdbMetadataDao().deleteFor(id)
        db.reminderDao().deleteFor(id)
        db.playlistDao().delete(id)
        if (wasActive) db.playlistDao().getAll().firstOrNull()?.let { db.playlistDao().setActive(it.id) }
    }
}
