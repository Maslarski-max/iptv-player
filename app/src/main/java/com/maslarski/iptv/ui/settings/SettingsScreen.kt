package com.maslarski.iptv.ui.settings

import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.maslarski.iptv.BuildConfig
import com.maslarski.iptv.R
import com.maslarski.iptv.data.repository.ContentRepository
import com.maslarski.iptv.data.repository.PlaylistRepository
import com.maslarski.iptv.data.settings.AppSettings
import com.maslarski.iptv.data.settings.AspectRatioMode
import com.maslarski.iptv.data.settings.BufferSizeProfile
import com.maslarski.iptv.data.settings.DecoderMode
import com.maslarski.iptv.data.settings.RefreshMode
import com.maslarski.iptv.data.settings.SettingsRepository
import com.maslarski.iptv.data.sync.PlaylistSyncer
import com.maslarski.iptv.data.sync.SyncScheduler
import com.maslarski.iptv.domain.license.LicensePlan
import com.maslarski.iptv.domain.license.LicenseRepository
import com.maslarski.iptv.domain.license.LicenseState
import com.maslarski.iptv.domain.license.SubscriptionStatus
import com.maslarski.iptv.domain.model.Category
import com.maslarski.iptv.domain.model.ContentType
import com.maslarski.iptv.domain.model.Playlist
import com.maslarski.iptv.domain.model.PlaylistType
import com.maslarski.iptv.domain.model.SyncStatus
import com.maslarski.iptv.domain.parental.ParentalGate
import com.maslarski.iptv.ui.components.Badge
import com.maslarski.iptv.ui.components.GlowButton
import com.maslarski.iptv.ui.components.PinDialog
import com.maslarski.iptv.ui.components.SectionHeader
import com.maslarski.iptv.ui.components.dpadTextField
import com.maslarski.iptv.ui.components.focusGlow
import com.maslarski.iptv.ui.components.rememberFocusState
import com.maslarski.iptv.ui.components.rememberInteractionSource
import com.maslarski.iptv.ui.playlists.DeletePlaylistDialog
import com.maslarski.iptv.ui.playlists.PlaylistRow
import com.maslarski.iptv.ui.theme.Palette
import com.maslarski.iptv.util.DeviceInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

data class Language(val tag: String, val label: String)

val SupportedLanguages = listOf(
    Language("", "System"),
    Language("en", "English"),
    Language("es", "Español"),
    Language("fr", "Français"),
    Language("de", "Deutsch"),
    Language("it", "Italiano"),
    Language("ar", "العربية"),
    Language("tr", "Türkçe"),
    Language("mk", "Македонски"),
    Language("sr-Latn", "Srpski / Hrvatski"),
)

/** One card on the Settings hub; each opens its own back-stack entry so its UI is disposed on Back. */
private const val HUB_COLUMNS = 4
private val HUB_CARD_HEIGHT = 140.dp

enum class SettingsSection(val titleRes: Int, val icon: ImageVector) {
    ACCOUNT(R.string.settings_account, Icons.Filled.WorkspacePremium),
    LANGUAGE(R.string.settings_language, Icons.Filled.Language),
    PLAYBACK(R.string.settings_playback, Icons.Filled.PlayCircle),
    DECODER(R.string.settings_decoder_engine, Icons.Filled.Memory),
    BUFFER(R.string.settings_buffer_profile, Icons.Filled.Speed),
    PLAYLISTS(R.string.settings_playlists, Icons.AutoMirrored.Filled.PlaylistPlay),
    METADATA(R.string.settings_metadata, Icons.Filled.Image),
    PARENTAL(R.string.settings_parental, Icons.Filled.Lock),
}

// ---------------------------------------------------------------- Hub

data class SettingsHubUiState(
    val settings: AppSettings = AppSettings(),
    val activePlaylist: Playlist? = null,
    val license: LicenseState? = null,
)

