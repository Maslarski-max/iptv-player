package com.maslarski.iptv.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
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
import com.maslarski.iptv.domain.model.SyncStatus
import com.maslarski.iptv.domain.parental.ParentalGate
import com.maslarski.iptv.ui.components.Badge
import com.maslarski.iptv.ui.components.GlowButton
import com.maslarski.iptv.ui.playlists.DeletePlaylistDialog
import com.maslarski.iptv.ui.playlists.PlaylistRow
import com.maslarski.iptv.ui.components.Pill
import com.maslarski.iptv.ui.components.PinDialog
import com.maslarski.iptv.ui.components.SectionHeader
import com.maslarski.iptv.ui.components.dpadTextField
import com.maslarski.iptv.ui.components.focusGlow
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
import kotlinx.coroutines.launch
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

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val categories: List<Category> = emptyList(),
    val lockedIds: Set<String> = emptySet(),
    val playlists: List<Playlist> = emptyList(),
    val sync: SyncStatus = SyncStatus(),
    val license: LicenseState? = null,
)

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
    private val categories = playlists.activePlaylist.flatMapLatest { p ->
        if (p == null) flowOf(emptyList<Category>() to emptySet<String>())
        else combine(
            content.categories(p.id, ContentType.LIVE), content.categories(p.id, ContentType.MOVIE),
            content.categories(p.id, ContentType.SERIES), content.lockedCategoryIds(p.id),
        ) { l, m, s, locked -> (l + m + s) to locked }
    }

    val state: StateFlow<SettingsUiState> = combine(
        settingsRepo.settings, categories, playlists.playlists, syncer.status, license.state,
    ) { s, (cats, locked), lists, sync, lic ->
        SettingsUiState(s, cats, locked, lists, sync, lic)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

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
    fun setHardwareAcceleration(v: Boolean) = viewModelScope.launch { settingsRepo.setHardwareAcceleration(v) }
    fun setAspect(mode: AspectRatioMode) = viewModelScope.launch { settingsRepo.setAspectRatio(mode) }
    fun setPin(pin: String) = viewModelScope.launch { settingsRepo.setPin(pin) }
    fun setTmdbApiKey(key: String) = viewModelScope.launch { settingsRepo.setTmdbApiKey(key) }

    fun removePin(currentPin: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = settingsRepo.verifyPin(currentPin)
        if (ok) { settingsRepo.setPin(null); gate.lock() }
        onResult(ok)
    }
    fun toggleLock(category: Category) = viewModelScope.launch {
        content.setCategoryLocked(category, category.id !in state.value.lockedIds)
    }
    fun lockNow() = gate.lock()
}

private enum class PinMode { NONE, SET, CONFIRM_REMOVE }

