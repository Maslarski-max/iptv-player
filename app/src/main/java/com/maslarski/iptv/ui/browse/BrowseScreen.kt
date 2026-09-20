package com.maslarski.iptv.ui.browse

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maslarski.iptv.R
import com.maslarski.iptv.domain.model.Category
import com.maslarski.iptv.domain.model.ContentType
import com.maslarski.iptv.domain.model.MediaItem
import com.maslarski.iptv.ui.components.ChannelCard
import com.maslarski.iptv.ui.components.EmptyState
import com.maslarski.iptv.ui.components.LoadingState
import com.maslarski.iptv.ui.components.Pill
import com.maslarski.iptv.ui.components.PinDialog
import com.maslarski.iptv.ui.components.PosterCard
import com.maslarski.iptv.ui.theme.Palette

@Composable
fun BrowseScreen(
    title: String,
    type: ContentType,
    viewModel: BrowseViewModel,
    isCompact: Boolean,
    onItemClick: (MediaItem) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pinError by remember { mutableStateOf<String?>(null) }
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

    when {
        state.loading -> LoadingState()
        state.categories.isEmpty() && state.items.isEmpty() -> EmptyState(stringResource(R.string.empty_section))
        isCompact -> Column(Modifier.fillMaxSize()) {
            Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Pill(stringResource(R.string.category_all), state.selectedCategoryId == null) { viewModel.selectCategory(null) } }
                items(state.categories, key = { it.id }) { c ->
                    Pill(c.name, c.id == state.selectedCategoryId, locked = c.id in state.lockedCategoryIds) { viewModel.selectCategory(c.id) }
                }
            }
            ContentGrid(type, state, onItemClick, viewModel::toggleFavorite, padding = 20.dp, minCell = if (type == ContentType.LIVE) 180.dp else 110.dp)
        }
        else -> Row(Modifier.fillMaxSize()) {
            CategoryRail(title, state.categories, state.selectedCategoryId, state.lockedCategoryIds, viewModel::selectCategory)
            ContentGrid(type, state, onItemClick, viewModel::toggleFavorite, padding = 32.dp, minCell = if (type == ContentType.LIVE) 240.dp else 150.dp)
        }
    }
}

@Composable
private fun CategoryRail(
    title: String,
    categories: List<Category>,
    selected: String?,
    locked: Set<String>,
    onSelect: (String?) -> Unit,
) {
    Column(Modifier.width(280.dp).fillMaxHeight().padding(start = 32.dp, top = 32.dp, end = 12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 4.dp))
        Text(stringResource(R.string.categories_title), style = MaterialTheme.typography.labelMedium, color = Palette.Muted, modifier = Modifier.padding(bottom = 16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { Pill(stringResource(R.string.category_all), selected == null, Modifier.fillMaxWidth()) { onSelect(null) } }
            items(categories, key = { it.id }) { c ->
                Pill("${c.name}  ·  ${c.itemCount}", c.id == selected, Modifier.fillMaxWidth(), locked = c.id in locked) { onSelect(c.id) }
            }
        }
    }
}

@Composable
private fun ContentGrid(
    type: ContentType,
    state: BrowseUiState,
    onItemClick: (MediaItem) -> Unit,
    onLongClick: (MediaItem) -> Unit,
    padding: Dp,
    minCell: Dp,
) {
    Crossfade(targetState = state.selectedCategoryId to state.items, label = "grid") { (_, gridItems) ->
        Box(Modifier.fillMaxSize()) {
            if (gridItems.isEmpty()) {
                EmptyState(stringResource(R.string.empty_section))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minCell),
                    contentPadding = PaddingValues(padding),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(gridItems, key = { it.id }) { item ->
                        if (type == ContentType.LIVE) {
                            val program = item.subtitle?.let { state.nowPlaying[it] }
                            ChannelCard(
                                name = item.title,
                                logoUrl = item.imageUrl,
                                nowPlaying = program?.title,
                                progress = program?.progressAt(System.currentTimeMillis()),
                                number = item.channelNumber,
                                isFavorite = item.isFavorite,
                                width = minCell,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onItemClick(item) },
                                onLongClick = { onLongClick(item) },
                            )
                        } else {
                            PosterCard(
                                title = item.title,
                                imageUrl = item.imageUrl,
                                subtitle = item.subtitle,
                                rating = item.rating,
                                isFavorite = item.isFavorite,
                                width = minCell,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onItemClick(item) },
                                onLongClick = { onLongClick(item) },
                            )
                        }
                    }
                }
            }
        }
    }
}
