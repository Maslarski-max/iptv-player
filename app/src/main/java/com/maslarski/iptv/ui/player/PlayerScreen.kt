package com.maslarski.iptv.ui.player

import android.net.Uri
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.maslarski.iptv.R
import com.maslarski.iptv.data.settings.AspectRatioMode
import com.maslarski.iptv.domain.model.Channel
import com.maslarski.iptv.ui.components.Pill
import com.maslarski.iptv.ui.components.focusGlow
import com.maslarski.iptv.ui.components.rememberFocusState
import com.maslarski.iptv.ui.components.rememberInteractionSource
import com.maslarski.iptv.ui.settings.label
import com.maslarski.iptv.ui.theme.Palette
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class Panel { NONE, AUDIO, SUBTITLES, CHANNELS, SETTINGS }

@UnstableApi
@Composable
fun PlayerScreen(onBack: () -> Unit, viewModel: PlayerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var controlsVisible by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    val playFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }

    fun poke() { lastInteraction = System.currentTimeMillis(); controlsVisible = true }

    LaunchedEffect(lastInteraction, state.isPlaying, panel) {
        if (panel != Panel.NONE) return@LaunchedEffect
        delay(OSD_TIMEOUT_MS)
        if (state.isPlaying) controlsVisible = false
    }
    LaunchedEffect(controlsVisible) { if (controlsVisible) playFocus.requestFocus() else rootFocus.requestFocus() }
    DisposableEffect(Unit) { onDispose { viewModel.onLeave() } }

    BackHandler {
        when {
            panel != Panel.NONE -> panel = Panel.NONE
            controlsVisible && state.isPlaying -> controlsVisible = false
            else -> onBack()
        }
    }

    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.addExternalSubtitle(uri)
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val handled = when (event.key.nativeKeyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { viewModel.togglePlayPause(); true }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> { viewModel.play(); true }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> { viewModel.pause(); true }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { viewModel.seekForward(); true }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> { viewModel.seekBack(); true }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> { if (state.isLive) viewModel.channelUp() else viewModel.playNextEpisode(); true }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (state.isLive) viewModel.channelDown(); true }
                    KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { viewModel.channelUp(); true }
                    KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { viewModel.channelDown(); true }
                    KeyEvent.KEYCODE_CAPTIONS -> { panel = Panel.SUBTITLES; true }
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { panel = Panel.SETTINGS; true }
                    KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TV -> { if (state.isLive) panel = Panel.CHANNELS; state.isLive }
                    else -> when (event.key) {
                        Key.DirectionUp -> if (!controlsVisible && panel == Panel.NONE && state.isLive) { viewModel.channelUp(); true } else false
                        Key.DirectionDown -> if (!controlsVisible && panel == Panel.NONE && state.isLive) { viewModel.channelDown(); true } else false
                        Key.DirectionLeft -> when {
                            controlsVisible || panel != Panel.NONE -> false
                            state.isLive -> { panel = Panel.CHANNELS; true }
                            else -> { viewModel.seekBack(); true }
                        }
                        Key.DirectionRight -> when {
                            controlsVisible || panel != Panel.NONE -> false
                            state.isLive -> { panel = Panel.SETTINGS; true }
                            else -> { viewModel.seekForward(); true }
                        }
                        Key.DirectionCenter, Key.Enter -> if (!controlsVisible && panel == Panel.NONE) { poke(); true } else false
                        else -> false
                    }
                }
                // Any remote interaction (including D-pad moves between OSD buttons) restarts the auto-hide countdown.
                if (panel == Panel.NONE && (handled || controlsVisible)) poke()
                handled
            }
            .clickable(interactionSource = null, indication = null) { if (controlsVisible && state.isPlaying) controlsVisible = false else poke() },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    keepScreenOn = true
                    setKeepContentOnPlayerReset(true)
                }
            },
            update = { view ->
                view.player = viewModel.player
                view.resizeMode = when (state.aspect) {
                    AspectRatioMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    AspectRatioMode.RATIO_16_9, AspectRatioMode.RATIO_4_3 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    AspectRatioMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    AspectRatioMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                }
                view.subtitleView?.setApplyEmbeddedStyles(true)
            },
            modifier = when (state.aspect) {
                AspectRatioMode.RATIO_16_9 -> Modifier.fillMaxHeight().aspectRatio(16f / 9f, matchHeightConstraintsFirst = true).align(Alignment.Center)
                AspectRatioMode.RATIO_4_3 -> Modifier.fillMaxHeight().aspectRatio(4f / 3f, matchHeightConstraintsFirst = true).align(Alignment.Center)
                else -> Modifier.fillMaxSize()
            },
        )

        if (state.isBuffering && state.error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = Palette.NeonPurple, strokeWidth = 3.dp)
        }
        if (state.error != null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(40.dp), color = Palette.Live, strokeWidth = 3.dp)
                Spacer(Modifier.height(12.dp))
                Text(
                    if (state.reconnectAttempt > 0) stringResource(R.string.player_reconnecting, state.reconnectAttempt) else stringResource(R.string.player_error),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(state.error.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Controls(
                state = state,
                playFocus = playFocus,
                onTogglePlay = { viewModel.togglePlayPause(); poke() },
                onSeekBack = { viewModel.seekBack(); poke() },
                onSeekForward = { viewModel.seekForward(); poke() },
                onSeek = { viewModel.seekTo(it); poke() },
                onAspect = { viewModel.cycleAspect(); poke() },
                onAudio = { panel = Panel.AUDIO; poke() },
                onSubtitles = { panel = Panel.SUBTITLES; poke() },
                onNext = { if (state.isLive) viewModel.channelUp() else viewModel.playNextEpisode(); poke() },
                onChannels = { panel = Panel.CHANNELS; poke() },
                onSettings = { panel = Panel.SETTINGS; poke() },
            )
        }

        AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart)) {
            Column(Modifier.fillMaxWidth().background(Palette.HeroTopScrim).padding(32.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.channelNumber != null) {
                        Text("${state.channelNumber}", style = MaterialTheme.typography.headlineMedium, color = Palette.ElectricBlue)
                        Spacer(Modifier.width(16.dp))
                    }
                    Text(state.title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (state.isLive) {
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.live_badge), color = Color.White, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.background(Palette.Live, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
                val sub = state.nowPlaying?.title ?: state.subtitle
                if (sub != null) Text(sub, style = MaterialTheme.typography.bodyLarge, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val next = state.nextProgram
                if (next != null) {
                    Text(stringResource(R.string.player_next, next.title), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                }
            }
        }

        if (panel == Panel.CHANNELS) {
            ChannelPanel(
                channels = state.channels,
                currentId = state.currentChannelId,
                favoritesOnly = state.favoritesOnly,
                onSelect = { viewModel.playChannelById(it.id); panel = Panel.NONE; poke() },
                onDismiss = { panel = Panel.NONE; poke() },
            )
        }
        if (panel == Panel.SETTINGS) {
            SettingsPanel(
                state = state,
                onAudio = { panel = Panel.AUDIO },
                onSubtitles = { panel = Panel.SUBTITLES },
                onAspect = { viewModel.setAspect(it) },
                onDismiss = { panel = Panel.NONE; poke() },
            )
        }
        if (panel == Panel.AUDIO || panel == Panel.SUBTITLES) {
            TrackPanel(
                title = stringResource(if (panel == Panel.AUDIO) R.string.player_audio else R.string.player_subtitles),
                options = if (panel == Panel.AUDIO) state.audioTracks else state.subtitleTracks,
                showOff = panel == Panel.SUBTITLES,
                offSelected = panel == Panel.SUBTITLES && !state.subtitlesEnabled,
                onSelect = { viewModel.selectTrack(it); panel = Panel.NONE },
                onOff = { viewModel.disableSubtitles(); panel = Panel.NONE },
                onLoadExternal = if (panel == Panel.SUBTITLES && !state.isLive) ({ subtitlePicker.launch(arrayOf("*/*")) }) else null,
                onDismiss = { panel = Panel.NONE },
            )
        }
    }
}

@Composable
private fun Controls(
    state: PlayerUiState,
    playFocus: FocusRequester,
    onTogglePlay: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSeek: (Float) -> Unit,
    onAspect: () -> Unit,
    onAudio: () -> Unit,
    onSubtitles: () -> Unit,
    onNext: () -> Unit,
    onChannels: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Palette.HeroScrim).padding(horizontal = 32.dp, vertical = 24.dp)) {
        if (!state.isLive && state.durationMillis > 0) {
            val fraction = (state.positionMillis.toFloat() / state.durationMillis).coerceIn(0f, 1f)
            val sliderInteraction = rememberInteractionSource()
            val sliderFocused by rememberFocusState(sliderInteraction)
            val accent by animateColorAsState(if (sliderFocused) Palette.Gold else Palette.NeonPurple, label = "timelineAccent")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(state.positionMillis), style = MaterialTheme.typography.labelMedium, color = if (sliderFocused) Palette.Gold else Color.Unspecified)
                Slider(
                    value = fraction,
                    onValueChange = onSeek,
                    interactionSource = sliderInteraction,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                        .focusGlow(sliderInteraction, RoundedCornerShape(50), focusedScale = 1f, borderWidth = 2.dp, glowColor = Palette.Gold, animateScale = false),
                    colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Palette.SurfaceHighest),
                )
                Text(formatTime(state.durationMillis), style = MaterialTheme.typography.labelMedium, color = if (sliderFocused) Palette.Gold else Color.Unspecified)
            }
        } else if (state.isLive && state.nowPlaying != null) {
            val p = state.nowPlaying
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatClock(p.startMillis), style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(
                    progress = { p.progressAt(System.currentTimeMillis()) },
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp).height(4.dp),
                    color = Palette.Live, trackColor = Palette.SurfaceHighest, drawStopIndicator = {},
                )
                Text(formatClock(p.endMillis), style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (!state.isLive) ControlButton(Icons.Filled.Replay10, stringResource(R.string.player_rewind), onSeekBack)
            Spacer(Modifier.width(16.dp))
            ControlButton(
                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                stringResource(if (state.isPlaying) R.string.player_pause else R.string.player_play),
                onTogglePlay, size = 72.dp, modifier = Modifier.focusRequester(playFocus), primary = true,
            )
            Spacer(Modifier.width(16.dp))
            if (!state.isLive) ControlButton(Icons.Filled.Forward30, stringResource(R.string.player_forward), onSeekForward)
            if (state.isLive || state.hasNextEpisode) {
                Spacer(Modifier.width(16.dp))
                ControlButton(Icons.Filled.SkipNext, stringResource(if (state.isLive) R.string.player_channel_up else R.string.player_next_episode), onNext)
            }
            Spacer(Modifier.width(40.dp))
            if (state.isLive && state.channels.isNotEmpty()) {
                ControlButton(Icons.AutoMirrored.Filled.List, stringResource(R.string.player_channel_list), onChannels)
                Spacer(Modifier.width(12.dp))
            }
            if (state.audioTracks.size > 1) {
                ControlButton(Icons.Filled.Audiotrack, stringResource(R.string.player_audio), onAudio)
                Spacer(Modifier.width(12.dp))
            }
            ControlButton(Icons.Filled.Subtitles, stringResource(R.string.player_subtitles), onSubtitles)
            Spacer(Modifier.width(12.dp))
            ControlButton(Icons.Filled.AspectRatio, stringResource(state.aspect.label()), onAspect)
            Spacer(Modifier.width(12.dp))
            ControlButton(Icons.Filled.Settings, stringResource(R.string.player_settings), onSettings)
        }
    }
}