@Composable
fun SettingsScreen(
    onAddPlaylist: () -> Unit,
    onEditPlaylist: (Long) -> Unit,
    onActivate: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pinMode by remember { mutableStateOf(PinMode.NONE) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<Playlist?>(null) }
    val wrongPin = stringResource(R.string.pin_wrong)

    pendingDelete?.let { p ->
        DeletePlaylistDialog(p, onConfirm = { viewModel.deletePlaylist(p.id); pendingDelete = null }, onDismiss = { pendingDelete = null })
    }

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

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 48.dp, vertical = 24.dp)) {
        // Title, section header and the first focusable card share one item so D-pad Up onto
        // "Activate premium" scrolls the headings back into view instead of stopping below them.
        item {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            SectionHeader(stringResource(R.string.settings_account))
            Spacer(Modifier.height(8.dp))
            state.license?.let { AccountCard(it, onActivate) }
            Spacer(Modifier.height(28.dp))
        }

        item { SectionHeader(stringResource(R.string.settings_language)); Spacer(Modifier.height(8.dp)) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SupportedLanguages.size) { i ->
                    val lang = SupportedLanguages[i]
                    Pill(if (lang.tag.isEmpty()) stringResource(R.string.settings_language_system) else lang.label, state.settings.languageTag == lang.tag) { viewModel.setLanguage(lang.tag) }
                }
            }
            Spacer(Modifier.height(28.dp))
        }

        item { SectionHeader(stringResource(R.string.settings_playback)); Spacer(Modifier.height(8.dp)) }
        item {
            Text(stringResource(R.string.player_aspect_ratio), style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AspectRatioMode.entries.size) { i ->
                    val mode = AspectRatioMode.entries[i]
                    Pill(stringResource(mode.label()), state.settings.aspectRatio == mode) { viewModel.setAspect(mode) }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        item {
            ToggleRow(stringResource(R.string.settings_hw_accel), stringResource(R.string.settings_hw_accel_body), state.settings.hardwareAcceleration, viewModel::setHardwareAcceleration)
            ToggleRow(stringResource(R.string.settings_launch_last), stringResource(R.string.settings_launch_last_body), state.settings.launchLastChannel, viewModel::setLaunchLastChannel)
            Spacer(Modifier.height(28.dp))
        }

        item { SectionHeader(stringResource(R.string.settings_playlists)); Spacer(Modifier.height(8.dp)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlowButton(stringResource(R.string.action_add_playlist), onAddPlaylist, icon = Icons.Filled.Add)
                if (state.playlists.isNotEmpty()) {
                    GlowButton(stringResource(R.string.settings_refresh_now), viewModel::refreshAll, icon = Icons.Filled.Refresh, primary = false)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        items(state.playlists, key = { "playlist:${it.id}" }) { p ->
            PlaylistRow(
                playlist = p,
                syncing = state.sync.isSyncing && state.sync.playlistId == p.id,
                syncMessage = state.sync.message,
                onActivate = { viewModel.activatePlaylist(p.id) },
                onEdit = { onEditPlaylist(p.id) },
                onRefresh = { viewModel.refresh(p) },
                onDelete = { pendingDelete = p },
            )
            Spacer(Modifier.height(12.dp))
        }
        item {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.settings_refresh_mode), style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(RefreshMode.entries.size) { i ->
                    val mode = RefreshMode.entries[i]
                    Pill(stringResource(mode.label()), state.settings.refreshMode == mode) { viewModel.setRefreshMode(mode) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.settings_refresh_mode_body), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            Spacer(Modifier.height(28.dp))
        }

        item { SectionHeader(stringResource(R.string.settings_metadata)); Spacer(Modifier.height(8.dp)) }
        item {
            TmdbKeyField(state.settings.tmdbApiKey, viewModel::setTmdbApiKey)
            Spacer(Modifier.height(28.dp))
        }

        item { SectionHeader(stringResource(R.string.settings_parental)); Spacer(Modifier.height(8.dp)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.settings.isParentalEnabled) {
                    GlowButton(stringResource(R.string.pin_change), { pinMode = PinMode.SET }, primary = false)
                    GlowButton(stringResource(R.string.pin_remove), { pinMode = PinMode.CONFIRM_REMOVE }, primary = false)
                    GlowButton(stringResource(R.string.pin_lock_now), viewModel::lockNow, primary = false)
                } else {
                    GlowButton(stringResource(R.string.pin_set), { pinMode = PinMode.SET })
                }
            }
            Spacer(Modifier.height(12.dp))
            if (state.settings.isParentalEnabled) {
                Text(stringResource(R.string.settings_locked_categories), style = MaterialTheme.typography.labelLarge, color = Palette.Muted)
                Spacer(Modifier.height(8.dp))
            }
        }
        if (state.settings.isParentalEnabled) {
            items(state.categories.size, key = { "${state.categories[it].type}:${state.categories[it].id}" }) { i ->
                val c = state.categories[i]
                ToggleRow(c.name, stringResource(c.type.label()), c.id in state.lockedIds) { viewModel.toggleLock(c) }
            }
        }
    }
}

@Composable
private fun AccountCard(license: LicenseState, onActivate: () -> Unit) {
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
            Badge(statusText, color = statusColor.copy(alpha = 0.2f), textColor = statusColor)
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
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, modifier = Modifier.weight(1f))
        Text(
            value,
            style = if (mono) MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
            else MaterialTheme.typography.titleMedium,
            color = if (mono) Palette.ElectricBlue else Palette.OnSurface,
        )
    }
}

@Composable
private fun TmdbKeyField(saved: String, onSave: (String) -> Unit) {
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
        GlowButton(stringResource(R.string.action_save), { onSave(draft) }, primary = false)
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
