package com.maslarski.iptv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maslarski.iptv.data.mapper.toMediaItem
import com.maslarski.iptv.data.repository.ContentRepository
import com.maslarski.iptv.data.repository.PlaylistRepository
import com.maslarski.iptv.data.sync.PlaylistSyncer
import com.maslarski.iptv.domain.model.MediaItem
import com.maslarski.iptv.domain.model.Movie
import com.maslarski.iptv.domain.model.Playlist
import com.maslarski.iptv.domain.model.Series
import com.maslarski.iptv.domain.model.SyncStatus
import com.maslarski.iptv.domain.parental.ParentalGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface Featured {
    val title: String
    val backdrop: String?
    val synopsis: String?
    val badge: String?
    data class OfMovie(val movie: Movie) : Featured {
        override val title get() = movie.title
        override val backdrop get() = movie.backdropUrl ?: movie.posterUrl
        override val synopsis get() = movie.synopsis
        override val badge get() = listOfNotNull(movie.releaseYear, movie.genre?.split(",")?.firstOrNull()?.trim()).joinToString(" · ").ifBlank { null }
    }
    data class OfSeries(val series: Series) : Featured {
        override val title get() = series.title
        override val backdrop get() = series.backdropUrl ?: series.posterUrl
        override val synopsis get() = series.synopsis
        override val badge get() = listOfNotNull(series.releaseYear, series.genre?.split(",")?.firstOrNull()?.trim()).joinToString(" · ").ifBlank { null }
    }
}

data class HomeUiState(
    val hasPlaylists: Boolean? = null,
    val playlist: Playlist? = null,
    val featured: List<Featured> = emptyList(),
    val continueWatching: List<MediaItem> = emptyList(),
    val favorites: List<MediaItem> = emptyList(),
    val liveNow: List<MediaItem> = emptyList(),
    val recentMovies: List<MediaItem> = emptyList(),
    val recentSeries: List<MediaItem> = emptyList(),
    val sync: SyncStatus = SyncStatus(),
    val lockedCategories: Set<String> = emptySet(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val playlists: PlaylistRepository,
    private val content: ContentRepository,
    private val syncer: PlaylistSyncer,
    private val gate: ParentalGate,
) : ViewModel() {

    private val active = playlists.activePlaylist

    private fun <T> perPlaylist(block: (Long) -> Flow<List<T>>): Flow<List<T>> =
        active.flatMapLatest { p -> if (p == null) flowOf(emptyList()) else block(p.id) }

    private val locked: Flow<Set<String>> = active.flatMapLatest { p ->
        if (p == null) flowOf(emptySet()) else combine(content.lockedCategoryIds(p.id), gate.enforcing) { ids, enforce -> if (enforce) ids else emptySet() }
    }

    private val featured = combine(
        perPlaylist { content.topRatedMovies(it, 6) },
        perPlaylist { content.recentSeries(it, 4) },
        locked,
    ) { movies, series, lockedIds ->
        val m = movies.filter { it.categoryId !in lockedIds }.map { Featured.OfMovie(it) }
        val s = series.filter { it.categoryId !in lockedIds }.map { Featured.OfSeries(it) }
        (m.take(4) + s.take(2)).ifEmpty { m + s }
    }

    private val rows = combine(
        perPlaylist { content.continueWatching(it) },
        perPlaylist { content.favorites(it) },
        perPlaylist { content.featuredChannels(it) },
        perPlaylist { content.recentMovies(it) },
        perPlaylist { content.recentSeries(it) },
    ) { cw, favs, channels, movies, series ->
        Rows(cw, favs, channels.map { it.toMediaItem() }, movies.map { it.toMediaItem() }, series.map { it.toMediaItem() })
    }

    private data class Rows(
        val continueWatching: List<MediaItem>, val favorites: List<MediaItem>, val live: List<MediaItem>,
        val movies: List<MediaItem>, val series: List<MediaItem>,
    )

    private val base = combine(playlists.playlists, active, featured) { all, playlist, feat ->
        HomeUiState(hasPlaylists = all.isNotEmpty(), playlist = playlist, featured = feat)
    }

    val state: StateFlow<HomeUiState> = combine(base, rows, syncer.status, locked) { b, r, sync, lockedIds ->
        b.copy(
            continueWatching = r.continueWatching,
            favorites = r.favorites,
            liveNow = r.live,
            recentMovies = r.movies,
            recentSeries = r.series,
            sync = sync,
            lockedCategories = lockedIds,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun refresh() {
        viewModelScope.launch {
            val p = playlists.getActive() ?: return@launch
            syncer.sync(p)
        }
    }

    fun toggleFavorite(item: MediaItem) {
        viewModelScope.launch { content.toggleFavorite(item.playlistId, item.seriesId ?: item.id, item.type) }
    }
}
