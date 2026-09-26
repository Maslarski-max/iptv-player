package com.maslarski.iptv.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
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
import kotlinx.coroutines.flow.Flow

data class CategoryCount(val categoryId: String?, val count: Int)

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY id")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY id")
    suspend fun getAll(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getById(id: Long): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Query("UPDATE playlists SET isActive = CASE WHEN id = :id THEN 1 ELSE 0 END")
    suspend fun setActive(id: Long)

    @Query("UPDATE playlists SET lastSyncedAt = :at, channelCount = :channels, movieCount = :movies, seriesCount = :series WHERE id = :id")
    suspend fun updateSyncStats(id: Long, at: Long, channels: Int, movies: Int, series: Int)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM playlists")
    suspend fun count(): Int
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE playlistId = :playlistId AND type = :type AND isVisible = 1 ORDER BY customOrder ASC, name ASC")
    fun observe(playlistId: Long, type: ContentType): Flow<List<CategoryEntity>>

    /** Every category including hidden ones, for the management editor. */
    @Query("SELECT * FROM categories WHERE playlistId = :playlistId AND type = :type ORDER BY customOrder ASC, name ASC")
    fun observeAllForEdit(playlistId: Long, type: ContentType): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE playlistId = :playlistId")
    suspend fun getAll(playlistId: Long): List<CategoryEntity>

    @Query("UPDATE categories SET customOrder = :order WHERE id = :id AND playlistId = :playlistId AND type = :type")
    suspend fun setOrder(id: String, playlistId: Long, type: ContentType, order: Int)

    @Query("UPDATE categories SET isVisible = :visible WHERE id = :id AND playlistId = :playlistId AND type = :type")
    suspend fun setVisible(id: String, playlistId: Long, type: ContentType, visible: Boolean)

    @Query("UPDATE categories SET customOrder = :order, isVisible = :visible WHERE id = :id AND playlistId = :playlistId AND type = :type")
    suspend fun restoreCustomization(id: String, playlistId: Long, type: ContentType, order: Int, visible: Boolean)

    @Query("SELECT * FROM categories WHERE playlistId = :playlistId AND isLocked = 1")
    fun observeLocked(playlistId: Long): Flow<List<CategoryEntity>>

    @Query("SELECT id FROM categories WHERE playlistId = :playlistId AND isLocked = 1")
    suspend fun getLockedIds(playlistId: Long): List<String>

    @Query("UPDATE categories SET isLocked = 1 WHERE playlistId = :playlistId AND id IN (:ids)")
    suspend fun relock(playlistId: Long, ids: List<String>)

    @Upsert
    suspend fun upsertAll(categories: List<CategoryEntity>)

    @Query("UPDATE categories SET isLocked = :locked WHERE id = :id AND playlistId = :playlistId AND type = :type")
    suspend fun setLocked(id: String, playlistId: Long, type: ContentType, locked: Boolean)

    @Query("DELETE FROM categories WHERE playlistId = :playlistId AND type = :type")
    suspend fun deleteFor(playlistId: Long, type: ContentType)
}

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND $VISIBLE ORDER BY customOrder ASC, sortOrder ASC")
    fun observeAll(playlistId: Long): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND $VISIBLE ORDER BY customOrder ASC, sortOrder ASC LIMIT :limit")
    fun observeFirst(playlistId: Long, limit: Int): Flow<List<ChannelEntity>>

    /** Channels of one category including hidden ones, for the management editor. */
    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND categoryId = :categoryId ORDER BY customOrder ASC, sortOrder ASC")
    fun observeByCategoryForEdit(playlistId: Long, categoryId: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND (customOrder != sortOrder OR isVisible = 0)")
    suspend fun getCustomized(playlistId: Long): List<ChannelEntity>

    @Query("UPDATE channels SET customOrder = :order WHERE id = :id AND playlistId = :playlistId")
    suspend fun setOrder(id: String, playlistId: Long, order: Int)

    @Query("UPDATE channels SET isVisible = :visible WHERE id = :id AND playlistId = :playlistId")
    suspend fun setVisible(id: String, playlistId: Long, visible: Boolean)

    @Query("UPDATE channels SET customOrder = :order, isVisible = :visible WHERE id = :id AND playlistId = :playlistId")
    suspend fun restoreCustomization(id: String, playlistId: Long, order: Int, visible: Boolean)

    @Query("SELECT categoryId, COUNT(*) AS count FROM channels WHERE playlistId = :playlistId GROUP BY categoryId")
    fun observeCategoryCounts(playlistId: Long): Flow<List<CategoryCount>>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND categoryId = :categoryId AND $VISIBLE ORDER BY customOrder ASC, sortOrder ASC")
    fun observeByCategory(playlistId: Long, categoryId: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND id = :id")
    suspend fun getById(playlistId: Long, id: String): ChannelEntity?

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND id IN (:ids) AND $VISIBLE")
    fun observeByIds(playlistId: Long, ids: List<String>): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId AND name LIKE '%' || :query || '%' AND $VISIBLE ORDER BY customOrder ASC, sortOrder ASC LIMIT :limit")
    suspend fun search(playlistId: Long, query: String, limit: Int): List<ChannelEntity>

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :playlistId")
    suspend fun count(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun deleteFor(playlistId: Long)

    companion object {
        /** Visible channel whose category (if any) is not hidden. */
        const val VISIBLE = "isVisible = 1 AND (categoryId IS NULL OR categoryId NOT IN " +
            "(SELECT id FROM categories WHERE categories.playlistId = channels.playlistId AND categories.type = 'LIVE' AND categories.isVisible = 0))"
    }
}

@Dao
interface MovieDao {
    @Query("SELECT * FROM movies WHERE playlistId = :playlistId AND (categoryId IS NULL OR categoryId NOT IN (SELECT id FROM categories WHERE categories.playlistId = movies.playlistId AND categories.type = 'MOVIE' AND categories.isVisible = 0)) ORDER BY title")
    fun observeAll(playlistId: Long): Flow<List<MovieEntity>>

    @Query("SELECT categoryId, COUNT(*) AS count FROM movies WHERE playlistId = :playlistId GROUP BY categoryId")
    fun observeCategoryCounts(playlistId: Long): Flow<List<CategoryCount>>

    @Query("SELECT * FROM movies WHERE playlistId = :playlistId AND categoryId = :categoryId ORDER BY title")
    fun observeByCategory(playlistId: Long, categoryId: String): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE playlistId = :playlistId AND (categoryId IS NULL OR categoryId NOT IN (SELECT id FROM categories WHERE categories.playlistId = movies.playlistId AND categories.type = 'MOVIE' AND categories.isVisible = 0)) ORDER BY addedAt DESC LIMIT :limit")
    fun observeRecent(playlistId: Long, limit: Int): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE playlistId = :playlistId AND rating IS NOT NULL AND (categoryId IS NULL OR categoryId NOT IN (SELECT id FROM categories WHERE categories.playlistId = movies.playlistId AND categories.type = 'MOVIE' AND categories.isVisible = 0)) ORDER BY rating DESC LIMIT :limit")
    fun observeTopRated(playlistId: Long, limit: Int): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE playlistId = :playlistId AND id = :id")
    suspend fun getById(playlistId: Long, id: String): MovieEntity?

    @Query("SELECT * FROM movies WHERE playlistId = :playlistId AND id = :id")
    fun observeById(playlistId: Long, id: String): Flow<MovieEntity?>

    @Query("SELECT * FROM movies WHERE playlistId = :playlistId AND id IN (:ids)")
    fun observeByIds(playlistId: Long, ids: List<String>): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE playlistId = :playlistId AND title LIKE '%' || :query || '%' AND (categoryId IS NULL OR categoryId NOT IN (SELECT id FROM categories WHERE categories.playlistId = movies.playlistId AND categories.type = 'MOVIE' AND categories.isVisible = 0)) ORDER BY title LIMIT :limit")
    suspend fun search(playlistId: Long, query: String, limit: Int): List<MovieEntity>

    @Query("SELECT COUNT(*) FROM movies WHERE playlistId = :playlistId")
    suspend fun count(playlistId: Long): Int

    @Query(
        """
        SELECT * FROM movies WHERE playlistId = :playlistId
          AND ((posterUrl IS NULL OR posterUrl = '') OR (synopsis IS NULL OR synopsis = ''))
          AND (tmdbCheckedAt IS NULL OR tmdbCheckedAt < :checkedBefore)
        ORDER BY addedAt DESC LIMIT :limit
        """,
    )
    suspend fun needingEnrichment(playlistId: Long, checkedBefore: Long, limit: Int): List<MovieEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(movies: List<MovieEntity>)

    @Query("DELETE FROM movies WHERE playlistId = :playlistId")
    suspend fun deleteFor(playlistId: Long)
}

@Dao
interface SeriesDao {
    @Query("SELECT * FROM series WHERE playlistId = :playlistId AND (categoryId IS NULL OR categoryId NOT IN (SELECT id FROM categories WHERE categories.playlistId = series.playlistId AND categories.type = 'SERIES' AND categories.isVisible = 0)) ORDER BY title")
    fun observeAll(playlistId: Long): Flow<List<SeriesEntity>>

    @Query("SELECT categoryId, COUNT(*) AS count FROM series WHERE playlistId = :playlistId GROUP BY categoryId")
    fun observeCategoryCounts(playlistId: Long): Flow<List<CategoryCount>>

    @Query("SELECT * FROM series WHERE playlistId = :playlistId AND (categoryId IS NULL OR categoryId NOT IN (SELECT id FROM categories WHERE categories.playlistId = series.playlistId AND categories.type = 'SERIES' AND categories.isVisible = 0)) ORDER BY rowid DESC LIMIT :limit")
    fun observeRecent(playlistId: Long, limit: Int): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE playlistId = :playlistId AND categoryId = :categoryId ORDER BY title")
    fun observeByCategory(playlistId: Long, categoryId: String): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE playlistId = :playlistId AND id = :id")
    fun observeById(playlistId: Long, id: String): Flow<SeriesEntity?>

    @Query("SELECT * FROM series WHERE playlistId = :playlistId AND id = :id")
    suspend fun getById(playlistId: Long, id: String): SeriesEntity?

    @Query("SELECT * FROM series WHERE playlistId = :playlistId AND id IN (:ids)")
    fun observeByIds(playlistId: Long, ids: List<String>): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE playlistId = :playlistId AND rating IS NOT NULL AND (categoryId IS NULL OR categoryId NOT IN (SELECT id FROM categories WHERE categories.playlistId = series.playlistId AND categories.type = 'SERIES' AND categories.isVisible = 0)) ORDER BY rating DESC LIMIT :limit")
    fun observeTopRated(playlistId: Long, limit: Int): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE playlistId = :playlistId AND title LIKE '%' || :query || '%' AND (categoryId IS NULL OR categoryId NOT IN (SELECT id FROM categories WHERE categories.playlistId = series.playlistId AND categories.type = 'SERIES' AND categories.isVisible = 0)) ORDER BY title LIMIT :limit")
    suspend fun search(playlistId: Long, query: String, limit: Int): List<SeriesEntity>

    @Query("SELECT COUNT(*) FROM series WHERE playlistId = :playlistId")
    suspend fun count(playlistId: Long): Int

    @Query(
        """
        SELECT * FROM series WHERE playlistId = :playlistId
          AND ((posterUrl IS NULL OR posterUrl = '') OR (synopsis IS NULL OR synopsis = ''))
          AND (tmdbCheckedAt IS NULL OR tmdbCheckedAt < :checkedBefore)
        ORDER BY rowid DESC LIMIT :limit
        """,
    )
    suspend fun needingEnrichment(playlistId: Long, checkedBefore: Long, limit: Int): List<SeriesEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(series: List<SeriesEntity>)

    @Query("UPDATE series SET detailsFetchedAt = :at WHERE playlistId = :playlistId AND id = :id")
    suspend fun markDetailsFetched(playlistId: Long, id: String, at: Long)

    @Query("DELETE FROM series WHERE playlistId = :playlistId")
    suspend fun deleteFor(playlistId: Long)
}

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes WHERE playlistId = :playlistId AND seriesId = :seriesId ORDER BY seasonNumber, episodeNumber")
    fun observeForSeries(playlistId: Long, seriesId: String): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE playlistId = :playlistId AND id = :id")
    suspend fun getById(playlistId: Long, id: String): EpisodeEntity?

    @Query("SELECT * FROM episodes WHERE playlistId = :playlistId AND id IN (:ids)")
    fun observeByIds(playlistId: Long, ids: List<String>): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE playlistId = :playlistId AND seriesId = :seriesId AND (seasonNumber > :season OR (seasonNumber = :season AND episodeNumber > :episode)) ORDER BY seasonNumber, episodeNumber LIMIT 1")
    suspend fun nextEpisode(playlistId: Long, seriesId: String, season: Int, episode: Int): EpisodeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(episodes: List<EpisodeEntity>)

    @Query("DELETE FROM episodes WHERE playlistId = :playlistId AND seriesId = :seriesId")
    suspend fun deleteForSeries(playlistId: Long, seriesId: String)

    @Query("DELETE FROM episodes WHERE playlistId = :playlistId")
    suspend fun deleteFor(playlistId: Long)
}

@Dao
interface EpgDao {
    @Query("SELECT * FROM epg_programs WHERE epgChannelId = :epgChannelId AND endMillis > :from AND startMillis < :to ORDER BY startMillis")
    fun observeForChannel(epgChannelId: String, from: Long, to: Long): Flow<List<EpgProgramEntity>>

    @Query("SELECT * FROM epg_programs WHERE epgChannelId IN (:epgChannelIds) AND endMillis > :from AND startMillis < :to ORDER BY epgChannelId, startMillis")
    fun observeForChannels(epgChannelIds: List<String>, from: Long, to: Long): Flow<List<EpgProgramEntity>>

    @Query("SELECT * FROM epg_programs WHERE epgChannelId IN (:epgChannelIds) AND startMillis <= :now AND endMillis > :now")
    fun observeNowPlaying(epgChannelIds: List<String>, now: Long): Flow<List<EpgProgramEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(programs: List<EpgProgramEntity>)

    @Query("DELETE FROM epg_programs WHERE endMillis < :before")
    suspend fun deleteEndedBefore(before: Long)

    @Query("DELETE FROM epg_programs")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM epg_programs")
    suspend fun count(): Int

    @Transaction
    suspend fun replaceAll(programs: List<EpgProgramEntity>) {
        deleteAll()
        programs.chunked(2000).forEach { insertAll(it) }
    }
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites WHERE playlistId = :playlistId ORDER BY addedAt DESC")
    fun observeAll(playlistId: Long): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE playlistId = :playlistId AND contentType = :type ORDER BY addedAt DESC")
    fun observeByType(playlistId: Long, type: ContentType): Flow<List<FavoriteEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE contentId = :contentId AND contentType = :type AND playlistId = :playlistId)")
    fun observeIsFavorite(contentId: String, type: ContentType, playlistId: Long): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE contentId = :contentId AND contentType = :type AND playlistId = :playlistId")
    suspend fun delete(contentId: String, type: ContentType, playlistId: Long)

    @Query("DELETE FROM favorites WHERE playlistId = :playlistId")
    suspend fun deleteFor(playlistId: Long)
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders WHERE endMillis > :now ORDER BY startMillis")
    fun observeUpcoming(now: Long): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE endMillis > :now ORDER BY startMillis")
    suspend fun upcoming(now: Long): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getById(id: Long): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE epgChannelId = :epgChannelId AND startMillis = :startMillis LIMIT 1")
    suspend fun find(epgChannelId: String, startMillis: Long): ReminderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reminder: ReminderEntity): Long

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM reminders WHERE endMillis <= :now")
    suspend fun deleteExpired(now: Long)

    @Query("DELETE FROM reminders WHERE playlistId = :playlistId")
    suspend fun deleteFor(playlistId: Long)
}

