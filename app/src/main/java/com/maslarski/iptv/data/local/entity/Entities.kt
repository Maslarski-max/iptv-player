package com.maslarski.iptv.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.maslarski.iptv.domain.model.ContentType
import com.maslarski.iptv.domain.model.PlaylistType

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: PlaylistType,
    val url: String,
    val username: String?,
    val password: String?,
    val epgUrl: String?,
    val isActive: Boolean,
    val lastSyncedAt: Long?,
    val channelCount: Int = 0,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
)

@Entity(
    tableName = "categories",
    primaryKeys = ["id", "playlistId", "type"],
    indices = [Index("playlistId", "type")],
)
data class CategoryEntity(
    val id: String,
    val playlistId: Long,
    val name: String,
    val type: ContentType,
    val isLocked: Boolean = false,
)

@Entity(
    tableName = "channels",
    primaryKeys = ["id", "playlistId"],
    indices = [Index("playlistId", "categoryId"), Index("epgChannelId"), Index("name")],
)
data class ChannelEntity(
    val id: String,
    val playlistId: Long,
    val name: String,
    val streamUrl: String,
    val logoUrl: String?,
    val categoryId: String?,
    val categoryName: String?,
    val epgChannelId: String?,
    val channelNumber: Int?,
    val sortOrder: Int,
)

@Entity(
    tableName = "movies",
    primaryKeys = ["id", "playlistId"],
    indices = [Index("playlistId", "categoryId"), Index("title"), Index("addedAt")],
)
data class MovieEntity(
    val id: String,
    val playlistId: Long,
    val title: String,
    val streamUrl: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val categoryId: String?,
    val categoryName: String?,
    val synopsis: String?,
    val releaseYear: String?,
    val rating: Double?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val durationSeconds: Long?,
    val addedAt: Long?,
)

@Entity(
    tableName = "series",
    primaryKeys = ["id", "playlistId"],
    indices = [Index("playlistId", "categoryId"), Index("title")],
)
data class SeriesEntity(
    val id: String,
    val playlistId: Long,
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val categoryId: String?,
    val categoryName: String?,
    val synopsis: String?,
    val releaseYear: String?,
    val rating: Double?,
    val cast: String?,
    val genre: String?,
    val detailsFetchedAt: Long? = null,
)

@Entity(
    tableName = "episodes",
    primaryKeys = ["id", "playlistId"],
    indices = [Index("seriesId", "playlistId")],
)
data class EpisodeEntity(
    val id: String,
    val seriesId: String,
    val playlistId: Long,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val streamUrl: String,
    val thumbnailUrl: String?,
    val synopsis: String?,
    val durationSeconds: Long?,
)

@Entity(
    tableName = "epg_programs",
    indices = [Index("epgChannelId", "startMillis"), Index("endMillis")],
)
data class EpgProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epgChannelId: String,
    val title: String,
    val description: String?,
    val startMillis: Long,
    val endMillis: Long,
    val category: String?,
)

@Entity(tableName = "favorites", primaryKeys = ["contentId", "contentType", "playlistId"])
data class FavoriteEntity(
    val contentId: String,
    val contentType: ContentType,
    val playlistId: Long,
    val addedAt: Long,
)

@Entity(
    tableName = "watch_progress",
    primaryKeys = ["contentId", "contentType", "playlistId"],
    indices = [Index("updatedAt")],
)
data class WatchProgressEntity(
    val contentId: String,
    val contentType: ContentType,
    val playlistId: Long,
    val positionMillis: Long,
    val durationMillis: Long,
    val updatedAt: Long,
    val seriesId: String? = null,
)
