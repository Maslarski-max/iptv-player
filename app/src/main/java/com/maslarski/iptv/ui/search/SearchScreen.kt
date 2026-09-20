package com.maslarski.iptv.ui.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.maslarski.iptv.R
import com.maslarski.iptv.data.mapper.toMediaItem
import com.maslarski.iptv.data.repository.ContentRepository
import com.maslarski.iptv.data.repository.PlaylistRepository
import com.maslarski.iptv.domain.model.MediaItem
import com.maslarski.iptv.domain.parental.ParentalGate
import com.maslarski.iptv.ui.components.EmptyState
import com.maslarski.iptv.ui.components.dpadTextField
import com.maslarski.iptv.ui.components.LandscapeAspect
import com.maslarski.iptv.ui.components.MediaRow
import com.maslarski.iptv.ui.theme.Palette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val searching: Boolean = false,
    val channels: List<MediaItem> = emptyList(),
    val movies: List<MediaItem> = emptyList(),
    val series: List<MediaItem> = emptyList(),
) {
    val isEmpty get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()
}

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val playlists: PlaylistRepository,
    private val content: ContentRepository,
    private val gate: ParentalGate,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val results = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = combine(query, results) { q, r -> r.copy(query = q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    init {
        viewModelScope.launch {
            query.debounce(300).map { it.trim() }.distinctUntilChanged().collect { q ->
                if (q.length < 2) { results.value = SearchUiState(); return@collect }
                val p = playlists.getActive() ?: return@collect
                results.value = results.value.copy(searching = true)
                val locked = if (gate.enforcing.first()) content.lockedCategoryIds(p.id).first() else emptySet()
                val r = content.search(p.id, q)
                results.value = SearchUiState(
                    searching = false,
                    channels = r.channels.filter { it.categoryId !in locked }.map { it.toMediaItem() },
                    movies = r.movies.filter { it.categoryId !in locked }.map { it.toMediaItem() },
                    series = r.series.filter { it.categoryId !in locked }.map { it.toMediaItem() },
                )
            }
        }
    }

    fun onQueryChange(q: String) { query.value = q }
    val queryFlow = query.asStateFlow()
}

@Composable
fun SearchScreen(
    onPlay: (MediaItem) -> Unit,
    onOpenDetails: (MediaItem) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.queryFlow.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp).focusRequester(focus).dpadTextField(),
            placeholder = { Text(stringResource(R.string.search_hint), color = Palette.Muted) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = Palette.NeonPurple) },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Palette.NeonPurple,
                unfocusedBorderColor = Palette.Slate,
                focusedContainerColor = Palette.SurfaceElevated,
                unfocusedContainerColor = Palette.Surface,
            ),
        )
        when {
            query.trim().length < 2 -> EmptyState(stringResource(R.string.search_title), body = stringResource(R.string.search_hint), icon = Icons.Filled.Search)
            state.isEmpty && !state.searching -> EmptyState(stringResource(R.string.search_no_results, state.query))
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 48.dp)) {
                item { MediaRow(stringResource(R.string.nav_live), state.channels, aspect = LandscapeAspect, cardWidth = 200.dp, onClick = onPlay) }
                item { MediaRow(stringResource(R.string.nav_movies), state.movies, onClick = onOpenDetails) }
                item { MediaRow(stringResource(R.string.nav_series), state.series, onClick = onOpenDetails) }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
