package com.maslarski.iptv.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.maslarski.iptv.R
import com.maslarski.iptv.data.local.entity.ReminderEntity
import com.maslarski.iptv.data.repository.ContentRepository
import com.maslarski.iptv.data.repository.PlaylistRepository
import com.maslarski.iptv.domain.model.Category
import com.maslarski.iptv.domain.model.Channel
import com.maslarski.iptv.domain.model.ContentType
import com.maslarski.iptv.domain.model.EpgProgram
import com.maslarski.iptv.domain.parental.ParentalGate
import com.maslarski.iptv.domain.reminder.ReminderManager
import com.maslarski.iptv.ui.components.EmptyState
import com.maslarski.iptv.ui.components.LoadingState
import com.maslarski.iptv.ui.components.Pill
import com.maslarski.iptv.ui.components.PinDialog
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

data class LiveUiState(
    val playlistId: Long? = null,
    val categories: List<Category> = emptyList(),
    val selectedCategoryId: String? = null,
    val channels: List<Channel> = emptyList(),
    val nowPlaying: Map<String, EpgProgram> = emptyMap(),
    val lockedCategoryIds: Set<String> = emptySet(),
    val pendingUnlockCategoryId: String? = null,
    val reminders: Map<String, ReminderEntity> = emptyMap(),
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LiveTvViewModel @Inject constructor(
    playlists: PlaylistRepository,
    private val content: ContentRepository,
    private val gate: ParentalGate,
    private val reminders: ReminderManager,
) : ViewModel() {

    private val selected = MutableStateFlow<String?>(null)
    private val pendingUnlock = MutableStateFlow<String?>(null)
    private val active = playlists.activePlaylist

    private val locked: Flow<Set<String>> = active.flatMapLatest { p ->
        if (p == null) flowOf(emptySet())
        else combine(content.lockedCategoryIds(p.id), gate.enforcing) { ids, enforce -> if (enforce) ids else emptySet() }
    }

    private val categories: Flow<List<Category>> = active.flatMapLatest { p ->
        if (p == null) flowOf(emptyList()) else content.categories(p.id, ContentType.LIVE)
    }

    private val channels: Flow<List<Channel>> = combine(active, selected, locked) { p, sel, lockedIds -> Triple(p, sel, lockedIds) }
        .flatMapLatest { (p, sel, lockedIds) ->
            if (p == null || (sel != null && sel in lockedIds)) flowOf(emptyList())
            else content.channels(p.id, sel).map { list -> if (sel == null) list.filter { it.categoryId !in lockedIds } else list }
        }

    private val nowPlaying: Flow<Map<String, EpgProgram>> = channels.flatMapLatest { list ->
        content.nowPlaying(list.mapNotNull { it.epgChannelId }.take(400))
    }

    val state: StateFlow<LiveUiState> = combine(active, categories, selected, channels, locked) { p, cats, sel, list, lockedIds ->
        LiveUiState(playlistId = p?.id, categories = cats, selectedCategoryId = sel, channels = list, lockedCategoryIds = lockedIds, loading = p == null)
    }.let { base ->
        combine(base, nowPlaying, pendingUnlock, reminders.upcoming) { s, epg, pending, upcoming ->
            s.copy(nowPlaying = epg, pendingUnlockCategoryId = pending, reminders = upcoming.associateBy { reminderKey(it.epgChannelId, it.startMillis) })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveUiState())

    fun programsFor(epgChannelId: String): Flow<List<EpgProgram>> {
        val now = System.currentTimeMillis()
        return content.programsForChannel(epgChannelId, now - EPG_PAST_MS, now + EPG_FUTURE_MS)
    }

    fun selectCategory(id: String?) {
        if (id != null && id in state.value.lockedCategoryIds) pendingUnlock.value = id else selected.value = id
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

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch { content.toggleFavorite(channel.playlistId, channel.id, ContentType.LIVE) }
    }

    fun setReminder(channel: Channel, program: EpgProgram, autoSwitch: Boolean) {
        viewModelScope.launch { reminders.set(channel, program, autoSwitch) }
    }

    fun removeReminder(id: Long) {
        viewModelScope.launch { reminders.remove(id) }
    }

    companion object {
        const val EPG_PAST_MS = 60 * 60 * 1000L
        const val EPG_FUTURE_MS = 12 * 60 * 60 * 1000L
    }
}

@Composable
fun LiveScreen(isCompact: Boolean, onPlay: (Channel) -> Unit, viewModel: LiveTvViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pinError by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf<Pair<Channel, EpgProgram>?>(null) }
    val wrongPin = stringResource(R.string.pin_wrong)

    if (state.pendingUnlockCategoryId != null) {
        PinDialog(
            title = stringResource(R.string.pin_enter),
            subtitle = stringResource(R.string.pin_locked_content),
            error = pinError,
            onSubmit = { pin -> viewModel.submitPin(pin) { ok -> pinError = if (ok) null else wrongPin } },
            onDismiss = { pinError = null; viewModel.dismissPin() },
        )
    }
    val sel = selection
    if (sel != null) {
        val (channel, program) = sel
        ReminderDialog(
            channel = channel,
            program = program,
            existing = state.reminders[reminderKey(program.epgChannelId, program.startMillis)],
            onRemind = { viewModel.setReminder(channel, program, autoSwitch = false); selection = null },
            onAutoSwitch = { viewModel.setReminder(channel, program, autoSwitch = true); selection = null },
            onRemove = { viewModel.removeReminder(it); selection = null },
            onWatchNow = { onPlay(channel); selection = null },
            onDismiss = { selection = null },
        )
    }
    val onProgramClick: (Channel, EpgProgram) -> Unit = { channel, program ->
        if (program.startMillis > System.currentTimeMillis()) selection = channel to program else onPlay(channel)
    }

    when {
        state.loading -> LoadingState()
        state.categories.isEmpty() && state.channels.isEmpty() -> EmptyState(stringResource(R.string.empty_section))
        isCompact -> Column(Modifier.fillMaxSize()) {
            Text(stringResource(R.string.nav_live), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Pill(stringResource(R.string.category_all), state.selectedCategoryId == null) { viewModel.selectCategory(null) } }
                items(state.categories, key = { it.id }) { c ->
                    Pill(c.name, c.id == state.selectedCategoryId, locked = c.id in state.lockedCategoryIds) { viewModel.selectCategory(c.id) }
                }
            }
            CompactChannelList(state.channels, state.nowPlaying, onPlay, Modifier.fillMaxWidth().padding(horizontal = 20.dp), onToggleFavorite = viewModel::toggleFavorite)
        }
        else -> LiveGuideColumns(
            categories = state.categories,
            selectedCategoryId = state.selectedCategoryId,
            lockedCategoryIds = state.lockedCategoryIds,
            allLabel = stringResource(R.string.category_all),
            onSelectCategory = viewModel::selectCategory,
            channels = state.channels,
            nowPlaying = state.nowPlaying,
            currentChannelId = null,
            onPlay = onPlay,
            programsFor = viewModel::programsFor,
            reminders = state.reminders,
            onProgramClick = onProgramClick,
            onToggleFavorite = viewModel::toggleFavorite,
            header = { Text(stringResource(R.string.nav_live), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 4.dp)) },
        )
    }
}
