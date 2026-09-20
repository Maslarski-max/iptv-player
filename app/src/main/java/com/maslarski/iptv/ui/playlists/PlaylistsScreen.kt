package com.maslarski.iptv.ui.playlists

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.maslarski.iptv.R
import com.maslarski.iptv.data.repository.PlaylistRepository
import com.maslarski.iptv.data.sync.PlaylistSyncer
import com.maslarski.iptv.data.sync.SyncScheduler
import com.maslarski.iptv.domain.model.Playlist
import com.maslarski.iptv.domain.model.PlaylistType
import com.maslarski.iptv.domain.model.SyncStatus
import com.maslarski.iptv.ui.components.ConfirmDialog
import com.maslarski.iptv.ui.components.EmptyState
import com.maslarski.iptv.ui.components.GlowButton
import com.maslarski.iptv.ui.components.Pill
import com.maslarski.iptv.ui.components.focusGlow
import com.maslarski.iptv.ui.components.rememberInteractionSource
import com.maslarski.iptv.ui.navigation.Route
import com.maslarski.iptv.ui.theme.Palette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

data class PlaylistsUiState(val playlists: List<Playlist>? = null, val sync: SyncStatus = SyncStatus())

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val repo: PlaylistRepository,
    private val syncer: PlaylistSyncer,
) : ViewModel() {
    val state: StateFlow<PlaylistsUiState> = combine(repo.playlists, syncer.status) { p, s -> PlaylistsUiState(p, s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistsUiState())

    fun activate(id: Long) = viewModelScope.launch { repo.setActive(id) }
    fun delete(id: Long) = viewModelScope.launch { repo.delete(id) }
    fun refresh(playlist: Playlist) = viewModelScope.launch { syncer.sync(playlist) }
}

@Composable
fun PlaylistsScreen(
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    viewModel: PlaylistsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val list = state.playlists ?: return
    var pendingDelete by remember { mutableStateOf<Playlist?>(null) }
    pendingDelete?.let { p ->
        DeletePlaylistDialog(p, onConfirm = { viewModel.delete(p.id); pendingDelete = null }, onDismiss = { pendingDelete = null })
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.playlists_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            GlowButton(stringResource(R.string.action_add_playlist), onAdd, icon = Icons.Filled.Add, requestInitialFocus = true)
        }
        Spacer(Modifier.height(20.dp))
        if (list.isEmpty()) {
            EmptyState(stringResource(R.string.home_empty_title), body = stringResource(R.string.home_empty_body), icon = Icons.AutoMirrored.Filled.PlaylistPlay)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 48.dp)) {
                items(list, key = { it.id }) { p ->
                    PlaylistRow(
                        playlist = p,
                        syncing = state.sync.isSyncing && state.sync.playlistId == p.id,
                        syncMessage = state.sync.message,
                        onActivate = { viewModel.activate(p.id) },
                        onEdit = { onEdit(p.id) },
                        onRefresh = { viewModel.refresh(p) },
                        onDelete = { pendingDelete = p },
                    )
                }
            }
        }
    }
}

@Composable
fun DeletePlaylistDialog(playlist: Playlist, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.playlist_delete_title, playlist.name),
        body = stringResource(R.string.playlist_delete_body),
        confirmText = stringResource(R.string.action_delete),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
fun PlaylistRow(
    playlist: Playlist,
    syncing: Boolean,
    syncMessage: String?,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    val interaction = rememberInteractionSource()
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth()
            .focusGlow(interaction, shape, focusedScale = 1.01f, borderWidth = 2.dp, glowColor = if (playlist.isActive) Palette.Gold else Palette.NeonPurple)
            .clip(shape).background(if (playlist.isActive) Palette.SurfaceElevated else Palette.Surface)
            .clickable(interactionSource = interaction, indication = null, onClick = onActivate)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(if (playlist.isActive) Palette.Gold else Palette.SurfaceHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (playlist.isActive) Icons.Filled.Check else Icons.AutoMirrored.Filled.PlaylistPlay, null, tint = if (playlist.isActive) Palette.Background else Palette.Muted)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(playlist.name, style = MaterialTheme.typography.titleLarge)
                val typeLabel = if (playlist.type == PlaylistType.XTREAM) stringResource(R.string.playlist_type_xtream) else stringResource(R.string.playlist_type_m3u)
                val synced = playlist.lastSyncedAt?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
                Text(
                    listOfNotNull(typeLabel, synced?.let { stringResource(R.string.playlist_last_synced, it) }).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall, color = Palette.Muted,
                )
                Text(
                    stringResource(R.string.playlist_counts, playlist.channelCount, playlist.movieCount, playlist.seriesCount),
                    style = MaterialTheme.typography.bodySmall, color = Palette.ElectricBlue,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconAction(Icons.Filled.Refresh, stringResource(R.string.action_refresh), onRefresh)
                IconAction(Icons.Filled.Edit, stringResource(R.string.action_edit), onEdit)
                IconAction(Icons.Filled.Delete, stringResource(R.string.action_delete), onDelete, tint = Palette.Danger)
            }
        }
        if (syncing) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = Palette.NeonPurple, trackColor = Palette.SurfaceHighest)
            if (syncMessage != null) {
                Spacer(Modifier.height(4.dp))
                Text(syncMessage, style = MaterialTheme.typography.labelSmall, color = Palette.Muted)
            }
        }
    }
}