@HiltViewModel
class SettingsHubViewModel @Inject constructor(
    settingsRepo: SettingsRepository,
    playlists: PlaylistRepository,
    license: LicenseRepository,
) : ViewModel() {
    val state: StateFlow<SettingsHubUiState> =
        combine(settingsRepo.settings, playlists.activePlaylist, license.state) { s, p, lic -> SettingsHubUiState(s, p, lic) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsHubUiState())
}

@Composable
fun SettingsScreen(onOpenSection: (SettingsSection) -> Unit, viewModel: SettingsHubViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val compact = LocalConfiguration.current.screenWidthDp < 600
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(10) {
            withFrameNanos {}
            if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = if (compact) 20.dp else 48.dp, vertical = if (compact) 24.dp else 20.dp)) {
        Text(
            stringResource(R.string.settings_title),
            style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(if (compact) 16.dp else 12.dp))
        LazyVerticalGrid(
            columns = if (compact) GridCells.Adaptive(minSize = 160.dp) else GridCells.Fixed(HUB_COLUMNS),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(SettingsSection.entries, key = { it.name }) { section ->
                SectionCard(
                    section = section,
                    summary = sectionSummary(section, state),
                    compact = compact,
                    modifier = if (section == SettingsSection.entries.first()) Modifier.focusRequester(firstFocus) else Modifier,
                ) { onOpenSection(section) }
            }
        }
    }
}

@Composable
private fun sectionSummary(section: SettingsSection, state: SettingsHubUiState): String {
    val s = state.settings
    return when (section) {
        SettingsSection.ACCOUNT -> state.license?.let { lic ->
            val status = when (lic.status) {
                SubscriptionStatus.ACTIVE -> stringResource(R.string.account_status_active)
                SubscriptionStatus.TRIAL -> stringResource(R.string.account_status_trial)
                SubscriptionStatus.EXPIRED -> stringResource(R.string.account_status_expired)
            }
            listOfNotNull(status, lic.plan?.let { stringResource(it.label()) }).joinToString("  ·  ")
        }.orEmpty()
        SettingsSection.LANGUAGE ->
            SupportedLanguages.firstOrNull { it.tag == s.languageTag }?.takeIf { it.tag.isNotEmpty() }?.label
                ?: stringResource(R.string.settings_language_system)
        SettingsSection.PLAYBACK -> stringResource(R.string.player_aspect_ratio) + ": " + stringResource(s.aspectRatio.label())
        SettingsSection.DECODER -> stringResource(s.videoDecoder.shortLabel()) + "  ·  " + stringResource(s.audioDecoder.shortLabel())
        SettingsSection.BUFFER -> stringResource(s.bufferProfile.label())
        SettingsSection.PLAYLISTS -> state.activePlaylist?.name ?: stringResource(R.string.settings_playlists_subtitle)
        SettingsSection.METADATA -> stringResource(
            when {
                s.tmdbApiKey.isNotBlank() -> R.string.settings_tmdb_custom
                BuildConfig.TMDB_API_KEY.isNotBlank() -> R.string.settings_tmdb_builtin
                else -> R.string.settings_tmdb_none
            },
        )
        SettingsSection.PARENTAL -> stringResource(if (s.isParentalEnabled) R.string.settings_parental_enabled else R.string.settings_parental_disabled)
    }
}

