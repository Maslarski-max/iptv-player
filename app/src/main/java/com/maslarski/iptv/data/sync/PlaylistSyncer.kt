package com.maslarski.iptv.data.sync

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.maslarski.iptv.data.local.IptvDatabase
import com.maslarski.iptv.data.local.entity.CategoryEntity
import com.maslarski.iptv.data.local.entity.ChannelEntity
import com.maslarski.iptv.data.local.entity.EpgProgramEntity
import com.maslarski.iptv.data.local.entity.EpisodeEntity
import com.maslarski.iptv.data.local.entity.MovieEntity
import com.maslarski.iptv.data.local.entity.SeriesEntity
import com.maslarski.iptv.data.parser.M3uEntry
import com.maslarski.iptv.data.parser.M3uParseEvent
import com.maslarski.iptv.data.parser.M3uParser
import com.maslarski.iptv.data.parser.XmltvEvent
import com.maslarski.iptv.data.parser.XmltvParser
import com.maslarski.iptv.data.remote.xtream.XtreamClient
import com.maslarski.iptv.data.repository.MetadataEnricher
import com.maslarski.iptv.data.settings.SettingsRepository
import com.maslarski.iptv.domain.model.ContentType
import com.maslarski.iptv.domain.model.Playlist
import com.maslarski.iptv.domain.model.PlaylistType
import com.maslarski.iptv.domain.model.SyncStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates downloading, parsing and persisting playlists + EPG. All heavy work happens on
 * [Dispatchers.IO]; parsed items are streamed into Room in batches so memory stays flat even for
 * playlists with hundreds of thousands of entries.
 */
