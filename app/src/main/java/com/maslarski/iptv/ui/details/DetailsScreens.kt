package com.maslarski.iptv.ui.details

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.maslarski.iptv.R
import com.maslarski.iptv.domain.model.Episode
import com.maslarski.iptv.domain.model.Movie
import com.maslarski.iptv.domain.model.Series
import com.maslarski.iptv.ui.components.Badge
import com.maslarski.iptv.ui.components.GlowButton
import com.maslarski.iptv.ui.components.LoadingState
import com.maslarski.iptv.ui.components.Pill
import com.maslarski.iptv.ui.components.PosterCard
import com.maslarski.iptv.ui.components.focusGlow
import com.maslarski.iptv.ui.components.rememberInteractionSource
import com.maslarski.iptv.ui.theme.Palette

@Composable
fun MovieDetailsScreen(
    onPlay: (Movie) -> Unit,
    isCompact: Boolean,
    viewModel: MovieDetailsViewModel = hiltViewModel(),
) {
    val movie by viewModel.movie.collectAsStateWithLifecycle()
    val m = movie ?: run { LoadingState(); return }
    DetailsScaffold(
        title = m.title,
        backdrop = m.backdropUrl ?: m.posterUrl,
        poster = m.posterUrl,
        isCompact = isCompact,
        meta = listOfNotNull(
            m.releaseYear,
            m.durationSeconds?.let { stringResource(R.string.duration_minutes, it / 60) },
            m.genre,
        ),
        rating = m.rating,
        synopsis = m.synopsis,
        facts = listOfNotNull(
            m.director?.let { stringResource(R.string.label_director) to it },
            m.cast?.let { stringResource(R.string.label_cast) to it },
        ),
        isFavorite = m.isFavorite,
        onToggleFavorite = viewModel::toggleFavorite,
        primaryAction = {
            val p = m.progress
            val label = if (p != null && !p.isFinished && p.fraction > 0.01f) stringResource(R.string.action_resume) else stringResource(R.string.action_play)
            GlowButton(label, { onPlay(m) }, icon = Icons.Filled.PlayArrow, requestInitialFocus = true)
        },
        progress = m.progress?.fraction,
    ) {}
}

