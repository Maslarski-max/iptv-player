package com.maslarski.iptv.data.mapper

import com.maslarski.iptv.data.local.entity.CategoryEntity
import com.maslarski.iptv.data.local.entity.ChannelEntity
import com.maslarski.iptv.data.local.entity.EpgProgramEntity
import com.maslarski.iptv.data.local.entity.EpisodeEntity
import com.maslarski.iptv.data.local.entity.FavoriteEntity
import com.maslarski.iptv.data.local.entity.MovieEntity
import com.maslarski.iptv.data.local.entity.PlaylistEntity
import com.maslarski.iptv.data.local.entity.SeriesEntity
import com.maslarski.iptv.data.local.entity.WatchProgressEntity
import com.maslarski.iptv.domain.model.Category
import com.maslarski.iptv.domain.model.Channel
import com.maslarski.iptv.domain.model.ContentType
import com.maslarski.iptv.domain.model.EpgProgram
import com.maslarski.iptv.domain.model.Episode
import com.maslarski.iptv.domain.model.Favorite
import com.maslarski.iptv.domain.model.MediaItem
import com.maslarski.iptv.domain.model.Movie
import com.maslarski.iptv.domain.model.Playlist
import com.maslarski.iptv.domain.model.Series
import com.maslarski.iptv.domain.model.WatchProgress

fun PlaylistEntity.toDomain() = Playlist(
    id = id, name = name, type = type, url = url, username = username, password = password,
    epgUrl = epgUrl, isActive = isActive, lastSyncedAt = lastSyncedAt,
    channelCount = channelCount, movieCount = movieCount, seriesCount = seriesCount,
)

fun Playlist.toEntity() = PlaylistEntity(
    id = id, name = name, type = type, url = url, username = username, password = password,
    epgUrl = epgUrl, isActive = isActive, lastSyncedAt = lastSyncedAt,
    channelCount = channelCount, movieCount = movieCount, seriesCount = seriesCount,
)

fun CategoryEntity.toDomain(itemCount: Int = 0) =
    Category(id = id, playlistId = playlistId, name = name, type = type, isLocked = isLocked, itemCount = itemCount)

fun ChannelEntity.toDomain(isFavorite: Boolean = false) = Channel(
    id = id, playlistId = playlistId, name = name, streamUrl = streamUrl, logoUrl = logoUrl,
    categoryId = categoryId, categoryName = categoryName, epgChannelId = epgChannelId,
    channelNumber = channelNumber, isFavorite = isFavorite,
)

fun MovieEntity.toDomain(isFavorite: Boolean = false, progress: WatchProgress? = null) = Movie(
    id = id, playlistId = playlistId, title = title, streamUrl = streamUrl, posterUrl = posterUrl,
    backdropUrl = backdropUrl, categoryId = categoryId, categoryName = categoryName, synopsis = synopsis,
    releaseYear = releaseYear, rating = rating, cast = cast, director = director, genre = genre,
    durationSeconds = durationSeconds, addedAt = addedAt, isFavorite = isFavorite, progress = progress,
)

fun SeriesEntity.toDomain(isFavorite: Boolean = false) = Series(
    id = id, playlistId = playlistId, title = title, posterUrl = posterUrl, backdropUrl = backdropUrl,
    categoryId = categoryId, categoryName = categoryName, synopsis = synopsis, releaseYear = releaseYear,
    rating = rating, cast = cast, genre = genre, isFavorite = isFavorite,
)

fun EpisodeEntity.toDomain(progress: WatchProgress? = null) = Episode(
    id = id, seriesId = seriesId, playlistId = playlistId, seasonNumber = seasonNumber,
    episodeNumber = episodeNumber, title = title, streamUrl = streamUrl, thumbnailUrl = thumbnailUrl,
    synopsis = synopsis, durationSeconds = durationSeconds, progress = progress,
)

fun EpgProgramEntity.toDomain() = EpgProgram(
    id = id, epgChannelId = epgChannelId, title = title, description = description,
    startMillis = startMillis, endMillis = endMillis, category = category,
)

fun WatchProgressEntity.toDomain() = WatchProgress(
    contentId = contentId, contentType = contentType, playlistId = playlistId,
    positionMillis = positionMillis, durationMillis = durationMillis, updatedAt = updatedAt,
)

fun FavoriteEntity.toDomain() = Favorite(contentId, contentType, playlistId, addedAt)

fun Channel.toMediaItem() = MediaItem(
    id = id, playlistId = playlistId, type = ContentType.LIVE, title = name, imageUrl = logoUrl,
    subtitle = categoryName, streamUrl = streamUrl, categoryId = categoryId, channelNumber = channelNumber, isFavorite = isFavorite,
)

fun Movie.toMediaItem() = MediaItem(
    id = id, playlistId = playlistId, type = ContentType.MOVIE, title = title, imageUrl = posterUrl,
    subtitle = releaseYear, progress = progress?.fraction, streamUrl = streamUrl,
    categoryId = categoryId, rating = rating, isFavorite = isFavorite,
)

fun Series.toMediaItem() = MediaItem(
    id = id, playlistId = playlistId, type = ContentType.SERIES, title = title, imageUrl = posterUrl,
    subtitle = releaseYear, categoryId = categoryId, rating = rating, isFavorite = isFavorite,
)

fun Episode.toMediaItem(seriesTitle: String?, poster: String?) = MediaItem(
    id = id, playlistId = playlistId, type = ContentType.SERIES,
    title = seriesTitle ?: title,
    imageUrl = thumbnailUrl ?: poster,
    subtitle = "S${seasonNumber.toString().padStart(2, '0')}E${episodeNumber.toString().padStart(2, '0')} · $title",
    progress = progress?.fraction, streamUrl = streamUrl, seriesId = seriesId,
)