/** Semi-transparent channel list over the running stream; Left/Right or Back closes it without stopping playback. */
@Composable
private fun ChannelPanel(
    channels: List<Channel>,
    currentId: String?,
    favoritesOnly: Boolean,
    onSelect: (Channel) -> Unit,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState()
    val currentFocus = remember { FocusRequester() }
    val currentIndex = channels.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
    LaunchedEffect(currentId) {
        if (channels.isNotEmpty()) listState.scrollToItem((currentIndex - 3).coerceAtLeast(0))
        runCatching { currentFocus.requestFocus() }
    }
    Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null, onClick = onDismiss)) {
        Column(
            Modifier.align(Alignment.CenterStart).fillMaxHeight().width(360.dp)
                .background(Brush.horizontalGradient(listOf(Palette.Background.copy(alpha = 0.92f), Palette.Background.copy(alpha = 0.72f), Color.Transparent)))
                .padding(start = 24.dp, end = 40.dp, top = 28.dp, bottom = 28.dp)
                .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { onDismiss(); true } else false },
        ) {
            Text(
                stringResource(if (favoritesOnly) R.string.nav_favorites else R.string.player_channel_list),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(stringResource(R.string.items_count, channels.size), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            Spacer(Modifier.height(12.dp))
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxHeight()) {
                itemsIndexed(channels, key = { _, c -> c.id }) { index, channel ->
                    val interaction = rememberInteractionSource()
                    val focused by rememberFocusState(interaction)
                    val selected = channel.id == currentId
                    Row(
                        Modifier.fillMaxWidth()
                            .let { if (index == currentIndex) it.focusRequester(currentFocus) else it }
                            .focusGlow(interaction, RoundedCornerShape(10.dp), focusedScale = 1.02f, borderWidth = 2.dp, glowColor = Palette.ElectricBlue)
                            .clip(RoundedCornerShape(10.dp))
                            .background(when { focused -> Palette.NeonPurple.copy(alpha = 0.85f); selected -> Color.White.copy(alpha = 0.14f); else -> Color.Transparent })
                            .clickable(interactionSource = interaction, indication = null) { onSelect(channel) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            channel.channelNumber?.toString() ?: "${index + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected && !focused) Palette.ElectricBlue else Color.White,
                            modifier = Modifier.width(40.dp),
                        )
                        Text(channel.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White)
                    }
                }
            }
        }
    }
}

