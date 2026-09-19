package com.maslarski.iptv.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.maslarski.iptv.data.local.dao.CategoryDao
import com.maslarski.iptv.data.local.dao.ChannelDao
import com.maslarski.iptv.data.local.dao.EpgDao
import com.maslarski.iptv.data.local.dao.EpisodeDao
import com.maslarski.iptv.data.local.dao.FavoriteDao
import com.maslarski.iptv.data.local.dao.MovieDao
import com.maslarski.iptv.data.local.dao.PlaylistDao
import com.maslarski.iptv.data.local.dao.SeriesDao
import com.maslarski.iptv.data.local.dao.WatchProgressDao
import com.maslarski.iptv.data.local.entity.CategoryEntity
import com.maslarski.iptv.data.local.entity.ChannelEntity
import com.maslarski.iptv.data.local.entity.EpgProgramEntity
import com.maslarski.iptv.data.local.entity.EpisodeEntity
import com.maslarski.iptv.data.local.entity.FavoriteEntity
import com.maslarski.iptv.data.local.entity.MovieEntity
import com.maslarski.iptv.data.local.entity.PlaylistEntity
import com.maslarski.iptv.data.local.entity.SeriesEntity
import com.maslarski.iptv.data.local.entity.WatchProgressEntity
import com.maslarski.iptv.domain.model.ContentType
import com.maslarski.iptv.domain.model.PlaylistType

class Converters {
    @TypeConverter fun contentTypeToString(value: ContentType): String = value.name
    @TypeConverter fun stringToContentType(value: String): ContentType = ContentType.valueOf(value)
    @TypeConverter fun playlistTypeToString(value: PlaylistType): String = value.name
    @TypeConverter fun stringToPlaylistType(value: String): PlaylistType = PlaylistType.valueOf(value)
}

@Database(
    entities = [
        PlaylistEntity::class,
        CategoryEntity::class,
        ChannelEntity::class,
        MovieEntity::class,
        SeriesEntity::class,
        EpisodeEntity::class,
        EpgProgramEntity::class,
        FavoriteEntity::class,
        WatchProgressEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class IptvDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun categoryDao(): CategoryDao
    abstract fun channelDao(): ChannelDao
    abstract fun movieDao(): MovieDao
    abstract fun seriesDao(): SeriesDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun epgDao(): EpgDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun watchProgressDao(): WatchProgressDao

    companion object {
        const val NAME = "iptv.db"
    }
}
