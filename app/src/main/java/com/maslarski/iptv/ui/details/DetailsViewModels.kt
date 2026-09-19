package com.maslarski.iptv.ui.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.maslarski.iptv.data.repository.ContentRepository
import com.maslarski.iptv.domain.model.ContentType
import com.maslarski.iptv.domain.model.Episode
import com.maslarski.iptv.domain.model.Movie
import com.maslarski.iptv.domain.model.Series
import com.maslarski.iptv.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MovieDetailsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val content: ContentRepository,
) : ViewModel() {
    val route = savedState.toRoute<Route.MovieDetails>()
    val movie: StateFlow<Movie?> = content.movie(route.playlistId, route.movieId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init { viewModelScope.launch { runCatching { content.refreshMovieDetails(route.playlistId, route.movieId) } } }

    fun toggleFavorite() = viewModelScope.launch { content.toggleFavorite(route.playlistId, route.movieId, ContentType.MOVIE) }
}

data class SeriesDetailsUiState(
    val series: Series? = null,
    val episodes: List<Episode> = emptyList(),
    val loadingEpisodes: Boolean = true,
) {
    val seasons: List<Int> get() = episodes.map { it.seasonNumber }.distinct().sorted()
    fun episodesOf(season: Int) = episodes.filter { it.seasonNumber == season }.sortedBy { it.episodeNumber }
    val nextUp: Episode?
        get() {
            val sorted = episodes.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
            val lastWatched = sorted.filter { it.progress != null }.maxByOrNull { it.progress!!.updatedAt } ?: return sorted.firstOrNull()
            if (!lastWatched.progress!!.isFinished) return lastWatched
            val idx = sorted.indexOf(lastWatched)
            return sorted.getOrNull(idx + 1) ?: lastWatched
        }
}

@HiltViewModel
class SeriesDetailsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val content: ContentRepository,
) : ViewModel() {
    val route = savedState.toRoute<Route.SeriesDetails>()

    val state: StateFlow<SeriesDetailsUiState> = combine(
        content.seriesById(route.playlistId, route.seriesId),
        content.episodes(route.playlistId, route.seriesId),
    ) { s, eps -> SeriesDetailsUiState(s, eps, loadingEpisodes = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SeriesDetailsUiState())

    init { viewModelScope.launch { runCatching { content.refreshSeriesDetails(route.playlistId, route.seriesId) } } }

    fun toggleFavorite() = viewModelScope.launch { content.toggleFavorite(route.playlistId, route.seriesId, ContentType.SERIES) }
}