/** Semi-transparent quick settings (audio, subtitles, aspect ratio) that keep the stream playing behind. */
@Composable
private fun SettingsPanel(
    state: PlayerUiState,
    onAudio: () -> Unit,
    onSubtitles: () -> Unit,
    onAspect: (AspectRatioMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null, onClick = onDismiss)) {
        Column(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(380.dp)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, Palette.Background.copy(alpha = 0.72f), Palette.Background.copy(alpha = 0.92f))))
                .padding(start = 40.dp, end = 24.dp, top = 28.dp, bottom = 28.dp)
                .verticalScroll(rememberScrollState())
                .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { onDismiss(); true } else false },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.player_settings), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.player_audio), style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
            Pill(
                state.audioTracks.firstOrNull { it.selected }?.label ?: stringResource(R.string.player_track_default),
                selected = false,
                modifier = Modifier.fillMaxWidth().focusRequester(first),
                onClick = onAudio,
            )
            Text(stringResource(R.string.player_subtitles), style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
            Pill(
                if (!state.subtitlesEnabled) stringResource(R.string.player_subtitles_off)
                else state.subtitleTracks.firstOrNull { it.selected }?.label ?: stringResource(R.string.player_track_default),
                selected = false,
                modifier = Modifier.fillMaxWidth(),
                onClick = onSubtitles,
            )
            Text(stringResource(R.string.player_aspect_ratio), style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
            AspectRatioMode.entries.forEach { mode ->
                Pill(stringResource(mode.label()), selected = mode == state.aspect, modifier = Modifier.fillMaxWidth()) { onAspect(mode) }
            }
        }
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    primary: Boolean = false,
) {
    val interaction = rememberInteractionSource()
    Box(
        modifier.size(size)
            .focusGlow(interaction, CircleShape, focusedScale = 1.12f, borderWidth = 2.dp, glowColor = if (primary) Palette.NeonPurple else Palette.ElectricBlue)
            .clip(CircleShape)
            .background(if (primary) Palette.NeonPurple else Color.White.copy(alpha = 0.12f))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(size / 2)) }
}

