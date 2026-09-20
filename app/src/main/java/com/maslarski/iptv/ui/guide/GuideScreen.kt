package com.maslarski.iptv.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.maslarski.iptv.R
import com.maslarski.iptv.data.local.entity.ReminderEntity
import com.maslarski.iptv.data.repository.ContentRepository
import com.maslarski.iptv.data.repository.PlaylistRepository
import com.maslarski.iptv.domain.model.Channel
import com.maslarski.iptv.domain.model.EpgProgram
import com.maslarski.iptv.domain.parental.ParentalGate
import com.maslarski.iptv.domain.reminder.ReminderManager
import com.maslarski.iptv.ui.components.EmptyState
import com.maslarski.iptv.ui.components.LoadingState
import com.maslarski.iptv.ui.components.Pill
import com.maslarski.iptv.ui.components.focusGlow
import com.maslarski.iptv.ui.components.rememberFocusState
import com.maslarski.iptv.ui.components.rememberInteractionSource
import com.maslarski.iptv.ui.theme.Palette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class GuideUiState(
    val loading: Boolean = true,
    val channels: List<Channel> = emptyList(),
    val programs: Map<String, List<EpgProgram>> = emptyMap(),
    val reminders: Map<String, ReminderEntity> = emptyMap(),
    val windowStart: Long = 0L,
    val windowEnd: Long = 0L,
)

