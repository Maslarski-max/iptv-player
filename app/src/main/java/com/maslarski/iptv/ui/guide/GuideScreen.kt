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
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.maslarski.iptv.R
import com.maslarski.iptv.data.repository.ContentRepository
import com.maslarski.iptv.data.repository.PlaylistRepository
import com.maslarski.iptv.domain.model.Channel
import com.maslarski.iptv.domain.model.EpgProgram
import com.maslarski.iptv.domain.parental.ParentalGate
import com.maslarski.iptv.ui.components.EmptyState
import com.maslarski.iptv.ui.components.LoadingState
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class GuideUiState(
    val loading: Boolean = true,
    val channels: List<Channel> = emptyList(),
    val programs: Map<String, List<EpgProgram>> = emptyMap(),
    val windowStart: Long = 0L,
    val windowEnd: Long = 0L,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GuideViewModel @Inject constructor(
    playlists: PlaylistRepository,
    content: ContentRepository,
    gate: ParentalGate,
) : ViewModel() {
    private val windowStart = (System.currentTimeMillis() / HALF_HOUR) * HALF_HOUR - HALF_HOUR
    private val windowEnd = windowStart + 6 * 60 * 60 * 1000L

    val state: StateFlow<GuideUiState> = playlists.activePlaylist.flatMapLatest { p ->
        if (p == null) flowOf(GuideUiState(loading = false))
        else combine(content.channels(p.id, null), content.lockedCategoryIds(p.id), gate.enforcing) { channels, locked, enforce ->
            channels.filter { !enforce || it.categoryId !in locked }.take(MAX_CHANNELS)
        }.flatMapLatest { channels ->
            content.programs(channels.mapNotNull { it.epgChannelId }, windowStart, windowEnd).let { flow ->
                combine(flowOf(channels), flow) { ch, programs ->
                    GuideUiState(loading = false, channels = ch, programs = programs, windowStart = windowStart, windowEnd = windowEnd)
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuideUiState())

    companion object {
        const val HALF_HOUR = 30 * 60 * 1000L
        const val MAX_CHANNELS = 400
    }
}

private val HourWidth = 360.dp
private val RowHeight = 72.dp
private val ChannelColumnWidth = 220.dp

@Composable
fun GuideScreen(onPlayChannel: (Channel) -> Unit, viewModel: GuideViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when {
        state.loading -> LoadingState()
        state.channels.isEmpty() -> EmptyState(stringResource(R.string.guide_no_programs), icon = Icons.Filled.Tv)
        else -> GuideGrid(state, onPlayChannel)
    }
}

@Composable
private fun GuideGrid(state: GuideUiState, onPlayChannel: (Channel) -> Unit) {
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
                            ProgramCell(p, p.isLiveAt(now), Modifier.offset(x = left).width(width).fillMaxHeight()) { onPlayChannel(channel) }
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
private fun ProgramCell(program: EpgProgram, live: Boolean, modifier: Modifier, onClick: () -> Unit) {
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
        Text(program.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (focused) Color.White else Palette.OnSurface)
        if (program.description != null) {
            Text(program.description, style = MaterialTheme.typography.bodySmall, color = if (focused) Color.White.copy(alpha = 0.85f) else Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
