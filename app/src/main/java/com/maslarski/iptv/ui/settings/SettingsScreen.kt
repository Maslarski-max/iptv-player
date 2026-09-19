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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.maslarski.iptv.R
import com.maslarski.iptv.data.repository.ContentRepository
import com.maslarski.iptv.data.repository.PlaylistRepository
import com.maslarski.iptv.data.settings.AppSettings
import com.maslarski.iptv.data.settings.AspectRatioMode
import com.maslarski.iptv.data.settings.SettingsRepository
import com.maslarski.iptv.domain.model.Category
import com.maslarski.iptv.domain.model.ContentType
import com.maslarski.iptv.domain.parental.ParentalGate
import com.maslarski.iptv.ui.components.GlowButton
import com.maslarski.iptv.ui.components.Pill
import com.maslarski.iptv.ui.components.PinDialog
import com.maslarski.iptv.ui.components.SectionHeader
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
)

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val categories: List<Category> = emptyList(),
    val lockedIds: Set<String> = emptySet(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    playlists: PlaylistRepository,
    private val content: ContentRepository,
    private val gate: ParentalGate,
) : ViewModel() {
    private val categories = playlists.activePlaylist.flatMapLatest { p ->
        if (p == null) flowOf(emptyList<Category>() to emptySet<String>())
        else combine(
            content.categories(p.id, ContentType.LIVE), content.categories(p.id, ContentType.MOVIE),
            content.categories(p.id, ContentType.SERIES), content.lockedCategoryIds(p.id),
        ) { l, m, s, locked -> (l + m + s) to locked }
    }

    val state: StateFlow<SettingsUiState> = combine(settingsRepo.settings, categories) { s, (cats, locked) ->
        SettingsUiState(s, cats, locked)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setLanguage(tag: String) {
        viewModelScope.launch {
            settingsRepo.setLanguage(tag)
            AppCompatDelegate.setApplicationLocales(if (tag.isBlank()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))
        }
    }
    fun setAutoUpdate(v: Boolean) = viewModelScope.launch { settingsRepo.setAutoUpdate(v) }
    fun setHardwareAcceleration(v: Boolean) = viewModelScope.launch { settingsRepo.setHardwareAcceleration(v) }
    fun setAspect(mode: AspectRatioMode) = viewModelScope.launch { settingsRepo.setAspectRatio(mode) }
    fun setPin(pin: String) = viewModelScope.launch { settingsRepo.setPin(pin) }

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
fun SettingsScreen(onManagePlaylists: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 48.dp, vertical = 24.dp)) {
        item { Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(16.dp)) }

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
            ToggleRow(stringResource(R.string.settings_auto_update), stringResource(R.string.settings_auto_update_body), state.settings.autoUpdateOnLaunch, viewModel::setAutoUpdate)
            Spacer(Modifier.height(28.dp))
        }

        item { SectionHeader(stringResource(R.string.settings_playlists)); Spacer(Modifier.height(8.dp)) }
        item {
            GlowButton(stringResource(R.string.playlists_manage), onManagePlaylists, primary = false)
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

fun ContentType.label(): Int = when (this) {
    ContentType.LIVE -> R.string.nav_live
    ContentType.MOVIE -> R.string.nav_movies
    ContentType.SERIES -> R.string.nav_series
}