fun reminderKey(epgChannelId: String, startMillis: Long) = "$epgChannelId@$startMillis"

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GuideViewModel @Inject constructor(
    playlists: PlaylistRepository,
    content: ContentRepository,
    gate: ParentalGate,
    private val reminders: ReminderManager,
) : ViewModel() {
    private val windowStart = (System.currentTimeMillis() / HALF_HOUR) * HALF_HOUR - HALF_HOUR
    private val windowEnd = windowStart + WINDOW_HOURS * 60 * 60 * 1000L

    val state: StateFlow<GuideUiState> = playlists.activePlaylist.flatMapLatest { p ->
        if (p == null) flowOf(GuideUiState(loading = false))
        else combine(content.channels(p.id, null), content.lockedCategoryIds(p.id), gate.enforcing) { channels, locked, enforce ->
            channels.filter { !enforce || it.categoryId !in locked }.take(MAX_CHANNELS)
        }.flatMapLatest { channels ->
            combine(
                flowOf(channels),
                content.programs(channels.mapNotNull { it.epgChannelId }, windowStart, windowEnd),
                reminders.upcoming,
            ) { ch, programs, upcoming ->
                GuideUiState(
                    loading = false, channels = ch, programs = programs,
                    reminders = upcoming.associateBy { reminderKey(it.epgChannelId, it.startMillis) },
                    windowStart = windowStart, windowEnd = windowEnd,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuideUiState())

    fun setReminder(channel: Channel, program: EpgProgram, autoSwitch: Boolean) {
        viewModelScope.launch { reminders.set(channel, program, autoSwitch) }
    }

    fun removeReminder(id: Long) {
        viewModelScope.launch { reminders.remove(id) }
    }

    companion object {
        const val HALF_HOUR = 30 * 60 * 1000L
        const val WINDOW_HOURS = 12
        const val MAX_CHANNELS = 400
    }
}

private val HourWidth = 360.dp
private val RowHeight = 72.dp
private val ChannelColumnWidth = 220.dp

@Composable
fun GuideScreen(onPlayChannel: (Channel) -> Unit, viewModel: GuideViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selection by remember { mutableStateOf<Pair<Channel, EpgProgram>?>(null) }
    when {
        state.loading -> LoadingState()
        state.channels.isEmpty() -> EmptyState(stringResource(R.string.guide_no_programs), icon = Icons.Filled.Tv)
        else -> GuideGrid(
            state = state,
            onPlayChannel = onPlayChannel,
            onProgramClick = { channel, program ->
                if (program.startMillis > System.currentTimeMillis()) selection = channel to program else onPlayChannel(channel)
            },
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
            onWatchNow = { onPlayChannel(channel); selection = null },
            onDismiss = { selection = null },
        )
    }
}

@Composable
private fun ReminderDialog(
    channel: Channel,
    program: EpgProgram,
    existing: ReminderEntity?,
    onRemind: () -> Unit,
    onAutoSwitch: () -> Unit,
    onRemove: (Long) -> Unit,
    onWatchNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val zone = ZoneId.systemDefault()
    val fmt = DateTimeFormatter.ofPattern("EEE HH:mm")
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(420.dp).clip(RoundedCornerShape(20.dp)).background(Palette.SurfaceElevated.copy(alpha = 0.96f)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(program.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${channel.name} · ${Instant.ofEpochMilli(program.startMillis).atZone(zone).format(fmt)} – ${Instant.ofEpochMilli(program.endMillis).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))}",
                style = MaterialTheme.typography.bodyMedium, color = Palette.Muted,
            )
            if (program.description != null) {
                Text(program.description, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            if (existing != null) {
                Text(
                    stringResource(if (existing.autoSwitch) R.string.reminder_auto_switch_set else R.string.reminder_set),
                    style = MaterialTheme.typography.labelLarge, color = Palette.Gold,
                )
            }
            Pill(stringResource(R.string.reminder_remind_me), selected = existing?.autoSwitch == false, modifier = Modifier.fillMaxWidth().focusRequester(first), onClick = onRemind)
            Pill(stringResource(R.string.reminder_auto_switch), selected = existing?.autoSwitch == true, modifier = Modifier.fillMaxWidth(), onClick = onAutoSwitch)
            if (existing != null) Pill(stringResource(R.string.reminder_remove), selected = false, modifier = Modifier.fillMaxWidth()) { onRemove(existing.id) }
            Pill(stringResource(R.string.action_watch_now), selected = false, modifier = Modifier.fillMaxWidth(), onClick = onWatchNow)
            Pill(stringResource(R.string.action_cancel), selected = false, modifier = Modifier.fillMaxWidth(), onClick = onDismiss)
        }
    }
}

@Composable
private fun GuideGrid(state: GuideUiState, onPlayChannel: (Channel) -> Unit, onProgramClick: (Channel, EpgProgram) -> Unit) {
    val scroll = rememberScrollState()
    val now = System.currentTimeMillis()
    val totalMillis = (state.windowEnd - state.windowStart).toFloat()
    val hours = (totalMillis / 3_600_000f)
    val timelineWidth = HourWidth * hours
    val zone = ZoneId.systemDefault()
    val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    fun xFor(t: Long) = timelineWidth * ((t - state.windowStart).toFloat() / totalMillis)

    Column(Modifier.fillMaxSize().padding(start = 32.dp, top = 24.dp)) {
        Text(stringResource(R.string.guide_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().height(36.dp)) {
            Box(Modifier.width(ChannelColumnWidth))
            Box(Modifier.horizontalScroll(scroll, enabled = false).width(timelineWidth)) {
                var t = state.windowStart
                while (t < state.windowEnd) {
                    Text(
                        Instant.ofEpochMilli(t).atZone(zone).format(timeFmt),
                        style = MaterialTheme.typography.labelLarge,
                        color = Palette.Muted,
                        modifier = Modifier.offset(x = xFor(t)),
                    )
                    t += GuideViewModel.HALF_HOUR
                }
                if (now in state.windowStart..state.windowEnd) {
                    Box(Modifier.offset(x = xFor(now)).width(2.dp).fillMaxHeight().background(Palette.Live))
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.channels, key = { it.id }) { channel ->
                Row(Modifier.fillMaxWidth().height(RowHeight).padding(vertical = 3.dp)) {
                    ChannelCell(channel) { onPlayChannel(channel) }
                    Box(Modifier.horizontalScroll(scroll).width(timelineWidth).fillMaxHeight()) {
                        val programs = channel.epgChannelId?.let { state.programs[it] }.orEmpty()
                        if (programs.isEmpty()) {
                            Box(
                                Modifier.fillMaxSize().padding(horizontal = 2.dp).clip(RoundedCornerShape(8.dp)).background(Palette.Surface),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(stringResource(R.string.live_no_epg), color = Palette.Muted, modifier = Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        programs.forEach { p ->
                            val start = p.startMillis.coerceAtLeast(state.windowStart)
                            val end = p.endMillis.coerceAtMost(state.windowEnd)
                            if (end <= start) return@forEach
                            val left = xFor(start)
                            val width = xFor(end) - left
                            val reminder = state.reminders[reminderKey(p.epgChannelId, p.startMillis)]
                            ProgramCell(p, p.isLiveAt(now), reminder, Modifier.offset(x = left).width(width).fillMaxHeight()) { onProgramClick(channel, p) }
                        }
                        if (now in state.windowStart..state.windowEnd) {
                            Box(Modifier.offset(x = xFor(now)).width(2.dp).fillMaxHeight().background(Palette.Live.copy(alpha = 0.6f)))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelCell(channel: Channel, onClick: () -> Unit) {
    val interaction = rememberInteractionSource()
    Row(
        Modifier.width(ChannelColumnWidth).fillMaxHeight().padding(end = 8.dp)
            .focusGlow(interaction, RoundedCornerShape(10.dp), focusedScale = 1.0f, borderWidth = 2.dp, glowColor = Palette.ElectricBlue)
            .clip(RoundedCornerShape(10.dp)).background(Palette.SurfaceElevated)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Palette.SurfaceHighest), contentAlignment = Alignment.Center) {
            if (channel.logoUrl != null) AsyncImage(channel.logoUrl, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(3.dp))
            else Icon(Icons.Filled.Tv, null, tint = Palette.Muted, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            if (channel.channelNumber != null) Text("${channel.channelNumber}", style = MaterialTheme.typography.labelSmall, color = Palette.ElectricBlue)
            Text(channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ProgramCell(program: EpgProgram, live: Boolean, reminder: ReminderEntity?, modifier: Modifier, onClick: () -> Unit) {
    val interaction = rememberInteractionSource()
    val focused by rememberFocusState(interaction)
    val bg = when {
        focused -> Palette.NeonPurple
        live -> Palette.SurfaceHighest
        else -> Palette.Surface
    }
    Column(
        modifier.padding(horizontal = 2.dp)
            .focusGlow(interaction, RoundedCornerShape(8.dp), focusedScale = 1.0f, borderWidth = 2.dp)
            .clip(RoundedCornerShape(8.dp)).background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (reminder != null) {
                Icon(
                    if (reminder.autoSwitch) Icons.Filled.SwapHoriz else Icons.Filled.Alarm,
                    null,
                    tint = if (focused) Color.White else Palette.Gold,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(program.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (focused) Color.White else Palette.OnSurface)
        }
        if (program.description != null) {
            Text(program.description, style = MaterialTheme.typography.bodySmall, color = if (focused) Color.White.copy(alpha = 0.85f) else Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