@Composable
private fun IconAction(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = Palette.OnSurface) {
    val interaction = rememberInteractionSource()
    Box(
        Modifier.size(44.dp)
            .focusGlow(interaction, CircleShape, focusedScale = 1.1f, borderWidth = 2.dp, glowColor = Palette.ElectricBlue)
            .clip(CircleShape).background(Palette.SurfaceHighest)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp)) }
}

// ---------- Add / edit ----------

data class EditPlaylistUiState(
    val id: Long = 0L,
    val name: String = "",
    val type: PlaylistType = PlaylistType.M3U,
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val epgUrl: String = "",
    val saving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
) {
    val isValid: Boolean
        get() = name.isNotBlank() && url.isNotBlank() &&
            (type == PlaylistType.M3U || (username.isNotBlank() && password.isNotBlank()))
}

@HiltViewModel
class EditPlaylistViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: PlaylistRepository,
    private val scheduler: SyncScheduler,
) : ViewModel() {
    private val route = savedState.toRoute<Route.EditPlaylist>()
    private val _state = MutableStateFlow(EditPlaylistUiState(id = route.playlistId))
    val state: StateFlow<EditPlaylistUiState> = _state.asStateFlow()

    init {
        if (route.playlistId != 0L) viewModelScope.launch {
            repo.getById(route.playlistId)?.let { p ->
                _state.value = EditPlaylistUiState(
                    id = p.id, name = p.name, type = p.type, url = p.url,
                    username = p.username.orEmpty(), password = p.password.orEmpty(), epgUrl = p.epgUrl.orEmpty(),
                )
            }
        }
    }

    fun update(block: EditPlaylistUiState.() -> EditPlaylistUiState) { _state.value = _state.value.block().copy(error = null) }

    fun save() {
        val s = _state.value
        if (!s.isValid) return
        _state.value = s.copy(saving = true)
        viewModelScope.launch {
            val existing = if (s.id != 0L) repo.getById(s.id) else null
            val playlist = Playlist(
                id = s.id,
                name = s.name.trim(),
                type = s.type,
                url = s.url.trim().let { if (s.type == PlaylistType.XTREAM) it.trimEnd('/') else it },
                username = s.username.trim().ifBlank { null },
                password = s.password.ifBlank { null },
                epgUrl = s.epgUrl.trim().ifBlank { null },
                isActive = existing?.isActive ?: true,
                lastSyncedAt = existing?.lastSyncedAt,
            )
            val id = repo.save(playlist)
            if (existing == null) repo.setActive(id)
            scheduler.syncNow(id)
            _state.value = _state.value.copy(saving = false, saved = true)
        }
    }
}

@Composable
fun EditPlaylistScreen(onDone: () -> Unit, viewModel: EditPlaylistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.update { copy(url = uri.toString(), name = name.ifBlank { uri.lastPathSegment?.substringAfterLast('/') ?: "Local playlist" }) }
        }
    }
    val colors = fieldColors()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 24.dp),
    ) {
        Text(
            stringResource(if (state.id == 0L) R.string.playlist_add_title else R.string.playlist_edit_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(stringResource(R.string.playlist_type_m3u), state.type == PlaylistType.M3U) { viewModel.update { copy(type = PlaylistType.M3U) } }
            Pill(stringResource(R.string.playlist_type_xtream), state.type == PlaylistType.XTREAM) { viewModel.update { copy(type = PlaylistType.XTREAM) } }
        }
        Spacer(Modifier.height(20.dp))
        Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(state.name, { v -> viewModel.update { copy(name = v) } }, label = { Text(stringResource(R.string.playlist_name)) }, singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth())
            if (state.type == PlaylistType.M3U) {
                OutlinedTextField(state.url, { v -> viewModel.update { copy(url = v) } }, label = { Text(stringResource(R.string.playlist_url)) }, singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                GlowButton(stringResource(R.string.playlist_pick_file), { filePicker.launch(arrayOf("*/*")) }, icon = Icons.Filled.FolderOpen, primary = false)
                OutlinedTextField(state.epgUrl, { v -> viewModel.update { copy(epgUrl = v) } }, label = { Text(stringResource(R.string.playlist_epg_url)) }, singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            } else {
                OutlinedTextField(state.url, { v -> viewModel.update { copy(url = v) } }, label = { Text(stringResource(R.string.playlist_server)) }, placeholder = { Text("http://host:port") }, singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(state.username, { v -> viewModel.update { copy(username = v) } }, label = { Text(stringResource(R.string.playlist_username)) }, singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(state.password, { v -> viewModel.update { copy(password = v) } }, label = { Text(stringResource(R.string.playlist_password)) }, singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            }
        }
        if (state.error != null) {
            Spacer(Modifier.height(12.dp))
            Text(state.error.orEmpty(), color = Palette.Danger)
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.isValid && !state.saving) {
                GlowButton(stringResource(R.string.action_save), viewModel::save, icon = Icons.Filled.Check)
            }
            GlowButton(stringResource(R.string.action_cancel), onDone, primary = false)
        }
    }
    LaunchedEffect(state.saved) { if (state.saved) onDone() }
}

@Composable
private fun fieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Palette.NeonPurple,
    unfocusedBorderColor = Palette.Slate,
    focusedLabelColor = Palette.NeonPurple,
    unfocusedLabelColor = Palette.Muted,
    focusedContainerColor = Palette.SurfaceElevated,
    unfocusedContainerColor = Palette.Surface,
    cursorColor = Palette.NeonPurple,
)
