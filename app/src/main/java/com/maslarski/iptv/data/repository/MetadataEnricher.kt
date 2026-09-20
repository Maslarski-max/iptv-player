package com.maslarski.iptv.data.repository

import com.maslarski.iptv.data.local.IptvDatabase
import com.maslarski.iptv.data.local.entity.MovieEntity
import com.maslarski.iptv.data.local.entity.SeriesEntity
import com.maslarski.iptv.data.local.entity.TmdbMetadataEntity
import com.maslarski.iptv.data.remote.tmdb.TmdbClient
import com.maslarski.iptv.data.remote.tmdb.TmdbMetadata
import com.maslarski.iptv.domain.model.ContentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fills in missing posters, backdrops, synopsis, ratings, cast and genres from TMDB.
 *
 * Items are enriched (a) on demand when a details screen opens and (b) in a low-priority
 * background sweep after each playlist sync. Lookups (including misses) are cached in
 * `tmdb_metadata`, which survives re-syncs, and re-applied to the freshly inserted rows.
 */
@Singleton
class MetadataEnricher @Inject constructor(
    private val db: IptvDatabase,
    private val tmdb: TmdbClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sweepMutex = Mutex()
    private var sweepJob: Job? = null

    suspend fun enrichMovie(playlistId: Long, id: String) {
        val movie = db.movieDao().getById(playlistId, id) ?: return
        if (!movie.needsEnrichment() || !tmdb.isConfigured()) return
        val cached = db.tmdbMetadataDao().get(playlistId, id, ContentType.MOVIE)
        val entry = if (cached != null && cached.isFresh()) cached else {
            val meta = runCatching { tmdb.findMovie(movie.title, movie.releaseYear) }.getOrNull()
            meta.toEntity(playlistId, id, ContentType.MOVIE).also { db.tmdbMetadataDao().upsert(it) }
        }
        db.movieDao().insertAll(listOf(movie.mergedWith(entry)))
    }

    suspend fun enrichSeries(playlistId: Long, id: String) {
        val series = db.seriesDao().getById(playlistId, id) ?: return
        if (!series.needsEnrichment() || !tmdb.isConfigured()) return
        val cached = db.tmdbMetadataDao().get(playlistId, id, ContentType.SERIES)
        val entry = if (cached != null && cached.isFresh()) cached else {
            val meta = runCatching { tmdb.findSeries(series.title, series.releaseYear) }.getOrNull()
            meta.toEntity(playlistId, id, ContentType.SERIES).also { db.tmdbMetadataDao().upsert(it) }
        }
        db.seriesDao().insertAll(listOf(series.mergedWith(entry)))
    }

    /** Re-applies cached TMDB data after a sync replaced the movie/series rows. */
    suspend fun reapplyCached(playlistId: Long) {
        db.tmdbMetadataDao().applyToMovies(playlistId)
        db.tmdbMetadataDao().applyToSeries(playlistId)
    }

    /** Background sweep over items still missing artwork/synopsis; safe to call after every sync. */
    fun scheduleSweep(playlistId: Long, maxItems: Int = SWEEP_BATCH) {
        sweepJob?.cancel()
        sweepJob = scope.launch {
            if (!tmdb.isConfigured()) return@launch
            sweepMutex.withLock {
                val threshold = System.currentTimeMillis() - RECHECK_AFTER_MS
                for (m in db.movieDao().needingEnrichment(playlistId, threshold, maxItems)) {
                    if (!isActive) return@launch
                    enrichMovie(playlistId, m.id)
                    delay(REQUEST_SPACING_MS)
                }
                for (s in db.seriesDao().needingEnrichment(playlistId, threshold, maxItems)) {
                    if (!isActive) return@launch
                    enrichSeries(playlistId, s.id)
                    delay(REQUEST_SPACING_MS)
                }
            }
        }
    }

    private fun TmdbMetadataEntity.isFresh() = System.currentTimeMillis() - checkedAt < RECHECK_AFTER_MS

    private fun MovieEntity.needsEnrichment(): Boolean {
        val stale = tmdbCheckedAt?.let { System.currentTimeMillis() - it > RECHECK_AFTER_MS } ?: true
        return stale && (posterUrl.isNullOrBlank() || synopsis.isNullOrBlank())
    }

    private fun SeriesEntity.needsEnrichment(): Boolean {
        val stale = tmdbCheckedAt?.let { System.currentTimeMillis() - it > RECHECK_AFTER_MS } ?: true
        return stale && (posterUrl.isNullOrBlank() || synopsis.isNullOrBlank())
    }

    private fun TmdbMetadata?.toEntity(playlistId: Long, contentId: String, type: ContentType) = TmdbMetadataEntity(
        contentId = contentId,
        playlistId = playlistId,
        type = type,
        tmdbId = this?.tmdbId,
        posterUrl = this?.posterUrl,
        backdropUrl = this?.backdropUrl,
        synopsis = this?.synopsis,
        releaseYear = this?.releaseYear,
        rating = this?.rating,
        genre = this?.genre,
        cast = this?.cast,
        director = this?.director,
        durationSeconds = this?.durationSeconds,
        checkedAt = System.currentTimeMillis(),
    )

    private fun MovieEntity.mergedWith(m: TmdbMetadataEntity): MovieEntity = copy(
        posterUrl = posterUrl.orIfBlank(m.posterUrl),
        backdropUrl = backdropUrl.orIfBlank(m.backdropUrl),
        synopsis = synopsis.orIfBlank(m.synopsis),
        releaseYear = releaseYear.orIfBlank(m.releaseYear),
        rating = rating ?: m.rating,
        cast = cast.orIfBlank(m.cast),
        director = director.orIfBlank(m.director),
        genre = genre.orIfBlank(m.genre),
        durationSeconds = durationSeconds ?: m.durationSeconds,
        tmdbId = m.tmdbId,
        tmdbCheckedAt = m.checkedAt,
    )

    private fun SeriesEntity.mergedWith(m: TmdbMetadataEntity): SeriesEntity = copy(
        posterUrl = posterUrl.orIfBlank(m.posterUrl),
        backdropUrl = backdropUrl.orIfBlank(m.backdropUrl),
        synopsis = synopsis.orIfBlank(m.synopsis),
        releaseYear = releaseYear.orIfBlank(m.releaseYear),
        rating = rating ?: m.rating,
        cast = cast.orIfBlank(m.cast),
        genre = genre.orIfBlank(m.genre),
        tmdbId = m.tmdbId,
        tmdbCheckedAt = m.checkedAt,
    )

    private fun String?.orIfBlank(other: String?): String? = if (isNullOrBlank()) other else this

    companion object {
        const val RECHECK_AFTER_MS = 7 * 24 * 60 * 60 * 1000L
        const val REQUEST_SPACING_MS = 250L
        const val SWEEP_BATCH = 150
    }
}
