package com.maslarski.iptv.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.maslarski.iptv.R
import com.maslarski.iptv.domain.model.ContentType
import com.maslarski.iptv.domain.model.MediaItem
import com.maslarski.iptv.ui.components.Badge
import com.maslarski.iptv.ui.components.EmptyState
import com.maslarski.iptv.ui.components.GlowButton
import com.maslarski.iptv.ui.components.LandscapeAspect
import com.maslarski.iptv.ui.components.LoadingState
import com.maslarski.iptv.ui.components.MediaRow
import com.maslarski.iptv.ui.theme.Palette
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    onPlay: (MediaItem) -> Unit,
    onOpenDetails: (MediaItem) -> Unit,
    onAddPlaylist: () -> Unit,
    onOpenFeatured: (Featured) -> Unit,
    isCompact: Boolean,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when (state.hasPlaylists) {
        null -> LoadingState()
        false -> EmptyState(
            title = stringResource(R.string.home_empty_title),
            body = stringResource(R.string.home_empty_body),
            icon = Icons.Filled.PlaylistPlay,
            action = { GlowButton(stringResource(R.string.action_add_playlist), onAddPlaylist, icon = Icons.Filled.Add) },
        )
        true -> HomeContent(state, onPlay, onOpenDetails, onOpenFeatured, viewModel::toggleFavorite, isCompact)
    }
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    onPlay: (MediaItem) -> Unit,
    onOpenDetails: (MediaItem) -> Unit,
    onOpenFeatured: (Featured) -> Unit,
    onToggleFavorite: (MediaItem) -> Unit,
    isCompact: Boolean,
) {
    val open: (MediaItem) -> Unit = { item ->
        when (item.type) {
            ContentType.LIVE -> onPlay(item)
            ContentType.MOVIE -> if (item.progress != null) onPlay(item) else onOpenDetails(item)
            ContentType.SERIES -> if (item.seriesId != null && item.streamUrl != null) onPlay(item) else onOpenDetails(item)
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
        item(key = "hero") {
            FeaturedBanner(state.featured, onOpenFeatured, isCompact, state.sync.isSyncing, state.sync.message)
        }
        item(key = "continue") {
            MediaRow(stringResource(R.string.home_continue_watching), state.continueWatching, aspect = LandscapeAspect, cardWidth = 240.dp, onClick = onPlay, onLongClick = onToggleFavorite)
        }
        item(key = "favorites") {
            MediaRow(stringResource(R.string.home_favorites), state.favorites, onClick = open, onLongClick = onToggleFavorite)
        }
        item(key = "live") {
            MediaRow(stringResource(R.string.home_live_now), state.liveNow, aspect = LandscapeAspect, cardWidth = 200.dp, onClick = onPlay, onLongClick = onToggleFavorite)
        }
        item(key = "movies") {
            MediaRow(stringResource(R.string.home_recent_movies), state.recentMovies, onClick = onOpenDetails, onLongClick = onToggleFavorite)
        }
        item(key = "series") {
            MediaRow(stringResource(R.string.home_recent_series), state.recentSeries, onClick = onOpenDetails, onLongClick = onToggleFavorite)
        }
    }
}

@Composable
private fun FeaturedBanner(
    featured: List<Featured>,
    onOpen: (Featured) -> Unit,
    isCompact: Boolean,
    syncing: Boolean,
    syncMessage: String?,
) {
    var index by remember(featured.size) { mutableIntStateOf(0) }
    var hasFocus by remember { mutableStateOf(false) }
    var onFirstButton by remember { mutableStateOf(true) }
    fun step(delta: Int) { if (featured.size > 1) index = ((index + delta) % featured.size + featured.size) % featured.size }
    // Auto-advance restarts from the last manual/automatic change and pauses while the banner is focused.
    LaunchedEffect(featured.size, index, hasFocus) {
        if (featured.size > 1 && !hasFocus) {
            delay(8_000)
            step(1)
        }
    }
    val height = if (isCompact) 320.dp else 440.dp
    val borderAlpha by animateFloatAsState(if (hasFocus) 1f else 0f, tween(200), label = "featuredFocus")
    Box(
        Modifier.fillMaxWidth().height(height)
            .onFocusChanged { hasFocus = it.hasFocus }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || !hasFocus || featured.size < 2) return@onPreviewKeyEvent false
                when (e.key) {
                    // At the first item Left falls through so the side rail stays reachable.
                    Key.DirectionLeft -> if (onFirstButton && index > 0) { step(-1); true } else false
                    Key.DirectionRight -> if (!onFirstButton) { step(1); true } else false
                    Key.MediaNext, Key.ChannelUp -> { step(1); true }
                    Key.MediaPrevious, Key.ChannelDown -> { step(-1); true }
                    else -> false
                }
            }
            .drawWithContent {
                drawContent()
                if (borderAlpha > 0f) {
                    drawRect(Palette.FocusGradient, alpha = borderAlpha, style = Stroke(width = 3.dp.toPx()))
                }
            },
    ) {
        val current = featured.getOrNull(index)
        AnimatedContent(
            targetState = current,
            transitionSpec = { fadeIn(tween(700)) togetherWith fadeOut(tween(700)) },
            label = "featured",
        ) { item ->
            Box(Modifier.fillMaxSize()) {
                if (item?.backdrop != null) {
                    AsyncImage(model = item.backdrop, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize().background(Palette.FocusGradient))
                }
                Box(Modifier.fillMaxSize().background(Palette.HeroScrim))
                Box(Modifier.fillMaxSize().background(Palette.HeroSideScrim))
            }
        }
        Column(
            Modifier.align(Alignment.BottomStart).padding(horizontal = 48.dp, vertical = 32.dp).widthIn(max = 640.dp),
        ) {
            AnimatedContent(
                targetState = current,
                transitionSpec = { (fadeIn(tween(500)) + slideInVertically { it / 4 }) togetherWith fadeOut(tween(300)) },
                label = "featuredText",
            ) { item ->
                val badge = item?.badge
                val synopsis = item?.synopsis
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Badge(stringResource(R.string.home_featured), color = Palette.NeonPurple, textColor = Color.White)
                        if (badge != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(badge, style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        item?.title ?: stringResource(R.string.app_name),
                        style = if (isCompact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displayMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!synopsis.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            synopsis,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Palette.Muted,
                            maxLines = if (isCompact) 2 else 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (current != null) {
                    GlowButton(
                        stringResource(R.string.action_watch_now), { onOpen(current) }, icon = Icons.Filled.PlayArrow,
                        modifier = Modifier.onFocusChanged { if (it.isFocused) onFirstButton = true },
                    )
                    GlowButton(
                        stringResource(R.string.action_details), { onOpen(current) }, icon = Icons.Filled.Info, primary = false,
                        modifier = Modifier.onFocusChanged { if (it.isFocused) onFirstButton = false },
                    )
                    if (featured.size > 1 && hasFocus) {
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = if (onFirstButton) Palette.Gold else Palette.Muted)
                        Text("${index + 1} / ${featured.size}", style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = if (!onFirstButton) Palette.Gold else Palette.Muted)
                    }
                }
            }
        }
        if (featured.size > 1) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(48.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                featured.indices.forEach { i ->
                    Box(
                        Modifier.size(width = if (i == index) 24.dp else 8.dp, height = 8.dp)
                            .clip(CircleShape)
                            .background(if (i == index) (if (hasFocus) Palette.Gold else Palette.NeonPurple) else Color.White.copy(alpha = 0.35f)),
                    )
                }
            }
        }
        if (syncing) {
            Row(
                Modifier.align(Alignment.TopEnd).padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(14.dp), color = Palette.ElectricBlue, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(syncMessage ?: stringResource(R.string.loading), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