@Composable
fun SeriesDetailsScreen(
    onPlayEpisode: (Episode) -> Unit,
    isCompact: Boolean,
    viewModel: SeriesDetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val s = state.series ?: run { LoadingState(); return }
    var season by remember { mutableIntStateOf(-1) }
    LaunchedEffect(state.seasons) {
        if (season == -1 || season !in state.seasons) season = state.nextUp?.seasonNumber ?: state.seasons.firstOrNull() ?: -1
    }
    DetailsScaffold(
        title = s.title,
        backdrop = s.backdropUrl ?: s.posterUrl,
        poster = s.posterUrl,
        isCompact = isCompact,
        meta = listOfNotNull(
            s.releaseYear,
            s.genre,
            state.seasons.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.series_seasons_count, it.size) },
        ),
        rating = s.rating,
        synopsis = s.synopsis,
        facts = listOfNotNull(s.cast?.let { stringResource(R.string.label_cast) to it }),
        isFavorite = s.isFavorite,
        onToggleFavorite = viewModel::toggleFavorite,
        primaryAction = {
            val next = state.nextUp
            if (next != null) {
                val label = if (next.progress != null && !next.progress.isFinished) stringResource(R.string.action_resume) else stringResource(R.string.action_play)
                GlowButton("$label · S${next.seasonNumber} E${next.episodeNumber}", { onPlayEpisode(next) }, icon = Icons.Filled.PlayArrow, requestInitialFocus = true)
            }
        },
    ) {
        if (state.loadingEpisodes) {
            Text(stringResource(R.string.loading), color = Palette.Muted, modifier = Modifier.padding(horizontal = 48.dp))
        } else if (state.seasons.isEmpty()) {
            Text(stringResource(R.string.series_no_episodes), color = Palette.Muted, modifier = Modifier.padding(horizontal = 48.dp))
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.seasons) { n -> Pill(stringResource(R.string.series_season, n), n == season) { season = n } }
            }
            Spacer(Modifier.height(16.dp))
            Crossfade(targetState = season, label = "season") { sel ->
                Column(Modifier.padding(horizontal = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.episodesOf(sel).forEach { ep -> EpisodeRow(ep) { onPlayEpisode(ep) } }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(ep: Episode, onClick: () -> Unit) {
    val interaction = rememberInteractionSource()
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth()
            .focusGlow(interaction, shape, focusedScale = 1.01f, borderWidth = 2.dp, glowColor = Palette.ElectricBlue)
            .clip(shape).background(Palette.Surface)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(160.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Palette.SurfaceHighest)) {
            if (ep.thumbnailUrl != null) AsyncImage(ep.thumbnailUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Icon(Icons.Filled.PlayArrow, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.align(Alignment.Center))
            val p = ep.progress
            if (p != null && p.fraction > 0.01f) {
                LinearProgressIndicator(
                    progress = { p.fraction },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    color = Palette.NeonPurple, trackColor = Color.Black.copy(alpha = 0.4f),
                    drawStopIndicator = {},
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.series_episode_label, ep.episodeNumber, ep.title), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(
                ep.durationSeconds?.let { stringResource(R.string.duration_minutes, it / 60) },
                ep.synopsis,
            ).joinToString("  ·  ")
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DetailsScaffold(
    title: String,
    backdrop: String?,
    poster: String?,
    isCompact: Boolean,
    meta: List<String>,
    rating: Double?,
    synopsis: String?,
    facts: List<Pair<String, String>>,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    primaryAction: @Composable () -> Unit,
    progress: Float? = null,
    content: @Composable () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(if (isCompact) 300.dp else 420.dp)) {
                if (backdrop != null) AsyncImage(backdrop, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Palette.HeroScrim))
                Box(Modifier.fillMaxSize().background(Palette.HeroSideScrim))
                Row(Modifier.align(Alignment.BottomStart).padding(horizontal = 48.dp, vertical = 28.dp), verticalAlignment = Alignment.Bottom) {
                    if (!isCompact && poster != null) {
                        PosterCard(title = "", imageUrl = poster, width = 180.dp, onClick = {})
                        Spacer(Modifier.width(32.dp))
                    }
                    Column(Modifier.widthIn(max = 760.dp)) {
                        Text(title, style = if (isCompact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (rating != null && rating > 0) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Star, null, tint = Palette.Gold, modifier = Modifier.width(18.dp))
                                    Text(" %.1f".format(rating), color = Palette.Gold, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                            meta.forEach { Badge(it) }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            primaryAction()
                            GlowButton(
                                stringResource(if (isFavorite) R.string.action_remove_favorite else R.string.action_add_favorite),
                                onToggleFavorite,
                                icon = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                primary = false,
                            )
                        }
                        if (progress != null && progress > 0.01f) {
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.width(320.dp).height(4.dp), color = Palette.NeonPurple, trackColor = Palette.SurfaceHighest, drawStopIndicator = {})
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 48.dp, vertical = 20.dp).widthIn(max = 900.dp)) {
                if (!synopsis.isNullOrBlank()) {
                    Text(stringResource(R.string.label_synopsis), style = MaterialTheme.typography.labelLarge, color = Palette.NeonPurple)
                    Spacer(Modifier.height(6.dp))
                    Text(synopsis, style = MaterialTheme.typography.bodyLarge, color = Palette.OnSurface)
                    Spacer(Modifier.height(16.dp))
                }
                facts.forEach { (label, value) ->
                    Row {
                        Text(label, style = MaterialTheme.typography.labelLarge, color = Palette.Muted, modifier = Modifier.width(110.dp))
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
        item { content() }
    }
}
