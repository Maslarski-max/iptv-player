package com.maslarski.iptv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.maslarski.iptv.R
import com.maslarski.iptv.data.repository.ContentRepository
import com.maslarski.iptv.data.repository.PlaylistRepository
import com.maslarski.iptv.domain.model.Category
import com.maslarski.iptv.domain.model.Channel
import com.maslarski.iptv.domain.model.ContentType
import com.maslarski.iptv.ui.components.Badge
import com.maslarski.iptv.ui.components.EmptyState
import com.maslarski.iptv.ui.components.SectionHeader
import com.maslarski.iptv.ui.components.dpadLongPress
import com.maslarski.iptv.ui.components.rememberDpadLongPressState
import com.maslarski.iptv.ui.components.rememberFocusState
import com.maslarski.iptv.ui.components.rememberInteractionSource
import com.maslarski.iptv.ui.theme.Palette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Identifies a category row across the three content types. */
data class CategoryKey(val type: ContentType, val id: String)

data class ManageUiState(
    val hasPlaylist: Boolean = false,
    val categories: Map<ContentType, List<Category>> = emptyMap(),
    val expanded: CategoryKey? = null,
    val channels: List<Channel> = emptyList(),
    val movingCategory: CategoryKey? = null,
    val movingChannelId: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ManageCategoriesViewModel @Inject constructor(
    playlists: PlaylistRepository,
    private val content: ContentRepository,
) : ViewModel() {
    private val expanded = MutableStateFlow<CategoryKey?>(null)
    private val movingCategory = MutableStateFlow<CategoryKey?>(null)
    private val movingChannel = MutableStateFlow<String?>(null)

    // While an item is being moved the list is edited in memory and each step is written to Room;
    // the in-memory copy keeps rapid key presses consistent until the database flow catches up.
    private val categoryOverride = MutableStateFlow<List<Category>?>(null)
    private val channelOverride = MutableStateFlow<List<Channel>?>(null)

    private val dbCategories = playlists.activePlaylist.flatMapLatest { p ->
        if (p == null) flowOf(null)
        else combine(
            content.editableCategories(p.id, ContentType.LIVE),
            content.editableCategories(p.id, ContentType.MOVIE),
            content.editableCategories(p.id, ContentType.SERIES),
        ) { l, m, s -> mapOf(ContentType.LIVE to l, ContentType.MOVIE to m, ContentType.SERIES to s) }
    }

    private val dbChannels = combine(playlists.activePlaylist, expanded) { p, key -> p?.id to key }
        .flatMapLatest { (playlistId, key) ->
            if (playlistId == null || key == null) flowOf(emptyList()) else content.editableChannels(playlistId, key.id)
        }

    val state: StateFlow<ManageUiState> = combine(
        combine(dbCategories, categoryOverride, movingCategory) { cats, override, moving ->
            val map = cats ?: return@combine null to moving
            if (override != null && moving != null) map + (moving.type to override) to moving else map to moving
        },
        combine(dbChannels, channelOverride, movingChannel) { chans, override, moving ->
            (if (override != null && moving != null) override else chans) to moving
        },
        expanded,
    ) { (cats, movingCat), (chans, movingChan), exp ->
        ManageUiState(
            hasPlaylist = cats != null,
            categories = cats ?: emptyMap(),
            expanded = exp,
            channels = chans,
            movingCategory = movingCat,
            movingChannelId = movingChan,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ManageUiState())

    fun toggleExpanded(category: Category) {
        if (category.type != ContentType.LIVE) return
        val key = CategoryKey(category.type, category.id)
        expanded.value = if (expanded.value == key) null else key
    }

    fun collapse() { expanded.value = null }

    fun startMovingCategory(category: Category) {
        finishMoving()
        movingCategory.value = CategoryKey(category.type, category.id)
        categoryOverride.value = state.value.categories[category.type]
    }

    fun startMovingChannel(channel: Channel) {
        finishMoving()
        movingChannel.value = channel.id
        channelOverride.value = state.value.channels
    }

    fun finishMoving() {
        movingCategory.value = null
        movingChannel.value = null
        categoryOverride.value = null
        channelOverride.value = null
    }

    /** Moves the item currently in Move Mode by [delta] rows (-1 = up, +1 = down) and persists the result. */
    fun moveBy(delta: Int) {
        movingCategory.value?.let { key ->
            val list = categoryOverride.value ?: return
            val moved = list.swapped({ it.id == key.id }, delta) ?: return
            categoryOverride.value = moved
            viewModelScope.launch { content.saveCategoryOrder(moved) }
            return
        }
        movingChannel.value?.let { id ->
            val list = channelOverride.value ?: return
            val moved = list.swapped({ it.id == id }, delta) ?: return
            channelOverride.value = moved
            viewModelScope.launch { content.saveChannelOrder(moved) }
        }
    }

    fun toggleVisible(category: Category) = viewModelScope.launch { content.setCategoryVisible(category, !category.isVisible) }
    fun toggleVisible(channel: Channel) = viewModelScope.launch { content.setChannelVisible(channel, !channel.isVisible) }

    private fun <T> List<T>.swapped(match: (T) -> Boolean, delta: Int): List<T>? {
        val from = indexOfFirst(match)
        val to = from + delta
        if (from < 0 || to < 0 || to >= size) return null
        return toMutableList().apply { add(to, removeAt(from)) }
    }
}

@Composable
fun ManageCategoriesScreen(viewModel: ManageCategoriesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val focusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    fun requesterFor(key: String) = focusRequesters.getOrPut(key) { FocusRequester() }

    val rows = remember(state.categories, state.expanded, state.channels) { buildRows(state) }
    val movingKey = state.movingCategory?.let { "cat:${it.type}:${it.id}" } ?: state.movingChannelId?.let { "ch:$it" }
    BackHandler(enabled = movingKey != null) { viewModel.finishMoving() }

    // Keep focus glued to the item being moved as it changes position (and scrolls into view if needed).
    LaunchedEffect(rows, movingKey) {
        val key = movingKey ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it.key == key }
        if (index < 0) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        val fullyVisible = visible.any { it.index == index && it.offset >= 0 && it.offset + it.size <= listState.layoutInfo.viewportEndOffset }
        if (!fullyVisible) listState.scrollToItem(index, scrollOffset = -listState.layoutInfo.viewportSize.height / 3)
        withFrameNanos { }
        withFrameNanos { }
        runCatching { requesterFor(key).requestFocus() }
    }

    if (!state.hasPlaylist) {
        EmptyState(title = stringResource(R.string.manage_categories_title), body = stringResource(R.string.manage_no_playlist))
        return
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(horizontal = 48.dp, vertical = 24.dp)) {
        item(key = "header") {
            Text(stringResource(R.string.manage_categories_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.manage_hint), style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
            Spacer(Modifier.height(8.dp))
        }
        rows.forEach { row ->
            when (row) {
                is ManageRow.Header -> item(key = row.key) {
                    SectionHeader(stringResource(row.type.label()))
                }
                is ManageRow.CategoryRow -> item(key = row.key) {
                    val c = row.category
                    val moving = state.movingCategory == CategoryKey(c.type, c.id)
                    ManageItemRow(
                        title = c.name,
                        subtitle = stringResource(R.string.manage_items, c.itemCount),
                        visible = c.isVisible,
                        moving = moving,
                        anyMoving = movingKey != null,
                        expandable = c.type == ContentType.LIVE,
                        expanded = state.expanded == CategoryKey(c.type, c.id),
                        focusRequester = requesterFor(row.key),
                        onClick = { if (moving) viewModel.finishMoving() else viewModel.startMovingCategory(c) },
                        onToggleVisible = { viewModel.toggleVisible(c) },
                        onMove = viewModel::moveBy,
                        onFinishMoving = viewModel::finishMoving,
                        onExpand = { viewModel.toggleExpanded(c) },
                        onCollapse = { if (state.expanded == CategoryKey(c.type, c.id)) viewModel.collapse() },
                    )
                }
                is ManageRow.ChannelRow -> item(key = row.key) {
                    val ch = row.channel
                    val moving = state.movingChannelId == ch.id
                    val parentKey = state.expanded?.let { "cat:${it.type}:${it.id}" }
                    ManageItemRow(
                        title = ch.name,
                        subtitle = null,
                        visible = ch.isVisible && row.categoryVisible,
                        moving = moving,
                        anyMoving = movingKey != null,
                        logoUrl = ch.logoUrl,
                        indent = 40.dp,
                        focusRequester = requesterFor(row.key),
                        onClick = { if (moving) viewModel.finishMoving() else viewModel.startMovingChannel(ch) },
                        onToggleVisible = { viewModel.toggleVisible(ch) },
                        onMove = viewModel::moveBy,
                        onFinishMoving = viewModel::finishMoving,
                        onCollapse = {
                            viewModel.collapse()
                            parentKey?.let { runCatching { requesterFor(it).requestFocus() } }
                        },
                    )
                }
            }
        }
    }
}

private sealed interface ManageRow {
    val key: String
    data class Header(val type: ContentType) : ManageRow { override val key get() = "header:$type" }
    data class CategoryRow(val category: Category) : ManageRow { override val key get() = "cat:${category.type}:${category.id}" }
    data class ChannelRow(val channel: Channel, val categoryVisible: Boolean) : ManageRow { override val key get() = "ch:${channel.id}" }
}

private fun buildRows(state: ManageUiState): List<ManageRow> = buildList {
    ContentType.entries.forEach { type ->
        val cats = state.categories[type].orEmpty()
        if (cats.isEmpty()) return@forEach
        add(ManageRow.Header(type))
        cats.forEach { c ->
            add(ManageRow.CategoryRow(c))
            if (state.expanded == CategoryKey(c.type, c.id)) {
                state.channels.forEach { add(ManageRow.ChannelRow(it, c.isVisible)) }
            }
        }
    }
}

@Composable
private fun ManageItemRow(
    title: String,
    subtitle: String?,
    visible: Boolean,
    moving: Boolean,
    anyMoving: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    onToggleVisible: () -> Unit,
    onMove: (Int) -> Unit,
    onFinishMoving: () -> Unit,
    onCollapse: () -> Unit,
    onExpand: (() -> Unit)? = null,
    expandable: Boolean = false,
    expanded: Boolean = false,
    logoUrl: String? = null,
    indent: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val interaction = rememberInteractionSource()
    val focused by rememberFocusState(interaction)
    val longPress = rememberDpadLongPressState()
    val shape = RoundedCornerShape(12.dp)
    val background by animateColorAsState(
        when {
            moving -> Palette.NeonPurple.copy(alpha = 0.45f)
            focused -> Palette.SurfaceHighest
            else -> Palette.Surface
        },
        label = "manageRowBg",
    )
    val borderColor = when {
        moving -> Palette.NeonPurple
        focused -> Palette.ElectricBlue
        else -> Color.Transparent
    }

    Row(
        Modifier.fillMaxWidth().padding(start = indent, top = 3.dp, bottom = 3.dp)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                val down = event.type == KeyEventType.KeyDown
                val isSelect = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                when {
                    moving -> when (event.key) {
                        Key.DirectionUp -> { if (down) onMove(-1); true }
                        Key.DirectionDown -> { if (down) onMove(1); true }
                        Key.Back, Key.Escape -> { if (down) onFinishMoving(); true }
                        Key.DirectionLeft, Key.DirectionRight -> true
                        else -> if (isSelect) { if (!down) onFinishMoving(); true } else false
                    }
                    // Another row is in Move Mode: swallow selects so focus can't start a second move.
                    anyMoving && isSelect -> true
                    down && event.key == Key.DirectionRight && expandable && onExpand != null -> { if (!expanded) onExpand(); true }
                    down && event.key == Key.DirectionLeft -> { onCollapse(); true }
                    else -> false
                }
            }
            .dpadLongPress(longPress, if (moving) null else onToggleVisible)
            .clip(shape)
            .background(background)
            .border(if (moving || focused) 2.dp else 0.dp, borderColor, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = longPress.click(onClick))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.DragHandle, contentDescription = null,
            tint = if (moving) Color.White else Palette.Muted.copy(alpha = if (focused) 1f else 0.4f),
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        if (logoUrl != null) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).background(Palette.SurfaceElevated)) {
                AsyncImage(logoUrl, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(3.dp))
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f).alpha(if (visible) 1f else 0.45f)) {
            Text(
                title, style = MaterialTheme.typography.titleMedium,
                fontWeight = if (moving) FontWeight.Bold else FontWeight.Medium, maxLines = 1,
            )
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
        Spacer(Modifier.width(12.dp))
        if (moving) {
            Badge(stringResource(R.string.manage_moving), color = Palette.NeonPurple, textColor = Color.White)
            Spacer(Modifier.width(12.dp))
        } else if (!visible) {
            Badge(stringResource(R.string.manage_hidden), color = Palette.SurfaceHighest, textColor = Palette.Muted)
            Spacer(Modifier.width(12.dp))
        }
        Icon(
            if (visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
            contentDescription = stringResource(if (visible) R.string.manage_hide else R.string.manage_show),
            tint = if (visible) Palette.Success else Palette.Muted,
            modifier = Modifier.size(22.dp),
        )
        if (expandable) {
            Spacer(Modifier.width(12.dp))
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null, tint = Palette.Muted,
            )
        }
    }
    Spacer(Modifier.height(2.dp))
}