@Composable
private fun TrackPanel(
    title: String,
    options: List<TrackOption>,
    showOff: Boolean,
    offSelected: Boolean,
    onSelect: (TrackOption) -> Unit,
    onOff: () -> Unit,
    onLoadExternal: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).clickable(interactionSource = null, indication = null, onClick = onDismiss)) {
        Column(
            Modifier.align(Alignment.CenterEnd).widthIn(min = 280.dp, max = 400.dp).padding(24.dp)
                .clip(RoundedCornerShape(20.dp)).background(Palette.SurfaceElevated.copy(alpha = 0.9f)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (showOff) Pill(stringResource(R.string.player_subtitles_off), offSelected, Modifier.fillMaxWidth().focusRequester(first), onClick = onOff)
            options.forEachIndexed { i, o ->
                Pill(o.label, o.selected, Modifier.fillMaxWidth().let { if (!showOff && i == 0) it.focusRequester(first) else it }) { onSelect(o) }
            }
            if (options.isEmpty() && !showOff) Text(stringResource(R.string.player_no_tracks), color = Palette.Muted)
            if (onLoadExternal != null) Pill(stringResource(R.string.player_load_subtitle), false, Modifier.fillMaxWidth(), onClick = onLoadExternal)
        }
    }
}

private const val OSD_TIMEOUT_MS = 5_000L

private fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")

private fun formatClock(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(clockFormat)
