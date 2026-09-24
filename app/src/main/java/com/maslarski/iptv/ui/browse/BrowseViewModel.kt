package com.maslarski.iptv.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maslarski.iptv.data.mapper.toMediaItem
import com.maslarski.iptv.data.repository.ContentRepository
import com.maslarski.iptv.data.repository.PlaylistRepository
import com.maslarski.iptv.domain.model.Category
import com.maslarski.iptv.domain.model.ContentType
import com.maslarski.iptv.domain.model.EpgProgram
import com.maslarski.iptv.domain.model.MediaItem
import com.maslarski.iptv.domain.parental.ParentalGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BrowseUiState(
    val playlistId: Long? = null,
    val categories: List<Category> = emptyList(),
    val selectedCategoryId: String? = null,
    val items: List<MediaItem> = emptyList(),
    val nowPlaying: Map<String, EpgProgram> = emptyMap(),
    val lockedCategoryIds: Set<String> = emptySet(),
    val pendingUnlockCategoryId: String? = null,
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
abstract class BrowseViewModel(
    protected val type: ContentType,
    private val playlists: PlaylistRepository,
    protected val content: ContentRepository,
    private val gate: ParentalGate,
) : ViewModel() {

    private val selected = MutableStateFlow<String?>(null)
    private val pendingUnlock = MutableStateFlow<String?>(null)
    private val active = playlists.activePlaylist

    private val locked: Flow<Set<String>> = active.flatMapLatest { p ->
        if (p == null) flowOf(emptySet())
        else combine(content.lockedCategoryIds(p.id), gate.enforcing) { ids, enforce -> if (enforce) ids else emptySet() }
    }

    private val categories: Flow<List<Category>> = active.flatMapLatest { p ->
        if (p == null) flowOf(emptyList()) else content.categories(p.id, type)
    }

    protected abstract fun items(playlistId: Long, categoryId: String?): Flow<List<MediaItem>>

    protected open fun nowPlaying(items: List<MediaItem>): Flow<Map<String, EpgProgram>> = flowOf(emptyMap())

    private val itemsFlow: Flow<List<MediaItem>> = combine(active, selected, locked) { p, sel, lockedIds -> Triple(p, sel, lockedIds) }
        .flatMapLatest { (p, sel, lockedIds) ->
            if (p == null || (sel != null && sel in lockedIds)) flowOf(emptyList())
            else items(p.id, sel).map { list -> if (sel == null) list.filter { it.categoryId !in lockedIds } else list }
        }

    private val epgFlow = itemsFlow.flatMapLatest { nowPlaying(it) }

    val state: StateFlow<BrowseUiState> = combine(active, categories, selected, itemsFlow, locked) { p, cats, sel, items, lockedIds ->
        BrowseUiState(
            playlistId = p?.id,
            categories = cats,
            selectedCategoryId = sel,
            items = items,
            lockedCategoryIds = lockedIds,
            loading = p == null,
        )
    }.let { base ->
        combine(base, epgFlow, pendingUnlock) { s, epg, pending -> s.copy(nowPlaying = epg, pendingUnlockCategoryId = pending) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseUiState())

    fun selectCategory(id: String?) {
        val lockedIds = state.value.lockedCategoryIds
        if (id != null && id in lockedIds) {
            pendingUnlock.value = id
        } else {
            selected.value = id
        }
    }

    fun submitPin(pin: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = gate.tryUnlock(pin)
            if (ok) {
                selected.value = pendingUnlock.value
                pendingUnlock.value = null
            }
            onResult(ok)
        }
    }

    fun dismissPin() { pendingUnlock.value = null }

    fun toggleFavorite(item: MediaItem) {
        viewModelScope.launch { content.toggleFavorite(item.playlistId, item.id, item.type) }
    }
}

@HiltViewModel
class MoviesViewModel @Inject constructor(
    playlists: PlaylistRepository, content: ContentRepository, gate: ParentalGate,
) : BrowseViewModel(ContentType.MOVIE, playlists, content, gate) {
    override fun items(playlistId: Long, categoryId: String?): Flow<List<MediaItem>> =
        content.movies(playlistId, categoryId).map { list ->
            list.map { m -> m.toMediaItem().copy(categoryId = m.categoryId, rating = m.rating, isFavorite = m.isFavorite) }
        }
}

@HiltViewModel
class SeriesViewModel @Inject constructor(
    playlists: PlaylistRepository, content: ContentRepository, gate: ParentalGate,
) : BrowseViewModel(ContentType.SERIES, playlists, content, gate) {
    override fun items(playlistId: Long, categoryId: String?): Flow<List<MediaItem>> =
        content.series(playlistId, categoryId).map { list ->
            list.map { s -> s.toMediaItem().copy(categoryId = s.categoryId, rating = s.rating, isFavorite = s.isFavorite) }
        }
}
