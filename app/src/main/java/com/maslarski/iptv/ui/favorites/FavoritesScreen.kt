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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FavoritesViewModel @Inject constructor(
    playlists: PlaylistRepository,
    private val content: ContentRepository,
) : ViewModel() {
    val favorites: StateFlow<List<MediaItem>?> = playlists.activePlaylist.flatMapLatest { p ->
        if (p == null) flowOf(emptyList()) else content.favorites(p.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun remove(item: MediaItem) {
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
    if (list.isEmpty()) {
        EmptyState(stringResource(R.string.favorites_empty_title), body = stringResource(R.string.favorites_empty_body), icon = Icons.Filled.Favorite)
        return
    }
    Column(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.favorites_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 48.dp, vertical = 24.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 48.dp)) {
            item { MediaRow(stringResource(R.string.nav_live), list.filter { it.type == ContentType.LIVE }, aspect = LandscapeAspect, cardWidth = 200.dp, onClick = onPlay, onLongClick = viewModel::remove) }
            item { MediaRow(stringResource(R.string.nav_movies), list.filter { it.type == ContentType.MOVIE }, onClick = onOpenDetails, onLongClick = viewModel::remove) }
            item { MediaRow(stringResource(R.string.nav_series), list.filter { it.type == ContentType.SERIES }, onClick = onOpenDetails, onLongClick = viewModel::remove) }
        }
    }
}
