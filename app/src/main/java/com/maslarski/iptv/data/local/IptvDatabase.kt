package com.maslarski.iptv.data.local

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.maslarski.iptv.data.local.dao.CategoryDao
import com.maslarski.iptv.data.local.dao.ChannelDao
import com.maslarski.iptv.data.local.dao.EpgDao
import com.maslarski.iptv.data.local.dao.EpisodeDao
import com.maslarski.iptv.data.local.dao.FavoriteDao
import com.maslarski.iptv.data.local.dao.MovieDao
import com.maslarski.iptv.data.local.dao.PlaylistDao
import com.maslarski.iptv.data.local.dao.ReminderDao
import com.maslarski.iptv.data.local.dao.SeriesDao
import com.maslarski.iptv.data.local.dao.TmdbMetadataDao
import com.maslarski.iptv.data.local.dao.WatchProgressDao
import com.maslarski.iptv.data.local.entity.CategoryEntity
import com.maslarski.iptv.data.local.entity.ChannelEntity
import com.maslarski.iptv.data.local.entity.EpgProgramEntity
import com.maslarski.iptv.data.local.entity.EpisodeEntity
import com.maslarski.iptv.data.local.entity.FavoriteEntity
import com.maslarski.iptv.data.local.entity.MovieEntity
import com.maslarski.iptv.data.local.entity.PlaylistEntity
import com.maslarski.iptv.data.local.entity.ReminderEntity
import com.maslarski.iptv.data.local.entity.SeriesEntity
import com.maslarski.iptv.data.local.entity.TmdbMetadataEntity
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
        TmdbMetadataEntity::class,
        ReminderEntity::class,
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 2, to = 3)],
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
    abstract fun tmdbMetadataDao(): TmdbMetadataDao
    abstract fun reminderDao(): ReminderDao

    companion object {
        const val NAME = "iptv.db"

        /** Adds custom ordering + visibility; existing rows keep their previous order (name / sortOrder). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN customOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE categories ADD COLUMN isVisible INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    "UPDATE categories SET customOrder = (SELECT COUNT(*) FROM categories c2 " +
                        "WHERE c2.playlistId = categories.playlistId AND c2.type = categories.type AND c2.name < categories.name)",
                )
                db.execSQL("ALTER TABLE channels ADD COLUMN customOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE channels ADD COLUMN isVisible INTEGER NOT NULL DEFAULT 1")
                db.execSQL("UPDATE channels SET customOrder = sortOrder")
            }
        }
    }
}