@Dao
interface WatchProgressDao {
    @Query("SELECT * FROM watch_progress WHERE playlistId = :playlistId AND contentType != 'LIVE' AND durationMillis > 0 AND positionMillis * 1.0 / durationMillis < 0.95 ORDER BY updatedAt DESC LIMIT :limit")
    fun observeContinueWatching(playlistId: Long, limit: Int): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM watch_progress WHERE playlistId = :playlistId AND contentId = :contentId AND contentType = :type")
    suspend fun get(playlistId: Long, contentId: String, type: ContentType): WatchProgressEntity?

    @Query("SELECT * FROM watch_progress WHERE playlistId = :playlistId AND seriesId = :seriesId")
    fun observeForSeries(playlistId: Long, seriesId: String): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM watch_progress WHERE playlistId = :playlistId AND seriesId = :seriesId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestForSeries(playlistId: Long, seriesId: String): WatchProgressEntity?

    @Upsert
    suspend fun upsert(progress: WatchProgressEntity)

    @Query("DELETE FROM watch_progress WHERE playlistId = :playlistId AND contentId = :contentId AND contentType = :type")
    suspend fun delete(playlistId: Long, contentId: String, type: ContentType)

    @Query("DELETE FROM watch_progress WHERE playlistId = :playlistId")
    suspend fun deleteFor(playlistId: Long)
}

