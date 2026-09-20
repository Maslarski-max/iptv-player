package com.maslarski.iptv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.maslarski.iptv.domain.model.MediaItem
import com.maslarski.iptv.ui.theme.Palette

val CardShape = RoundedCornerShape(14.dp)
val PosterAspect = 2f / 3f
val LandscapeAspect = 16f / 9f

@Composable
fun PosterCard(
    title: String,
    imageUrl: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    progress: Float? = null,
    rating: Double? = null,
    isFavorite: Boolean = false,
    isLocked: Boolean = false,
    width: Dp = 150.dp,
    aspect: Float = PosterAspect,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val interaction = rememberInteractionSource()
    val focused by rememberFocusState(interaction)
    Column(modifier = modifier.width(width)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .focusGlow(interaction, CardShape)
                .clip(CardShape)
                .background(Palette.SurfaceElevated)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        ) {
            if (isLocked) {
                Box(Modifier.fillMaxSize().background(Palette.SurfaceHighest), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Lock, null, tint = Palette.Gold, modifier = Modifier.size(36.dp))
                }
            } else {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f)),
                    ),
                )
            }
            if (rating != null && rating > 0) {
                Row(
                    Modifier.align(Alignment.TopStart).padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Star, null, tint = Palette.Gold, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(String.format(java.util.Locale.US, "%.1f", rating), style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
            if (isFavorite) {
                Icon(
                    Icons.Filled.Favorite, null, tint = Palette.NeonPurple,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(16.dp),
                )
            }
            if (progress != null && progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                    color = Palette.NeonPurple,
                    trackColor = Color.White.copy(alpha = 0.25f),
                    drawStopIndicator = {},
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = if (focused) Color.White else Palette.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
        )
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun ChannelCard(
    name: String,
    logoUrl: String?,
    modifier: Modifier = Modifier,
    nowPlaying: String? = null,
    progress: Float? = null,
    number: Int? = null,
    isFavorite: Boolean = false,
    isLocked: Boolean = false,
    width: Dp = 200.dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val interaction = rememberInteractionSource()
    Column(
        modifier = modifier
            .width(width)
            .focusGlow(interaction, CardShape, focusedScale = 1.05f, glowColor = Palette.ElectricBlue)
            .clip(CardShape)
            .background(Palette.SurfaceElevated)
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(Palette.SurfaceHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (isLocked) {
                    Icon(Icons.Filled.Lock, null, tint = Palette.Gold)
                } else if (logoUrl != null) {
                    AsyncImage(model = logoUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
                } else {
                    Icon(Icons.Filled.Tv, null, tint = Palette.Muted)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (number != null) {
                        Text("$number", style = MaterialTheme.typography.labelMedium, color = Palette.ElectricBlue)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (isFavorite) Icon(Icons.Filled.Favorite, null, tint = Palette.NeonPurple, modifier = Modifier.size(14.dp))
                }
                Text(
                    nowPlaying ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (progress != null) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                color = Palette.ElectricBlue,
                trackColor = Palette.SurfaceHighest,
                drawStopIndicator = {},
            )
        }
    }
}

@Composable
fun MediaRow(
    title: String,
    items: List<MediaItem>,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 150.dp,
    aspect: Float = PosterAspect,
    lockedCategoryIds: Set<String> = emptySet(),
    onClick: (MediaItem) -> Unit,
    onLongClick: ((MediaItem) -> Unit)? = null,
) {
    AnimatedVisibility(visible = items.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
        Column(modifier) {
            SectionHeader(title)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(items, key = { "${it.type}:${it.id}" }) { item ->
                    PosterCard(
                        title = item.title,
                        imageUrl = item.imageUrl,
                        subtitle = item.subtitle,
                        progress = item.progress,
                        width = cardWidth,
                        aspect = aspect,
                        onClick = { onClick(item) },
                        onLongClick = onLongClick?.let { cb -> { cb(item) } },
                    )
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(22.dp).clip(CircleShape).background(Palette.FocusGradient))
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun Pill(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = rememberInteractionSource()
    val focused by rememberFocusState(interaction)
    val bg = when {
        focused -> Palette.NeonPurple
        selected -> Palette.SurfaceHighest
        else -> Palette.Surface
    }
    Row(
        modifier
            .focusGlow(interaction, CircleShape, focusedScale = 1.04f, borderWidth = 2.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (locked) {
            Icon(Icons.Filled.Lock, null, tint = if (focused) Color.White else Palette.Gold, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (focused || selected) Color.White else Palette.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