@Singleton
class PlaylistSyncer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: IptvDatabase,
    private val m3uParser: M3uParser,
    private val xmltvParser: XmltvParser,
    private val xtream: XtreamClient,
    private val settings: SettingsRepository,
    private val enricher: MetadataEnricher,
) {
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()
    private val mutex = Mutex()

    suspend fun sync(playlist: Playlist, includeEpg: Boolean = true): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                _status.value = SyncStatus(isSyncing = true, playlistId = playlist.id, message = "Downloading playlist", progress = null)
                when (playlist.type) {
                    PlaylistType.M3U -> syncM3u(playlist)
                    PlaylistType.XTREAM -> syncXtream(playlist)
                }
                enricher.reapplyCached(playlist.id)
                if (includeEpg) syncEpg(playlist)
                db.playlistDao().updateSyncStats(
                    id = playlist.id,
                    at = System.currentTimeMillis(),
                    channels = db.channelDao().count(playlist.id),
                    movies = db.movieDao().count(playlist.id),
                    series = db.seriesDao().count(playlist.id),
                )
                _status.value = SyncStatus(isSyncing = false, message = "Up to date", progress = 1f)
                enricher.scheduleSweep(playlist.id)
            }.onFailure { e ->
                _status.value = SyncStatus(isSyncing = false, error = e.message ?: e::class.simpleName)
            }
        }
    }

    // ---------------------------------------------------------------- M3U

    private suspend fun syncM3u(playlist: Playlist) {
        val stream = openM3u(playlist.url)
        val channels = ArrayList<ChannelEntity>(M3uParser.DEFAULT_BATCH)
        val movies = ArrayList<MovieEntity>()
        val seriesMap = LinkedHashMap<String, SeriesEntity>()
        val episodes = ArrayList<EpisodeEntity>()
        val categories = HashMap<Pair<ContentType, String>, CategoryEntity>()
        var order = 0
        var epgUrlFromHeader: String? = null

        db.withTransaction {
            db.channelDao().deleteFor(playlist.id)
            db.movieDao().deleteFor(playlist.id)
            db.seriesDao().deleteFor(playlist.id)
            db.episodeDao().deleteFor(playlist.id)
        }

        m3uParser.parse(stream).collect { event ->
            when (event) {
                is M3uParseEvent.Header -> epgUrlFromHeader = event.epgUrl
                is M3uParseEvent.Batch -> {
                    for (entry in event.entries) {
                        val type = entry.contentType
                        val group = entry.group ?: "Uncategorized"
                        val categoryId = categoryIdFor(group)
                        categories.getOrPut(type to categoryId) {
                            CategoryEntity(id = categoryId, playlistId = playlist.id, name = group, type = type)
                        }
                        when (type) {
                            ContentType.LIVE -> channels += entry.toChannel(playlist.id, categoryId, order++)
                            ContentType.MOVIE -> movies += entry.toMovie(playlist.id, categoryId)
                            ContentType.SERIES -> {
                                val info = entry.seriesInfo()
                                if (info == null) {
                                    movies += entry.toMovie(playlist.id, categoryId)
                                } else {
                                    val seriesId = categoryIdFor("${group}|${info.seriesTitle}")
                                    seriesMap.getOrPut(seriesId) {
                                        SeriesEntity(
                                            id = seriesId, playlistId = playlist.id, title = info.seriesTitle,
                                            posterUrl = entry.logo, backdropUrl = null, categoryId = categoryId,
                                            categoryName = group, synopsis = null, releaseYear = null, rating = null,
                                            cast = null, genre = null,
                                        )
                                    }
                                    episodes += EpisodeEntity(
                                        id = entry.stableId, seriesId = seriesId, playlistId = playlist.id,
                                        seasonNumber = info.season, episodeNumber = info.episode,
                                        title = entry.title, streamUrl = entry.url, thumbnailUrl = entry.logo,
                                        synopsis = null, durationSeconds = entry.durationSeconds,
                                    )
                                }
                            }
                        }
                    }
                    flushIfNeeded(channels, movies, episodes)
                    _status.value = SyncStatus(isSyncing = true, playlistId = playlist.id, message = "Parsing ${event.parsedSoFar} items")
                }
                is M3uParseEvent.Done -> Unit
            }
        }
        flushIfNeeded(channels, movies, episodes, force = true)
        db.withTransaction {
            val locked = db.categoryDao().getLockedIds(playlist.id)
            ContentType.entries.forEach { db.categoryDao().deleteFor(playlist.id, it) }
            db.categoryDao().upsertAll(categories.values.toList())
            if (locked.isNotEmpty()) db.categoryDao().relock(playlist.id, locked)
            seriesMap.values.chunked(500).forEach { db.seriesDao().insertAll(it) }
        }
        if (playlist.epgUrl.isNullOrBlank() && !epgUrlFromHeader.isNullOrBlank()) {
            db.playlistDao().getById(playlist.id)?.let { db.playlistDao().update(it.copy(epgUrl = epgUrlFromHeader)) }
        }
    }

    private suspend fun flushIfNeeded(
        channels: MutableList<ChannelEntity>,
        movies: MutableList<MovieEntity>,
        episodes: MutableList<EpisodeEntity>,
        force: Boolean = false,
    ) {
        if (!force && channels.size < 1000 && movies.size < 1000 && episodes.size < 1000) return
        db.withTransaction {
            if (channels.isNotEmpty()) db.channelDao().insertAll(channels.toList())
            if (movies.isNotEmpty()) db.movieDao().insertAll(movies.toList())
            if (episodes.isNotEmpty()) db.episodeDao().insertAll(episodes.toList())
        }
        channels.clear(); movies.clear(); episodes.clear()
    }

    private fun M3uEntry.toChannel(playlistId: Long, categoryId: String, order: Int) = ChannelEntity(
        id = stableId, playlistId = playlistId, name = title, streamUrl = url, logoUrl = logo,
        categoryId = categoryId, categoryName = group, epgChannelId = tvgId ?: tvgName,
        channelNumber = channelNumber, sortOrder = order,
    )

    private fun M3uEntry.toMovie(playlistId: Long, categoryId: String) = MovieEntity(
        id = stableId, playlistId = playlistId, title = title, streamUrl = url, posterUrl = logo,
        backdropUrl = null, categoryId = categoryId, categoryName = group, synopsis = null,
        releaseYear = YEAR_REGEX.find(title)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }, rating = null, cast = null,
        director = null, genre = null, durationSeconds = durationSeconds, addedAt = null,
    )

    private suspend fun openM3u(url: String): InputStream =
        if (url.startsWith("content://") || url.startsWith("file://")) {
            context.contentResolver.openInputStream(Uri.parse(url)) ?: error("Cannot open $url")
        } else {
            xtream.downloadM3u(url).byteStream()
        }

    // ---------------------------------------------------------------- Xtream

    private suspend fun syncXtream(playlist: Playlist) {
        xtream.authenticate(playlist)
        _status.value = SyncStatus(isSyncing = true, playlistId = playlist.id, message = "Loading categories")
        val liveCats = xtream.categories(playlist, ContentType.LIVE)
        val vodCats = xtream.categories(playlist, ContentType.MOVIE)
        val seriesCats = xtream.categories(playlist, ContentType.SERIES)

        _status.value = SyncStatus(isSyncing = true, playlistId = playlist.id, message = "Loading live channels")
        val channels = xtream.liveStreams(playlist, liveCats.associate { it.id to it.name })
        _status.value = SyncStatus(isSyncing = true, playlistId = playlist.id, message = "Loading movies")
        val movies = xtream.vodStreams(playlist, vodCats.associate { it.id to it.name })
        _status.value = SyncStatus(isSyncing = true, playlistId = playlist.id, message = "Loading series")
        val series = xtream.series(playlist, seriesCats.associate { it.id to it.name })

        db.withTransaction {
            val locked = db.categoryDao().getLockedIds(playlist.id)
            ContentType.entries.forEach { db.categoryDao().deleteFor(playlist.id, it) }
            db.categoryDao().upsertAll(liveCats + vodCats + seriesCats)
            if (locked.isNotEmpty()) db.categoryDao().relock(playlist.id, locked)
            db.channelDao().deleteFor(playlist.id)
            channels.chunked(1000).forEach { db.channelDao().insertAll(it) }
            db.movieDao().deleteFor(playlist.id)
            movies.chunked(1000).forEach { db.movieDao().insertAll(it) }
            db.seriesDao().deleteFor(playlist.id)
            series.chunked(1000).forEach { db.seriesDao().insertAll(it) }
        }
    }

    // ---------------------------------------------------------------- EPG

    private suspend fun syncEpg(playlist: Playlist) {
        val epgUrl = when {
            !playlist.epgUrl.isNullOrBlank() -> playlist.epgUrl
            playlist.type == PlaylistType.XTREAM -> xtream.epgUrl(playlist)
            else -> db.playlistDao().getById(playlist.id)?.epgUrl
        } ?: return
        _status.value = SyncStatus(isSyncing = true, playlistId = playlist.id, message = "Updating TV guide")
        val retentionDays = settings.current().epgRetentionDays
        val now = System.currentTimeMillis()
        val stream = openM3u(epgUrl)
        val staging = ArrayList<EpgProgramEntity>(4000)
        var first = true
        xmltvParser.parse(
            rawInput = stream,
            keepFrom = now - 12 * 60 * 60 * 1000L,
            keepUntil = now + retentionDays * 24 * 60 * 60 * 1000L,
        ).collect { event ->
            when (event) {
                is XmltvEvent.Channels -> Unit
                is XmltvEvent.Programs -> {
                    if (first) {
                        db.epgDao().deleteAll()
                        first = false
                    }
                    staging += event.programs
                    if (staging.size >= 4000) {
                        db.epgDao().insertAll(staging.toList())
                        staging.clear()
                    }
                    _status.value = SyncStatus(isSyncing = true, playlistId = playlist.id, message = "TV guide: ${event.parsedSoFar} programs")
                }
                is XmltvEvent.Done -> Unit
            }
        }
        if (staging.isNotEmpty()) db.epgDao().insertAll(staging)
        db.epgDao().deleteEndedBefore(now - 12 * 60 * 60 * 1000L)
    }

    companion object {
        private val YEAR_REGEX = Regex("""\((\d{4})\)|\b((?:19|20)\d{2})\b""")

        fun categoryIdFor(name: String): String {
            val digest = MessageDigest.getInstance("MD5").digest(name.lowercase().trim().toByteArray())
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
