package com.maslarski.iptv.ui.favorites

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.maslarski.iptv.R
import com.maslarski.iptv.data.repository.ContentRepository
import com.maslarski.iptv.data.repository.PlaylistRepository
import com.maslarski.iptv.domain.model.ContentType
import com.maslarski.iptv.domain.model.MediaItem
import com.maslarski.iptv.ui.components.EmptyState
import com.maslarski.iptv.ui.components.LandscapeAspect
import com.maslarski.iptv.ui.components.MediaRow
import com.maslarski.iptv.ui.theme.Palette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FavoritesViewModel @Inject constructor(
    playlists: PlaylistRepository,
    private val content: ContentRepository,
) : ViewModel() {
    /**
     * Items keep their slot while the screen is open: un-favoriting dims the card instead of removing
     * it, so D-pad focus and scroll position survive and the action can be undone with another long-press.
     */
    val favorites: StateFlow<List<MediaItem>?> = playlists.activePlaylist.flatMapLatest { p ->
        if (p == null) flowOf(emptyList()) else content.favorites(p.id)
    }.scan<List<MediaItem>, List<MediaItem>?>(null) { shown, current ->
        val currentByKey = current.associateBy { "${it.type}:${it.id}" }
        if (shown == null) return@scan current.map { it.copy(isFavorite = true) }
        val kept = shown.map { old -> old.copy(isFavorite = "${old.type}:${old.id}" in currentByKey) }
        val known = shown.map { "${it.type}:${it.id}" }.toSet()
        kept + current.filter { "${it.type}:${it.id}" !in known }.map { it.copy(isFavorite = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggle(item: MediaItem) {
        viewModelScope.launch { content.toggleFavorite(item.playlistId, item.id, item.type) }
    }
}

@Composable
fun FavoritesScreen(
    onPlay: (MediaItem) -> Unit,
    onOpenDetails: (MediaItem) -> Unit,
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val list = favorites ?: return
    val playFavorite: (MediaItem) -> Unit = { item -> if (item.isFavorite) onPlay(item) else viewModel.toggle(item) }
    val openFavorite: (MediaItem) -> Unit = { item -> if (item.isFavorite) onOpenDetails(item) else viewModel.toggle(item) }
    if (list.isEmpty()) {
        EmptyState(stringResource(R.string.favorites_empty_title), body = stringResource(R.string.favorites_empty_body), icon = Icons.Filled.Favorite)
        return
    }
    Column(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.favorites_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 48.dp, vertical = 24.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 48.dp)) {
            item { MediaRow(stringResource(R.string.nav_live), list.filter { it.type == ContentType.LIVE }, aspect = LandscapeAspect, cardWidth = 200.dp, showFavoriteState = true, onClick = playFavorite, onLongClick = viewModel::toggle) }
            item { MediaRow(stringResource(R.string.nav_movies), list.filter { it.type == ContentType.MOVIE }, showFavoriteState = true, onClick = openFavorite, onLongClick = viewModel::toggle) }
            item { MediaRow(stringResource(R.string.nav_series), list.filter { it.type == ContentType.SERIES }, showFavoriteState = true, onClick = openFavorite, onLongClick = viewModel::toggle) }
            item { Text(stringResource(R.string.favorites_hint), style = MaterialTheme.typography.bodySmall, color = Palette.Muted, modifier = Modifier.padding(horizontal = 48.dp, vertical = 16.dp)) }
        }
    }
}
