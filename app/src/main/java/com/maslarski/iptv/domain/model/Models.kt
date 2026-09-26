package com.maslarski.iptv.domain.model

enum class ContentType { LIVE, MOVIE, SERIES }

enum class PlaylistType { M3U, XTREAM }

data class Playlist(
    val id: Long = 0,
    val name: String,
    val type: PlaylistType,
    val url: String,
    val username: String? = null,
    val password: String? = null,
    val epgUrl: String? = null,
    val isActive: Boolean = false,
    val lastSyncedAt: Long? = null,
    val channelCount: Int = 0,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
)

data class Category(
    val id: String,
    val playlistId: Long,
    val name: String,
    val type: ContentType,
    val isLocked: Boolean = false,
    val itemCount: Int = 0,
    val customOrder: Int = 0,
    val isVisible: Boolean = true,
)

data class Channel(
    val id: String,
    val playlistId: Long,
    val name: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val categoryId: String? = null,
    val categoryName: String? = null,
    val epgChannelId: String? = null,
    val channelNumber: Int? = null,
    val isFavorite: Boolean = false,
    val customOrder: Int = 0,
    val isVisible: Boolean = true,
)

data class Movie(
    val id: String,
    val playlistId: Long,
    val title: String,
    val streamUrl: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val categoryId: String? = null,
    val categoryName: String? = null,
    val synopsis: String? = null,
    val releaseYear: String? = null,
    val rating: Double? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val durationSeconds: Long? = null,
    val addedAt: Long? = null,
    val isFavorite: Boolean = false,
    val progress: WatchProgress? = null,
)

data class Series(
    val id: String,
    val playlistId: Long,
    val title: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val categoryId: String? = null,
    val categoryName: String? = null,
    val synopsis: String? = null,
    val releaseYear: String? = null,
    val rating: Double? = null,
    val cast: String? = null,
    val genre: String? = null,
    val isFavorite: Boolean = false,
)

data class Season(
    val number: Int,
    val name: String,
    val episodeCount: Int,
    val coverUrl: String? = null,
)

data class Episode(
    val id: String,
    val seriesId: String,
    val playlistId: Long,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val streamUrl: String,
    val thumbnailUrl: String? = null,
    val synopsis: String? = null,
    val durationSeconds: Long? = null,
    val progress: WatchProgress? = null,
)

data class EpgProgram(
    val id: Long = 0,
    val epgChannelId: String,
    val title: String,
    val description: String? = null,
    val startMillis: Long,
    val endMillis: Long,
    val category: String? = null,
) {
    fun isLiveAt(now: Long): Boolean = now in startMillis until endMillis
    fun progressAt(now: Long): Float {
        val total = (endMillis - startMillis).coerceAtLeast(1)
        return ((now - startMillis).toFloat() / total).coerceIn(0f, 1f)
    }
}

data class WatchProgress(
    val contentId: String,
    val contentType: ContentType,
    val playlistId: Long,
    val positionMillis: Long,
    val durationMillis: Long,
    val updatedAt: Long,
) {
    val fraction: Float
        get() = if (durationMillis <= 0) 0f else (positionMillis.toFloat() / durationMillis).coerceIn(0f, 1f)
    val isFinished: Boolean get() = durationMillis > 0 && fraction > 0.95f
}

data class Favorite(
    val contentId: String,
    val contentType: ContentType,
    val playlistId: Long,
    val addedAt: Long,
)

/** A unified item used by rows on the home dashboard and favorites/search results. */
data class MediaItem(
    val id: String,
    val playlistId: Long,
    val type: ContentType,
    val title: String,
    val imageUrl: String?,
    val subtitle: String? = null,
    val progress: Float? = null,
    val streamUrl: String? = null,
    val seriesId: String? = null,
    val categoryId: String? = null,
    val channelNumber: Int? = null,
    val rating: Double? = null,
    val isFavorite: Boolean = false,
)

data class PlaybackRequest(
    val contentId: String,
    val contentType: ContentType,
    val playlistId: Long,
    val title: String,
    val streamUrl: String,
    val startPositionMillis: Long = 0,
    val seriesId: String? = null,
)

data class SearchResults(
    val channels: List<Channel> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val series: List<Series> = emptyList(),
) {
    val isEmpty: Boolean get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()
}

data class SyncStatus(
    val isSyncing: Boolean = false,
    val playlistId: Long? = null,
    val message: String? = null,
    val progress: Float? = null,
    val error: String? = null,
)
