package com.maslarski.iptv.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.maslarski.iptv.R
import com.maslarski.iptv.data.local.entity.ReminderEntity
import com.maslarski.iptv.domain.model.Category
import com.maslarski.iptv.domain.model.Channel
import com.maslarski.iptv.domain.model.EpgProgram
import com.maslarski.iptv.ui.components.Pill
import com.maslarski.iptv.ui.components.focusGlow
import com.maslarski.iptv.ui.components.rememberFocusState
import com.maslarski.iptv.ui.components.rememberInteractionSource
import com.maslarski.iptv.ui.theme.Palette
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun reminderKey(epgChannelId: String, startMillis: Long) = "$epgChannelId@$startMillis"

private val RowShape = RoundedCornerShape(10.dp)
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private fun clock(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(timeFmt)

/**
 * Leanback three-column live TV browser: categories | compact channel list | EPG of the focused channel.
 * Used both as the Live TV screen and as the translucent zapping overlay inside the player.
 */
@Composable
fun LiveGuideColumns(
    categories: List<Category>,
    selectedCategoryId: String?,
    lockedCategoryIds: Set<String>,
    allLabel: String,
    onSelectCategory: (String?) -> Unit,
    channels: List<Channel>,
    nowPlaying: Map<String, EpgProgram>,
    currentChannelId: String?,
    onPlay: (Channel) -> Unit,
    programsFor: (String) -> Flow<List<EpgProgram>>,
    reminders: Map<String, ReminderEntity>,
    onProgramClick: (Channel, EpgProgram) -> Unit,
    modifier: Modifier = Modifier,
    translucent: Boolean = false,
    focusCurrentOnShow: Boolean = false,
    header: (@Composable () -> Unit)? = null,
) {
    var focusedChannel by remember { mutableStateOf<Channel?>(null) }
    val shown = focusedChannel?.takeIf { f -> channels.any { it.id == f.id } }
        ?: channels.firstOrNull { it.id == currentChannelId }
        ?: channels.firstOrNull()
    val panelBg = if (translucent) Color.White.copy(alpha = 0.06f) else Palette.Surface

    Row(modifier.fillMaxSize()) {
        Column(Modifier.width(280.dp).fillMaxHeight().padding(start = 32.dp, end = 12.dp, top = 24.dp)) {
            header?.invoke()
            Text(stringResource(R.string.categories_title), style = MaterialTheme.typography.labelMedium, color = Palette.Muted, modifier = Modifier.padding(bottom = 12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                item { Pill(allLabel, selectedCategoryId == null, Modifier.fillMaxWidth()) { onSelectCategory(null) } }
                items(categories, key = { it.id }) { c ->
                    Pill("${c.name}  ·  ${c.itemCount}", c.id == selectedCategoryId, Modifier.fillMaxWidth(), locked = c.id in lockedCategoryIds) { onSelectCategory(c.id) }
                }
            }
        }

        ChannelColumn(
            channels = channels,
            nowPlaying = nowPlaying,
            currentChannelId = currentChannelId,
            focusCurrentOnShow = focusCurrentOnShow,
            onFocus = { focusedChannel = it },
            onPlay = onPlay,
            modifier = Modifier.weight(1f).fillMaxHeight().padding(top = 24.dp, end = 12.dp),
        )

        Column(
            Modifier.width(400.dp).fillMaxHeight().padding(top = 24.dp, end = 32.dp, bottom = 24.dp)
                .clip(RoundedCornerShape(16.dp)).background(panelBg).padding(16.dp),
        ) {
            if (shown != null) {
                EpgColumn(shown, programsFor, reminders, onPlay, onProgramClick)
            }
        }
    }
}

/** Phone layout: just the compact channel list (categories are shown as pills above by the caller). */
@Composable
fun CompactChannelList(
    channels: List<Channel>,
    nowPlaying: Map<String, EpgProgram>,
    onPlay: (Channel) -> Unit,
    modifier: Modifier = Modifier,
) {
    ChannelColumn(channels, nowPlaying, currentChannelId = null, focusCurrentOnShow = false, onFocus = {}, onPlay = onPlay, modifier = modifier)
}

@Composable
private fun ChannelColumn(
    channels: List<Channel>,
    nowPlaying: Map<String, EpgProgram>,
    currentChannelId: String?,
    focusCurrentOnShow: Boolean,
    onFocus: (Channel) -> Unit,
    onPlay: (Channel) -> Unit,
    modifier: Modifier,
) {
    val listState: LazyListState = rememberLazyListState()
    val currentFocus = remember { FocusRequester() }
    val currentIndex = channels.indexOfFirst { it.id == currentChannelId }
    LaunchedEffect(channels.size, currentChannelId, focusCurrentOnShow) {
        if (focusCurrentOnShow && currentIndex >= 0) {
            listState.scrollToItem((currentIndex - 4).coerceAtLeast(0))
            runCatching { currentFocus.requestFocus() }
        }
    }
    val now = System.currentTimeMillis()
    LazyColumn(state = listState, modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
        itemsIndexed(channels, key = { _, c -> c.id }) { index, channel ->
            val program = channel.epgChannelId?.let { nowPlaying[it] }
            ChannelRow(
                channel = channel,
                program = program,
                progress = program?.progressAt(now),
                isCurrent = channel.id == currentChannelId,
                modifier = if (index == currentIndex) Modifier.focusRequester(currentFocus) else Modifier,
                onFocus = { onFocus(channel) },
                onClick = { onPlay(channel) },
            )
        }
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    program: EpgProgram?,
    progress: Float?,
    isCurrent: Boolean,
    modifier: Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    val interaction = rememberInteractionSource()
    val focused by rememberFocusState(interaction)
    Column(
        modifier.fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocus() }
            .focusGlow(interaction, RowShape, focusedScale = 1.01f, borderWidth = 2.dp, glowColor = Palette.ElectricBlue)
            .clip(RowShape)
            .background(
                when {
                    focused -> Palette.NeonPurple.copy(alpha = 0.9f)
                    isCurrent -> Color.White.copy(alpha = 0.14f)
                    else -> Color.White.copy(alpha = 0.05f)
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                channel.channelNumber?.toString() ?: "",
                style = MaterialTheme.typography.labelLarge,
                color = if (focused) Color.White else Palette.ElectricBlue,
                modifier = Modifier.width(44.dp),
            )
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                if (channel.logoUrl != null) AsyncImage(channel.logoUrl, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(3.dp))
                else Icon(Icons.Filled.Tv, null, tint = Palette.Muted, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White)
                if (program != null) {
                    Text(
                        program.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (focused) Color.White.copy(alpha = 0.85f) else Palette.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (channel.isFavorite) Icon(Icons.Filled.Favorite, null, tint = if (focused) Color.White else Palette.NeonPurple, modifier = Modifier.size(14.dp))
        }
        if (progress != null) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(start = 44.dp).height(2.dp).clip(CircleShape),
                color = if (focused) Color.White else Palette.ElectricBlue,
                trackColor = Color.White.copy(alpha = 0.15f),
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
private fun EpgColumn(
    channel: Channel,
    programsFor: (String) -> Flow<List<EpgProgram>>,
    reminders: Map<String, ReminderEntity>,
    onPlay: (Channel) -> Unit,
    onProgramClick: (Channel, EpgProgram) -> Unit,
) {
    val epgId = channel.epgChannelId
    val flow = remember(epgId) { epgId?.let(programsFor) ?: flowOf(emptyList()) }
    val programs by flow.collectAsState(emptyList())
    val now = System.currentTimeMillis()

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            if (channel.logoUrl != null) AsyncImage(channel.logoUrl, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
            else Icon(Icons.Filled.Tv, null, tint = Palette.Muted)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (channel.channelNumber != null) Text("${channel.channelNumber}", style = MaterialTheme.typography.labelMedium, color = Palette.ElectricBlue)
            Text(channel.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.guide_title), style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
    Spacer(Modifier.height(8.dp))
    if (programs.isEmpty()) {
        Text(stringResource(R.string.live_no_epg), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Pill(stringResource(R.string.action_watch_now), selected = false, modifier = Modifier.fillMaxWidth()) { onPlay(channel) }
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxHeight()) {
        items(programs, key = { it.id }) { p ->
            ProgramRow(
                program = p,
                live = p.isLiveAt(now),
                past = p.endMillis <= now,
                reminder = reminders[reminderKey(p.epgChannelId, p.startMillis)],
                onClick = { onProgramClick(channel, p) },
            )
        }
    }
}

@Composable
private fun ProgramRow(program: EpgProgram, live: Boolean, past: Boolean, reminder: ReminderEntity?, onClick: () -> Unit) {
    val interaction = rememberInteractionSource()
    val focused by rememberFocusState(interaction)
    val fg = when {
        focused -> Color.White
        past -> Palette.Muted.copy(alpha = 0.6f)
        else -> Palette.OnSurface
    }
    Column(
        Modifier.fillMaxWidth()
            .focusGlow(interaction, RowShape, focusedScale = 1.0f, borderWidth = 2.dp)
            .clip(RowShape)
            .background(when { focused -> Palette.NeonPurple; live -> Color.White.copy(alpha = 0.12f); else -> Color.Transparent })
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${clock(program.startMillis)} – ${clock(program.endMillis)}",
                style = MaterialTheme.typography.labelMedium,
                color = if (focused) Color.White else if (live) Palette.Live else Palette.Muted,
                modifier = Modifier.width(104.dp),
            )
            if (reminder != null) {
                Icon(if (reminder.autoSwitch) Icons.Filled.SwapHoriz else Icons.Filled.Alarm, null, tint = if (focused) Color.White else Palette.Gold, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(program.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = fg, modifier = Modifier.weight(1f))
        }
        if (program.description != null && (live || focused)) {
            Text(
                program.description,
                style = MaterialTheme.typography.bodySmall,
                color = if (focused) Color.White.copy(alpha = 0.85f) else Palette.Muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 104.dp, top = 2.dp),
            )
        }
        if (live) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { program.progressAt(System.currentTimeMillis()) },
                modifier = Modifier.fillMaxWidth().padding(start = 104.dp).height(3.dp).clip(CircleShape),
                color = if (focused) Color.White else Palette.Live,
                trackColor = Color.White.copy(alpha = 0.15f),
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
fun ReminderDialog(
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
                "${channel.name} · ${Instant.ofEpochMilli(program.startMillis).atZone(zone).format(fmt)} – ${clock(program.endMillis)}",
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