@Composable
private fun SectionCard(
    section: SettingsSection,
    summary: String,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = rememberInteractionSource()
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.fillMaxWidth().then(if (compact) Modifier.heightIn(min = 132.dp) else Modifier.height(HUB_CARD_HEIGHT))
            .focusGlow(interaction, shape, focusedScale = 1.02f, borderWidth = 2.dp, glowColor = Palette.ElectricBlue)
            .clip(shape).background(Palette.SurfaceElevated)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(if (compact) 20.dp else 14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(if (compact) 44.dp else 36.dp).clip(RoundedCornerShape(10.dp)).background(Palette.SurfaceHighest),
                contentAlignment = Alignment.Center,
            ) { Icon(section.icon, contentDescription = null, tint = Palette.ElectricBlue) }
            Spacer(Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Palette.Muted)
        }
        Spacer(Modifier.height(if (compact) 16.dp else 10.dp))
        Text(stringResource(section.titleRes), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(summary, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------- Sections

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val playlists: List<Playlist> = emptyList(),
    val sync: SyncStatus = SyncStatus(),
    val license: LicenseState? = null,
)

data class LockedCategories(val categories: List<Category> = emptyList(), val lockedIds: Set<String> = emptySet())

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val playlists: PlaylistRepository,
    private val content: ContentRepository,
    private val gate: ParentalGate,
    private val syncer: PlaylistSyncer,
    private val scheduler: SyncScheduler,
    license: LicenseRepository,
) : ViewModel() {
    val state: StateFlow<SettingsUiState> = combine(
        settingsRepo.settings, playlists.playlists, syncer.status, license.state,
    ) { s, lists, sync, lic -> SettingsUiState(s, lists, sync, lic) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /** Only the parental section collects this, so category lists are not held for the other sections. */
    val lockedCategories: StateFlow<LockedCategories> = playlists.activePlaylist.flatMapLatest { p ->
        if (p == null) flowOf(LockedCategories())
        else combine(
            content.categories(p.id, ContentType.LIVE), content.categories(p.id, ContentType.MOVIE),
            content.categories(p.id, ContentType.SERIES), content.lockedCategoryIds(p.id),
        ) { l, m, s, locked -> LockedCategories(l + m + s, locked) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LockedCategories())

    fun setLanguage(tag: String) {
        viewModelScope.launch {
            settingsRepo.setLanguage(tag)
            AppCompatDelegate.setApplicationLocales(if (tag.isBlank()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))
        }
    }
    fun setRefreshMode(mode: RefreshMode) = viewModelScope.launch {
        settingsRepo.setRefreshMode(mode)
        scheduler.applyRefreshMode(mode)
    }
    fun setLaunchLastChannel(v: Boolean) = viewModelScope.launch { settingsRepo.setLaunchLastChannel(v) }
    fun refreshAll() = viewModelScope.launch { state.value.playlists.forEach { syncer.sync(it) } }
    fun refresh(playlist: Playlist) = viewModelScope.launch { syncer.sync(playlist) }
    fun activatePlaylist(id: Long) = viewModelScope.launch { playlists.setActive(id) }
    fun deletePlaylist(id: Long) = viewModelScope.launch { playlists.delete(id) }
    fun setVideoDecoder(mode: DecoderMode) = viewModelScope.launch { settingsRepo.setVideoDecoder(mode) }
    fun setAudioDecoder(mode: DecoderMode) = viewModelScope.launch { settingsRepo.setAudioDecoder(mode) }
    fun setBufferProfile(profile: BufferSizeProfile) = viewModelScope.launch { settingsRepo.setBufferProfile(profile) }
    fun setAspect(mode: AspectRatioMode) = viewModelScope.launch { settingsRepo.setAspectRatio(mode) }
    fun setPin(pin: String) = viewModelScope.launch { settingsRepo.setPin(pin) }
    fun setTmdbApiKey(key: String) = viewModelScope.launch { settingsRepo.setTmdbApiKey(key) }

    fun removePin(currentPin: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = settingsRepo.verifyPin(currentPin)
        if (ok) { settingsRepo.setPin(null); gate.lock() }
        onResult(ok)
    }
    fun toggleLock(category: Category) = viewModelScope.launch {
        content.setCategoryLocked(category, category.id !in lockedCategories.value.lockedIds)
    }
    fun lockNow() = gate.lock()
}

private enum class PinMode { NONE, SET, CONFIRM_REMOVE }

@Composable
fun SettingsSectionScreen(
    section: SettingsSection,
    onAddPlaylist: () -> Unit,
    onEditPlaylist: (Long) -> Unit,
    onActivate: () -> Unit,
    onManageCategories: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locked = if (section == SettingsSection.PARENTAL) viewModel.lockedCategories.collectAsStateWithLifecycle().value else LockedCategories()
    val compact = LocalConfiguration.current.screenWidthDp < 600
    val firstFocus = remember { FocusRequester() }
    var pendingDelete by remember { mutableStateOf<Playlist?>(null) }
    pendingDelete?.let { p ->
        DeletePlaylistDialog(p, onConfirm = { viewModel.deletePlaylist(p.id); pendingDelete = null }, onDismiss = { pendingDelete = null })
    }
    LaunchedEffect(section) {
        repeat(10) {
            withFrameNanos {}
            if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = if (compact) 20.dp else 48.dp, vertical = 24.dp)) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(section.icon, contentDescription = null, tint = Palette.ElectricBlue, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(section.titleRes), style = MaterialTheme.typography.headlineMedium)
            }
            Text(stringResource(R.string.settings_back_hint), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            Spacer(Modifier.height(20.dp))
        }
        when (section) {
            SettingsSection.ACCOUNT -> item { state.license?.let { AccountCard(it, onActivate, firstFocus) } }
            SettingsSection.LANGUAGE -> item {
                OptionGrid(
                    options = SupportedLanguages,
                    isSelected = { state.settings.languageTag == it.tag },
                    label = { if (it.tag.isEmpty()) stringResource(R.string.settings_language_system) else it.label },
                    onSelect = { viewModel.setLanguage(it.tag) },
                    firstFocus = firstFocus,
                )
            }
            SettingsSection.PLAYBACK -> {
                item {
                    Text(stringResource(R.string.player_aspect_ratio), style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
                    Spacer(Modifier.height(8.dp))
                    OptionGrid(
                        options = AspectRatioMode.entries,
                        isSelected = { state.settings.aspectRatio == it },
                        label = { stringResource(it.label()) },
                        onSelect = viewModel::setAspect,
                        firstFocus = firstFocus,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                item {
                    ToggleRow(stringResource(R.string.settings_launch_last), stringResource(R.string.settings_launch_last_body), state.settings.launchLastChannel, viewModel::setLaunchLastChannel)
                }
            }
            SettingsSection.DECODER -> item {
                Text(stringResource(R.string.settings_decoder_engine_body), style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                Spacer(Modifier.height(12.dp))
                DecoderPicker(stringResource(R.string.settings_video_decoder), state.settings.videoDecoder, viewModel::setVideoDecoder, firstFocus)
                Spacer(Modifier.height(12.dp))
                DecoderPicker(stringResource(R.string.settings_audio_decoder), state.settings.audioDecoder, viewModel::setAudioDecoder)
            }
            SettingsSection.BUFFER -> item {
                Text(stringResource(R.string.settings_buffer_profile_body), style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                Spacer(Modifier.height(12.dp))
                OptionGrid(
                    options = BufferSizeProfile.entries,
                    isSelected = { state.settings.bufferProfile == it },
                    label = { stringResource(it.label()) },
                    onSelect = viewModel::setBufferProfile,
                    firstFocus = firstFocus,
                    columns = 4,
                )
            }
            SettingsSection.PLAYLISTS -> playlistsSection(state, viewModel, onAddPlaylist, onEditPlaylist, onManageCategories, { pendingDelete = it }, firstFocus)
            SettingsSection.METADATA -> item { TmdbKeyField(state.settings.tmdbApiKey, viewModel::setTmdbApiKey, firstFocus) }
            SettingsSection.PARENTAL -> parentalSection(state, locked, viewModel, firstFocus)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.playlistsSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onAddPlaylist: () -> Unit,
    onEditPlaylist: (Long) -> Unit,
    onManageCategories: () -> Unit,
    onDelete: (Playlist) -> Unit,
    firstFocus: FocusRequester,
) {
    item(key = "actions") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlowButton(stringResource(R.string.action_add_playlist), onAddPlaylist, icon = Icons.Filled.Add, modifier = Modifier.focusRequester(firstFocus))
            if (state.playlists.isNotEmpty()) {
                GlowButton(stringResource(R.string.settings_refresh_now), viewModel::refreshAll, icon = Icons.Filled.Refresh, primary = false)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (state.playlists.isNotEmpty()) {
            ActionRow(
                title = stringResource(R.string.manage_categories_title),
                body = stringResource(R.string.manage_categories_body),
                icon = Icons.AutoMirrored.Filled.Sort,
                onClick = onManageCategories,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
    items(state.playlists, key = { "playlist:${it.id}" }) { p ->
        PlaylistRow(
            playlist = p,
            syncing = state.sync.isSyncing && state.sync.playlistId == p.id,
            syncMessage = state.sync.message,
            onActivate = { viewModel.activatePlaylist(p.id) },
            onEdit = { onEditPlaylist(p.id) },
            onRefresh = { viewModel.refresh(p) },
            onDelete = { onDelete(p) },
        )
        Spacer(Modifier.height(12.dp))
    }
    item(key = "details") {
        Spacer(Modifier.height(8.dp))
        SectionHeader(stringResource(R.string.playlist_details_title), modifier = Modifier.padding(horizontal = 0.dp))
        val active = state.playlists.firstOrNull { it.isActive }
        if (active == null) {
            Text(stringResource(R.string.playlist_no_active), style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
        } else {
            PlaylistDetailsCard(active)
        }
        Spacer(Modifier.height(28.dp))
    }
    item {
        Text(stringResource(R.string.settings_refresh_mode), style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
        Spacer(Modifier.height(8.dp))
        OptionGrid(
            options = RefreshMode.entries,
            isSelected = { state.settings.refreshMode == it },
            label = { stringResource(it.label()) },
            onSelect = { viewModel.setRefreshMode(it) },
            columns = 4,
        )
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.settings_refresh_mode_body), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        Spacer(Modifier.height(28.dp))
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.parentalSection(
    state: SettingsUiState,
    locked: LockedCategories,
    viewModel: SettingsViewModel,
    firstFocus: FocusRequester,
) {
    item(key = "pin") {
        var pinMode by remember { mutableStateOf(PinMode.NONE) }
        var pinError by remember { mutableStateOf<String?>(null) }
        val wrongPin = stringResource(R.string.pin_wrong)
        when (pinMode) {
            PinMode.SET -> PinDialog(
                title = stringResource(R.string.pin_set),
                subtitle = stringResource(R.string.pin_set_hint),
                onSubmit = { viewModel.setPin(it); pinMode = PinMode.NONE },
                onDismiss = { pinMode = PinMode.NONE },
            )
            PinMode.CONFIRM_REMOVE -> PinDialog(
                title = stringResource(R.string.pin_enter),
                error = pinError,
                onSubmit = { pin ->
                    viewModel.removePin(pin) { ok ->
                        if (ok) { pinMode = PinMode.NONE; pinError = null } else pinError = wrongPin
                    }
                },
                onDismiss = { pinMode = PinMode.NONE; pinError = null },
            )
            PinMode.NONE -> Unit
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.settings.isParentalEnabled) {
                GlowButton(stringResource(R.string.pin_change), { pinMode = PinMode.SET }, primary = false, modifier = Modifier.focusRequester(firstFocus))
                GlowButton(stringResource(R.string.pin_remove), { pinMode = PinMode.CONFIRM_REMOVE }, primary = false)
                GlowButton(stringResource(R.string.pin_lock_now), viewModel::lockNow, primary = false)
            } else {
                GlowButton(stringResource(R.string.pin_set), { pinMode = PinMode.SET }, modifier = Modifier.focusRequester(firstFocus))
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    if (state.settings.isParentalEnabled) {
        item(key = "locked-header") {
            Text(stringResource(R.string.settings_locked_categories), style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
            Spacer(Modifier.height(8.dp))
        }
        items(locked.categories, key = { "${it.type}:${it.id}" }) { c ->
            ToggleRow(c.name, stringResource(c.type.label()), c.id in locked.lockedIds) { viewModel.toggleLock(c) }
        }
    }
}

@Composable
private fun AccountCard(license: LicenseState, onActivate: () -> Unit, focus: FocusRequester) {
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.LONG) }
    val (statusText, statusColor) = when (license.status) {
        SubscriptionStatus.ACTIVE -> stringResource(R.string.account_status_active) to Palette.Success
        SubscriptionStatus.TRIAL -> stringResource(R.string.account_status_trial) to Palette.Gold
        SubscriptionStatus.EXPIRED -> stringResource(R.string.account_status_expired) to Palette.Danger
    }
    val days = license.daysLeft()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.SurfaceElevated).padding(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.account_status), style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
                Text(statusText, style = MaterialTheme.typography.headlineSmall, color = statusColor)
                license.plan?.let { Text(stringResource(it.label()), style = MaterialTheme.typography.bodyMedium, color = Palette.Muted) }
            }
            if (LocalConfiguration.current.screenWidthDp >= 600) {
                Spacer(Modifier.width(16.dp))
                Badge(statusText, color = statusColor.copy(alpha = 0.2f), textColor = statusColor)
            }
        }
        Spacer(Modifier.height(16.dp))
        when {
            license.isLifetime -> InfoRow(stringResource(R.string.account_expiry), stringResource(R.string.account_lifetime))
            license.expiresAt != null -> {
                InfoRow(
                    stringResource(R.string.account_days_left),
                    if (license.status == SubscriptionStatus.EXPIRED) stringResource(R.string.account_expired_on)
                    else pluralStringResource(R.plurals.account_days_left_value, days ?: 0, days ?: 0),
                )
                InfoRow(stringResource(R.string.account_expiry), dateFormat.format(Date(license.expiresAt)))
            }
        }
        InfoRow(stringResource(R.string.account_device_id), license.deviceId, mono = true)
        val mac by produceState<String?>(null) { value = withContext(Dispatchers.IO) { DeviceInfo.macAddress() } }
        InfoRow(
            stringResource(R.string.account_mac_address),
            mac ?: stringResource(R.string.account_mac_unavailable),
            mono = mac != null,
        )
        InfoRow(
            stringResource(R.string.account_verification),
            stringResource(if (license.remote) R.string.account_verified_server else R.string.account_verified_local),
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlowButton(
                stringResource(if (license.isLifetime) R.string.account_manage else R.string.account_activate),
                onActivate,
                icon = Icons.Filled.WorkspacePremium,
                primary = !license.isLifetime,
                modifier = Modifier.focusRequester(focus),
            )
        }
    }
}

/** Host, stream format, EPG sync and (for Xtream lines) status / expiry of the active playlist. */
@Composable
private fun PlaylistDetailsCard(playlist: Playlist) {
    val dateTime = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val dateOnly = remember { DateFormat.getDateInstance(DateFormat.LONG) }
    val isXtream = playlist.type == PlaylistType.XTREAM
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.SurfaceElevated).padding(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(playlist.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Badge(
                stringResource(if (isXtream) R.string.playlist_type_xtream else R.string.playlist_type_m3u),
                color = Palette.NeonPurple.copy(alpha = 0.2f),
                textColor = Palette.NeonPurple,
            )
        }
        Spacer(Modifier.height(12.dp))
        InfoRow(stringResource(R.string.playlist_host), playlist.hostLabel(), mono = true)
        InfoRow(stringResource(R.string.playlist_stream_format), playlist.streamFormatLabel())
        InfoRow(
            stringResource(R.string.playlist_epg_status),
            when {
                playlist.epgSyncedAt != null -> stringResource(R.string.playlist_last_synced, dateTime.format(Date(playlist.epgSyncedAt)))
                !isXtream && playlist.epgUrl.isNullOrBlank() -> stringResource(R.string.playlist_epg_not_configured)
                else -> stringResource(R.string.playlist_never_synced)
            },
        )
        if (isXtream) {
            val status = playlist.accountStatus
            InfoRow(
                stringResource(R.string.playlist_line_status),
                status ?: stringResource(R.string.playlist_not_provided),
                valueColor = when {
                    status == null -> Palette.OnSurface
                    status.equals("Active", ignoreCase = true) -> Palette.Success
                    else -> Palette.Danger
                },
            )
            val expires = playlist.accountExpiresAt
            InfoRow(
                stringResource(R.string.account_expiry),
                when {
                    expires != null -> dateOnly.format(Date(expires))
                    status != null -> stringResource(R.string.playlist_expiry_unlimited)
                    else -> stringResource(R.string.playlist_not_provided)
                },
                valueColor = if (expires != null && expires < System.currentTimeMillis()) Palette.Danger else Palette.OnSurface,
            )
            InfoRow(
                stringResource(R.string.playlist_max_connections),
                playlist.maxConnections?.toString() ?: stringResource(R.string.playlist_not_provided),
            )
        }
    }
}

@Composable
private fun Playlist.hostLabel(): String {
    if (url.startsWith("content://") || url.startsWith("file://")) return stringResource(R.string.playlist_format_local_file)
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
    val host = uri.host ?: return url
    val port = uri.port.takeIf { it > 0 }?.let { ":$it" }.orEmpty()
    return "${uri.scheme ?: "http"}://$host$port"
}

@Composable
private fun Playlist.streamFormatLabel(): String {
    if (type == PlaylistType.XTREAM) {
        return outputFormats.takeIf { it.isNotEmpty() }?.joinToString(" / ") { formatName(it) }
            ?: stringResource(R.string.playlist_not_provided)
    }
    if (url.startsWith("content://") || url.startsWith("file://")) return stringResource(R.string.playlist_format_local_file)
    val lower = url.lowercase()
    return when {
        lower.contains(".m3u8") -> "HLS (M3U8)"
        lower.contains(".m3u") -> "M3U"
        lower.contains(".ts") -> "MPEG-TS"
        else -> "M3U"
    }
}

private fun formatName(raw: String): String = when (raw.lowercase()) {
    "m3u8", "hls" -> "HLS (M3U8)"
    "ts" -> "MPEG-TS"
    "rtmp" -> "RTMP"
    else -> raw.uppercase()
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false, valueColor: Color? = null) {
    val valueStyle = if (mono) MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
    else MaterialTheme.typography.titleMedium
    val color = valueColor ?: if (mono) Palette.ElectricBlue else Palette.OnSurface
    if (LocalConfiguration.current.screenWidthDp < 600) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
            Text(value, style = valueStyle, color = color)
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            Text(value, style = valueStyle, color = color, textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun TmdbKeyField(saved: String, onSave: (String) -> Unit, focus: FocusRequester) {
    var draft by remember(saved) { mutableStateOf(saved) }
    val hasBuiltIn = BuildConfig.TMDB_API_KEY.isNotBlank()
    Column {
        Text(
            stringResource(if (hasBuiltIn) R.string.settings_tmdb_body_builtin else R.string.settings_tmdb_body),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Muted,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth().dpadTextField(),
            singleLine = true,
            label = { Text(stringResource(R.string.settings_tmdb_key)) },
            visualTransformation = PasswordVisualTransformation(),
        )
        Spacer(Modifier.height(8.dp))
        GlowButton(stringResource(R.string.action_save), { onSave(draft) }, primary = false, modifier = Modifier.focusRequester(focus))
    }
}

@Composable
private fun ActionRow(title: String, body: String, icon: ImageVector, onClick: () -> Unit) {
    val interaction = rememberInteractionSource()
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .focusGlow(interaction, shape, focusedScale = 1.0f, borderWidth = 2.dp, glowColor = Palette.ElectricBlue)
            .clip(shape).background(Palette.Surface)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Palette.ElectricBlue)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
        Spacer(Modifier.width(16.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Palette.Muted)
    }
}

@Composable
private fun DecoderPicker(title: String, selected: DecoderMode, onSelect: (DecoderMode) -> Unit, firstFocus: FocusRequester? = null) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
    Spacer(Modifier.height(8.dp))
    OptionGrid(
        options = DecoderMode.entries,
        isSelected = { selected == it },
        label = {
            stringResource(
                when (it) {
                    DecoderMode.HARDWARE -> R.string.settings_decoder_hardware
                    DecoderMode.SOFTWARE -> R.string.settings_decoder_software
                },
            )
        },
        onSelect = onSelect,
        firstFocus = firstFocus,
        columns = 4,
    )
}

/**
 * Options laid out in rows of [columns] equal-width cells (2 on phones) so the D-pad moves in both
 * directions instead of along one long horizontal lane. Trailing empty cells keep widths uniform.
 */
@Composable
private fun <T> OptionGrid(
    options: List<T>,
    isSelected: (T) -> Boolean,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    firstFocus: FocusRequester? = null,
    columns: Int = 5,
) {
    val cols = if (LocalConfiguration.current.screenWidthDp < 600) 2 else columns
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.chunked(cols).forEachIndexed { rowIndex, row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { i, option ->
                    val focusModifier = if (rowIndex == 0 && i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier
                    OptionCell(label(option), isSelected(option), modifier = Modifier.weight(1f).fillMaxHeight().then(focusModifier)) { onSelect(option) }
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun OptionCell(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = rememberInteractionSource()
    val focused by rememberFocusState(interaction)
    val shape = RoundedCornerShape(12.dp)
    val bg = when {
        focused -> Palette.NeonPurple
        selected -> Palette.SurfaceHighest
        else -> Palette.Surface
    }
    Row(
        modifier
            .focusGlow(interaction, shape, focusedScale = 1.03f, borderWidth = 2.dp, glowColor = Palette.ElectricBlue)
            .clip(shape)
            .background(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = if (focused) Color.White else Palette.ElectricBlue, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ToggleRow(title: String, body: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val interaction = rememberInteractionSource()
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .focusGlow(interaction, shape, focusedScale = 1.0f, borderWidth = 2.dp, glowColor = Palette.ElectricBlue)
            .clip(shape).background(Palette.Surface)
            .clickable(interactionSource = interaction, indication = null) { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (body != null) Text(body, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.NeonPurple, checkedThumbColor = Color.White),
        )
    }
}

fun AspectRatioMode.label(): Int = when (this) {
    AspectRatioMode.FIT -> R.string.aspect_fit
    AspectRatioMode.RATIO_16_9 -> R.string.aspect_16_9
    AspectRatioMode.RATIO_4_3 -> R.string.aspect_4_3
    AspectRatioMode.ZOOM -> R.string.aspect_zoom
    AspectRatioMode.STRETCH -> R.string.aspect_stretch
}

fun BufferSizeProfile.label(): Int = when (this) {
    BufferSizeProfile.SMALL -> R.string.settings_buffer_small
    BufferSizeProfile.MEDIUM -> R.string.settings_buffer_medium
    BufferSizeProfile.LARGE -> R.string.settings_buffer_large
    BufferSizeProfile.EXTRA_LARGE -> R.string.settings_buffer_extra_large
}

fun DecoderMode.shortLabel(): Int = when (this) {
    DecoderMode.HARDWARE -> R.string.settings_decoder_hw_short
    DecoderMode.SOFTWARE -> R.string.settings_decoder_sw_short
}

fun RefreshMode.label(): Int = when (this) {
    RefreshMode.MANUAL -> R.string.refresh_manual
    RefreshMode.ON_LAUNCH -> R.string.refresh_on_launch
    RefreshMode.EVERY_12H -> R.string.refresh_every_12h
    RefreshMode.EVERY_24H -> R.string.refresh_every_24h
}

fun LicensePlan.label(): Int = when (this) {
    LicensePlan.LIFETIME -> R.string.plan_lifetime
    LicensePlan.MONTHLY -> R.string.plan_monthly
    LicensePlan.QUARTERLY -> R.string.plan_quarterly
    LicensePlan.HALF_YEAR -> R.string.plan_half_year
    LicensePlan.YEARLY -> R.string.plan_yearly
}

fun ContentType.label(): Int = when (this) {
    ContentType.LIVE -> R.string.nav_live
    ContentType.MOVIE -> R.string.nav_movies
    ContentType.SERIES -> R.string.nav_series
}