@Dao
interface TmdbMetadataDao {
    @Query("SELECT * FROM tmdb_metadata WHERE playlistId = :playlistId AND contentId = :contentId AND type = :type")
    suspend fun get(playlistId: Long, contentId: String, type: ContentType): TmdbMetadataEntity?

    @Upsert
    suspend fun upsert(entity: TmdbMetadataEntity)

    @Query(
        """
        UPDATE movies SET
            posterUrl = COALESCE(NULLIF(posterUrl, ''), (SELECT t.posterUrl FROM tmdb_metadata t WHERE t.playlistId = movies.playlistId AND t.contentId = movies.id AND t.type = 'MOVIE')),
            backdropUrl = COALESCE(NULLIF(backdropUrl, ''), (SELECT t.backdropUrl FROM tmdb_metadata t WHERE t.playlistId = movies.playlistId AND t.contentId = movies.id AND t.type = 'MOVIE')),
            synopsis = COALESCE(NULLIF(synopsis, ''), (SELECT t.synopsis FROM tmdb_metadata t WHERE t.playlistId = movies.playlistId AND t.contentId = movies.id AND t.type = 'MOVIE')),
            releaseYear = COALESCE(NULLIF(releaseYear, ''), (SELECT t.releaseYear FROM tmdb_metadata t WHERE t.playlistId = movies.playlistId AND t.contentId = movies.id AND t.type = 'MOVIE')),
            rating = COALESCE(rating, (SELECT t.rating FROM tmdb_metadata t WHERE t.playlistId = movies.playlistId AND t.contentId = movies.id AND t.type = 'MOVIE')),
            `cast` = COALESCE(NULLIF(`cast`, ''), (SELECT t.`cast` FROM tmdb_metadata t WHERE t.playlistId = movies.playlistId AND t.contentId = movies.id AND t.type = 'MOVIE')),
            director = COALESCE(NULLIF(director, ''), (SELECT t.director FROM tmdb_metadata t WHERE t.playlistId = movies.playlistId AND t.contentId = movies.id AND t.type = 'MOVIE')),
            genre = COALESCE(NULLIF(genre, ''), (SELECT t.genre FROM tmdb_metadata t WHERE t.playlistId = movies.playlistId AND t.contentId = movies.id AND t.type = 'MOVIE')),
            durationSeconds = COALESCE(durationSeconds, (SELECT t.durationSeconds FROM tmdb_metadata t WHERE t.playlistId = movies.playlistId AND t.contentId = movies.id AND t.type = 'MOVIE')),
            tmdbId = (SELECT t.tmdbId FROM tmdb_metadata t WHERE t.playlistId = movies.playlistId AND t.contentId = movies.id AND t.type = 'MOVIE'),
            tmdbCheckedAt = (SELECT t.checkedAt FROM tmdb_metadata t WHERE t.playlistId = movies.playlistId AND t.contentId = movies.id AND t.type = 'MOVIE')
        WHERE playlistId = :playlistId
          AND EXISTS (SELECT 1 FROM tmdb_metadata t WHERE t.playlistId = movies.playlistId AND t.contentId = movies.id AND t.type = 'MOVIE')
        """,
    )
    suspend fun applyToMovies(playlistId: Long)

