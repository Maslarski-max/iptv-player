package com.maslarski.iptv.data.repository

import androidx.room.withTransaction
import com.maslarski.iptv.data.local.IptvDatabase
import com.maslarski.iptv.data.local.entity.FavoriteEntity
import com.maslarski.iptv.data.local.entity.WatchProgressEntity
import com.maslarski.iptv.data.mapper.toDomain
import com.maslarski.iptv.data.mapper.toMediaItem
import com.maslarski.iptv.data.remote.xtream.XtreamClient
import com.maslarski.iptv.domain.model.Category
import com.maslarski.iptv.domain.model.Channel
import com.maslarski.iptv.domain.model.ContentType
import com.maslarski.iptv.domain.model.EpgProgram
import com.maslarski.iptv.domain.model.Episode
import com.maslarski.iptv.domain.model.MediaItem
import com.maslarski.iptv.domain.model.Movie
import com.maslarski.iptv.domain.model.Playlist
import com.maslarski.iptv.domain.model.PlaylistType
import com.maslarski.iptv.domain.model.SearchResults
import com.maslarski.iptv.domain.model.Series
import com.maslarski.iptv.domain.model.WatchProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ContentRepository @Inject constructor(
    private val db: IptvDatabase,
    private val xtream: XtreamClient,
    private val playlists: PlaylistRepository,
    private val enricher: MetadataEnricher,
) {
    // ------------------------------------------------------------ categories

    fun categories(playlistId: Long, type: ContentType): Flow<List<Category>> {
        val counts = when (type) {
            ContentType.LIVE -> db.channelDao().observeCategoryCounts(playlistId)
            ContentType.MOVIE -> db.movieDao().observeCategoryCounts(playlistId)
            ContentType.SERIES -> db.seriesDao().observeCategoryCounts(playlistId)
        }
        return combine(db.categoryDao().observe(playlistId, type), counts) { cats, cnts ->
            val byId = cnts.associate { it.categoryId to it.count }
            cats.map { it.toDomain(byId[it.id] ?: 0) }.filter { it.itemCount > 0 }
        }
    }

    /** All categories of a type, hidden ones included, for the management editor. */
    fun editableCategories(playlistId: Long, type: ContentType): Flow<List<Category>> {
        val counts = when (type) {
            ContentType.LIVE -> db.channelDao().observeCategoryCounts(playlistId)
            ContentType.MOVIE -> db.movieDao().observeCategoryCounts(playlistId)
            ContentType.SERIES -> db.seriesDao().observeCategoryCounts(playlistId)
        }
        return combine(db.categoryDao().observeAllForEdit(playlistId, type), counts) { cats, cnts ->
            val byId = cnts.associate { it.categoryId to it.count }
            cats.map { it.toDomain(byId[it.id] ?: 0) }.filter { it.itemCount > 0 }
        }
    }

    /** All live channels of a category, hidden ones included, for the management editor. */
    fun editableChannels(playlistId: Long, categoryId: String): Flow<List<Channel>> =
        combine(db.channelDao().observeByCategoryForEdit(playlistId, categoryId), favoriteIds(playlistId, ContentType.LIVE)) { list, favs ->
            list.map { it.toDomain(it.id in favs) }
        }

    /** Persists [ordered] (the full list of one type) as the new category sequence. */
    suspend fun saveCategoryOrder(ordered: List<Category>) = db.withTransaction {
        ordered.forEachIndexed { index, c -> db.categoryDao().setOrder(c.id, c.playlistId, c.type, index) }
    }

    /**
     * Persists [ordered] (the full channel list of one category) by redistributing the existing order
     * keys of those channels, so the category's slot within the global "all channels" list is unchanged.
     */
    suspend fun saveChannelOrder(ordered: List<Channel>) {
        if (ordered.isEmpty()) return
        val playlistId = ordered.first().playlistId
        val rows = ordered.mapNotNull { db.channelDao().getById(playlistId, it.id) }
        if (rows.size != ordered.size) return
        var keys = rows.map { it.customOrder }.sorted()
        if (keys.toSet().size != keys.size) keys = rows.map { it.sortOrder }.sorted()
        if (keys.toSet().size != keys.size) keys = rows.indices.toList()
        db.withTransaction {
            ordered.forEachIndexed { index, c -> db.channelDao().setOrder(c.id, playlistId, keys[index]) }
        }
    }

    suspend fun setCategoryVisible(category: Category, visible: Boolean) =
        db.categoryDao().setVisible(category.id, category.playlistId, category.type, visible)

    suspend fun setChannelVisible(channel: Channel, visible: Boolean) =
        db.channelDao().setVisible(channel.id, channel.playlistId, visible)

    fun lockedCategoryIds(playlistId: Long): Flow<Set<String>> =
        db.categoryDao().observeLocked(playlistId).map { list -> list.map { it.id }.toSet() }

    suspend fun setCategoryLocked(category: Category, locked: Boolean) =
        db.categoryDao().setLocked(category.id, category.playlistId, category.type, locked)

    // ------------------------------------------------------------ live

    fun channels(playlistId: Long, categoryId: String?): Flow<List<Channel>> {
        val source = if (categoryId == null) db.channelDao().observeAll(playlistId)
        else db.channelDao().observeByCategory(playlistId, categoryId)
        return combine(source, favoriteIds(playlistId, ContentType.LIVE)) { list, favs ->
            list.map { it.toDomain(isFavorite = it.id in favs) }
        }
    }

    /** Favorite live channels in the order they were added (matches the Favorites screen). */
    fun favoriteChannels(playlistId: Long): Flow<List<Channel>> =
        db.favoriteDao().observeByType(playlistId, ContentType.LIVE).flatMapLatest { favs ->
            if (favs.isEmpty()) return@flatMapLatest flowOf(emptyList())
            val order = favs.withIndex().associate { (i, f) -> f.contentId to i }
            db.channelDao().observeByIds(playlistId, favs.map { it.contentId }).map { list ->
                list.sortedBy { order[it.id] ?: Int.MAX_VALUE }.map { it.toDomain(isFavorite = true) }
            }
        }

    fun featuredChannels(playlistId: Long, limit: Int = 12): Flow<List<Channel>> =
        combine(db.channelDao().observeFirst(playlistId, limit), favoriteIds(playlistId, ContentType.LIVE)) { list, favs ->
            list.map { it.toDomain(isFavorite = it.id in favs) }
        }

    suspend fun channel(playlistId: Long, id: String): Channel? = db.channelDao().getById(playlistId, id)?.toDomain()

    fun nowPlaying(epgChannelIds: List<String>): Flow<Map<String, EpgProgram>> =
        if (epgChannelIds.isEmpty()) flowOf(emptyMap())
        else db.epgDao().observeNowPlaying(epgChannelIds.distinct().take(900), System.currentTimeMillis())
            .map { list -> list.associate { it.epgChannelId to it.toDomain() } }

    fun programs(epgChannelIds: List<String>, from: Long, to: Long): Flow<Map<String, List<EpgProgram>>> =
        if (epgChannelIds.isEmpty()) flowOf(emptyMap())
        else db.epgDao().observeForChannels(epgChannelIds.distinct().take(900), from, to)
            .map { list -> list.groupBy({ it.epgChannelId }, { it.toDomain() }) }

    fun programsForChannel(epgChannelId: String, from: Long, to: Long): Flow<List<EpgProgram>> =
        db.epgDao().observeForChannel(epgChannelId, from, to).map { list -> list.map { it.toDomain() } }

    // ------------------------------------------------------------ movies

    fun movies(playlistId: Long, categoryId: String?): Flow<List<Movie>> {
        val source = if (categoryId == null) db.movieDao().observeAll(playlistId)
        else db.movieDao().observeByCategory(playlistId, categoryId)
        return combine(source, favoriteIds(playlistId, ContentType.MOVIE)) { list, favs ->
            list.map { it.toDomain(isFavorite = it.id in favs) }
        }
    }

    fun recentMovies(playlistId: Long, limit: Int = 20): Flow<List<Movie>> =
        combine(db.movieDao().observeRecent(playlistId, limit), favoriteIds(playlistId, ContentType.MOVIE)) { list, favs ->
            list.map { it.toDomain(isFavorite = it.id in favs) }
        }

    fun topRatedMovies(playlistId: Long, limit: Int = 10): Flow<List<Movie>> =
        db.movieDao().observeTopRated(playlistId, limit).map { list -> list.map { it.toDomain() } }

    fun movie(playlistId: Long, id: String): Flow<Movie?> = combine(
        db.movieDao().observeById(playlistId, id),
        db.favoriteDao().observeIsFavorite(id, ContentType.MOVIE, playlistId),
    ) { movie, fav -> movie?.toDomain(isFavorite = fav, progress = progress(playlistId, id, ContentType.MOVIE)) }

    suspend fun refreshMovieDetails(playlistId: Long, id: String) {
        val playlist = playlists.getById(playlistId) ?: return
        if (playlist.type == PlaylistType.XTREAM) {
            val existing = db.movieDao().getById(playlistId, id) ?: return
            runCatching { xtream.movieDetails(playlist, existing) }.onSuccess { db.movieDao().insertAll(listOf(it)) }
        }
        enricher.enrichMovie(playlistId, id)
    }

    // ------------------------------------------------------------ series

    fun series(playlistId: Long, categoryId: String?): Flow<List<Series>> {
        val source = if (categoryId == null) db.seriesDao().observeAll(playlistId)
        else db.seriesDao().observeByCategory(playlistId, categoryId)
        return combine(source, favoriteIds(playlistId, ContentType.SERIES)) { list, favs ->
            list.map { it.toDomain(isFavorite = it.id in favs) }
        }
    }

    fun recentSeries(playlistId: Long, limit: Int = 20): Flow<List<Series>> =
        combine(db.seriesDao().observeRecent(playlistId, limit), favoriteIds(playlistId, ContentType.SERIES)) { list, favs ->
            list.map { it.toDomain(isFavorite = it.id in favs) }
        }

    fun seriesById(playlistId: Long, id: String): Flow<Series?> = combine(
        db.seriesDao().observeById(playlistId, id),
        db.favoriteDao().observeIsFavorite(id, ContentType.SERIES, playlistId),
    ) { s, fav -> s?.toDomain(isFavorite = fav) }

    fun episodes(playlistId: Long, seriesId: String): Flow<List<Episode>> = combine(
        db.episodeDao().observeForSeries(playlistId, seriesId),
        db.watchProgressDao().observeForSeries(playlistId, seriesId),
    ) { eps, progress ->
        val byId = progress.associateBy { it.contentId }
        eps.map { it.toDomain(progress = byId[it.id]?.toDomain()) }
    }

    suspend fun refreshSeriesDetails(playlistId: Long, seriesId: String, force: Boolean = false) {
        val playlist = playlists.getById(playlistId) ?: return
        if (playlist.type == PlaylistType.XTREAM) {
            val existing = db.seriesDao().getById(playlistId, seriesId) ?: return
            val stale = existing.detailsFetchedAt?.let { System.currentTimeMillis() - it > 6 * 60 * 60 * 1000L } ?: true
            if (stale || force) {
                runCatching { xtream.seriesDetails(playlist, existing) }.onSuccess { details ->
                    db.episodeDao().deleteForSeries(playlistId, seriesId)
                    db.episodeDao().insertAll(details.episodes)
                    db.seriesDao().insertAll(listOf(details.series))
                }
            }
        }
        enricher.enrichSeries(playlistId, seriesId)
    }

    suspend fun episode(playlistId: Long, id: String): Episode? = db.episodeDao().getById(playlistId, id)?.toDomain()

    suspend fun nextEpisode(playlistId: Long, current: Episode): Episode? =
        db.episodeDao().nextEpisode(playlistId, current.seriesId, current.seasonNumber, current.episodeNumber)?.toDomain()

    suspend fun latestEpisodeForSeries(playlistId: Long, seriesId: String): Episode? {
        val latest = db.watchProgressDao().latestForSeries(playlistId, seriesId) ?: return null
        val episode = db.episodeDao().getById(playlistId, latest.contentId) ?: return null
        val progress = latest.toDomain()
        return if (progress.isFinished) nextEpisode(playlistId, episode.toDomain()) else episode.toDomain(progress)
    }

    // ------------------------------------------------------------ favorites

    private fun favoriteIds(playlistId: Long, type: ContentType): Flow<Set<String>> =
        db.favoriteDao().observeByType(playlistId, type).map { list -> list.map { it.contentId }.toSet() }

    fun isFavorite(playlistId: Long, id: String, type: ContentType): Flow<Boolean> =
        db.favoriteDao().observeIsFavorite(id, type, playlistId)

    suspend fun toggleFavorite(playlistId: Long, id: String, type: ContentType) {
        val entity = FavoriteEntity(id, type, playlistId, System.currentTimeMillis())
        val isFav = db.favoriteDao().observeIsFavorite(id, type, playlistId).first()
        if (isFav) db.favoriteDao().delete(id, type, playlistId) else db.favoriteDao().insert(entity)
    }

    fun favorites(playlistId: Long): Flow<List<MediaItem>> =
        db.favoriteDao().observeAll(playlistId).flatMapLatest { favs ->
            if (favs.isEmpty()) return@flatMapLatest flowOf(emptyList())
            val channelIds = favs.filter { it.contentType == ContentType.LIVE }.map { it.contentId }
            val movieIds = favs.filter { it.contentType == ContentType.MOVIE }.map { it.contentId }
            val seriesIds = favs.filter { it.contentType == ContentType.SERIES }.map { it.contentId }
            combine(
                db.channelDao().observeByIds(playlistId, channelIds.ifEmpty { listOf("-") }),
                db.movieDao().observeByIds(playlistId, movieIds.ifEmpty { listOf("-") }),
                db.seriesDao().observeByIds(playlistId, seriesIds.ifEmpty { listOf("-") }),
            ) { channels, movies, series ->
                val order = favs.withIndex().associate { (i, f) -> (f.contentType to f.contentId) to i }
                val items = channels.map { it.toDomain(true).toMediaItem() } +
                    movies.map { it.toDomain(true).toMediaItem() } +
                    series.map { it.toDomain(true).toMediaItem() }
                items.sortedBy { order[it.type to it.id] ?: Int.MAX_VALUE }
            }
        }

    // ------------------------------------------------------------ progress

    suspend fun progress(playlistId: Long, id: String, type: ContentType): WatchProgress? =
        db.watchProgressDao().get(playlistId, id, type)?.toDomain()

    suspend fun saveProgress(
        playlistId: Long, id: String, type: ContentType, position: Long, duration: Long, seriesId: String?,
    ) {
        if (type == ContentType.LIVE || duration <= 0) return
        db.watchProgressDao().upsert(
            WatchProgressEntity(id, type, playlistId, position, duration, System.currentTimeMillis(), seriesId),
        )
    }

    fun continueWatching(playlistId: Long, limit: Int = 20): Flow<List<MediaItem>> =
        db.watchProgressDao().observeContinueWatching(playlistId, limit).flatMapLatest { progress ->
            if (progress.isEmpty()) return@flatMapLatest flowOf(emptyList())
            val movieIds = progress.filter { it.contentType == ContentType.MOVIE }.map { it.contentId }
            val episodeIds = progress.filter { it.contentType == ContentType.SERIES }.map { it.contentId }
            val seriesIds = progress.mapNotNull { it.seriesId }.distinct()
            combine(
                db.movieDao().observeByIds(playlistId, movieIds.ifEmpty { listOf("-") }),
                db.episodeDao().observeByIds(playlistId, episodeIds.ifEmpty { listOf("-") }),
                db.seriesDao().observeByIds(playlistId, seriesIds.ifEmpty { listOf("-") }),
            ) { movies, episodes, series ->
                val byKey = progress.associateBy { it.contentType to it.contentId }
                val seriesById = series.associateBy { it.id }
                val movieItems = movies.map { m ->
                    m.toDomain(progress = byKey[ContentType.MOVIE to m.id]?.toDomain()).toMediaItem()
                }
                val episodeItems = episodes.map { e ->
                    val parent = seriesById[e.seriesId]
                    e.toDomain(progress = byKey[ContentType.SERIES to e.id]?.toDomain())
                        .toMediaItem(parent?.title, parent?.posterUrl)
                }
                (movieItems + episodeItems).sortedByDescending { byKey[it.type to it.id]?.updatedAt ?: 0L }
            }
        }

    // ------------------------------------------------------------ search

    suspend fun search(playlistId: Long, query: String, limit: Int = 60): SearchResults = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext SearchResults()
        coroutineScope {
            val c = async { db.channelDao().search(playlistId, q, limit) }
            val m = async { db.movieDao().search(playlistId, q, limit) }
            val s = async { db.seriesDao().search(playlistId, q, limit) }
            SearchResults(
                channels = c.await().map { it.toDomain() },
                movies = m.await().map { it.toDomain() },
                series = s.await().map { it.toDomain() },
            )
        }
    }

    suspend fun activePlaylist(): Playlist? = playlists.getActive()
}