    @Query(
        """
        UPDATE series SET
            posterUrl = COALESCE(NULLIF(posterUrl, ''), (SELECT t.posterUrl FROM tmdb_metadata t WHERE t.playlistId = series.playlistId AND t.contentId = series.id AND t.type = 'SERIES')),
            backdropUrl = COALESCE(NULLIF(backdropUrl, ''), (SELECT t.backdropUrl FROM tmdb_metadata t WHERE t.playlistId = series.playlistId AND t.contentId = series.id AND t.type = 'SERIES')),
            synopsis = COALESCE(NULLIF(synopsis, ''), (SELECT t.synopsis FROM tmdb_metadata t WHERE t.playlistId = series.playlistId AND t.contentId = series.id AND t.type = 'SERIES')),
            releaseYear = COALESCE(NULLIF(releaseYear, ''), (SELECT t.releaseYear FROM tmdb_metadata t WHERE t.playlistId = series.playlistId AND t.contentId = series.id AND t.type = 'SERIES')),
            rating = COALESCE(rating, (SELECT t.rating FROM tmdb_metadata t WHERE t.playlistId = series.playlistId AND t.contentId = series.id AND t.type = 'SERIES')),
            `cast` = COALESCE(NULLIF(`cast`, ''), (SELECT t.`cast` FROM tmdb_metadata t WHERE t.playlistId = series.playlistId AND t.contentId = series.id AND t.type = 'SERIES')),
            genre = COALESCE(NULLIF(genre, ''), (SELECT t.genre FROM tmdb_metadata t WHERE t.playlistId = series.playlistId AND t.contentId = series.id AND t.type = 'SERIES')),
            tmdbId = (SELECT t.tmdbId FROM tmdb_metadata t WHERE t.playlistId = series.playlistId AND t.contentId = series.id AND t.type = 'SERIES'),
            tmdbCheckedAt = (SELECT t.checkedAt FROM tmdb_metadata t WHERE t.playlistId = series.playlistId AND t.contentId = series.id AND t.type = 'SERIES')
        WHERE playlistId = :playlistId
          AND EXISTS (SELECT 1 FROM tmdb_metadata t WHERE t.playlistId = series.playlistId AND t.contentId = series.id AND t.type = 'SERIES')
        """,
    )
    suspend fun applyToSeries(playlistId: Long)

    @Query("DELETE FROM tmdb_metadata WHERE playlistId = :playlistId")
    suspend fun deleteFor(playlistId: Long)
}
