package com.maslarski.iptv.ui.player

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.maslarski.iptv.data.repository.ContentRepository
import com.maslarski.iptv.di.PlayerModule
import com.maslarski.iptv.data.repository.PlaylistRepository
import com.maslarski.iptv.data.settings.AspectRatioMode
import com.maslarski.iptv.data.settings.LastChannel
import com.maslarski.iptv.data.settings.SettingsRepository
import com.maslarski.iptv.data.local.entity.ReminderEntity
import com.maslarski.iptv.domain.model.Category
import com.maslarski.iptv.domain.model.Channel
import com.maslarski.iptv.domain.model.ContentType
import com.maslarski.iptv.domain.model.EpgProgram
import com.maslarski.iptv.domain.model.Episode
import com.maslarski.iptv.domain.parental.ParentalGate
import com.maslarski.iptv.domain.reminder.ReminderManager
import com.maslarski.iptv.ui.live.reminderKey
import com.maslarski.iptv.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.math.abs
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

data class TrackOption(val id: Int, val type: Int, val label: String, val selected: Boolean)

data class PlayerUiState(
    val title: String = "",
    val subtitle: String? = null,
    val contentType: ContentType = ContentType.LIVE,
    val isLive: Boolean = false,
    val isPlaying: Boolean = false,
    /** User intent: false only after an explicit pause, never during stalls or rebuffering. */
    val isPaused: Boolean = false,
    val isBuffering: Boolean = true,
    val positionMillis: Long = 0L,
    val durationMillis: Long = 0L,
    val error: String? = null,
    val reconnectAttempt: Int = 0,
    val aspect: AspectRatioMode = AspectRatioMode.FIT,
    val audioTracks: List<TrackOption> = emptyList(),
    val subtitleTracks: List<TrackOption> = emptyList(),
    val subtitlesEnabled: Boolean = true,
    val isMuted: Boolean = false,
    val nowPlaying: EpgProgram? = null,
    val nextProgram: EpgProgram? = null,
    val channelNumber: Int? = null,
    val hasNextEpisode: Boolean = false,
    val channels: List<Channel> = emptyList(),
    val currentChannelId: String? = null,
    val favoritesOnly: Boolean = false,
)

/** Category / channel / EPG browser state shown as the translucent zapping overlay over a live stream. */
data class LiveGuideState(
    val categories: List<Category> = emptyList(),
    val selectedCategoryId: String? = null,
    val lockedCategoryIds: Set<String> = emptySet(),
    val channels: List<Channel> = emptyList(),
    val nowPlaying: Map<String, EpgProgram> = emptyMap(),
    val reminders: Map<String, ReminderEntity> = emptyMap(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedState: SavedStateHandle,
    private val content: ContentRepository,
    private val playlists: PlaylistRepository,
    private val settings: SettingsRepository,
    private val gate: ParentalGate,
    private val reminders: ReminderManager,
    private val libVlc: LibVLC,
) : ViewModel() {

    private val route = savedState.toRoute<Route.Player>()
    private val _state = MutableStateFlow(
        PlayerUiState(contentType = ContentType.valueOf(route.contentType), favoritesOnly = route.favoritesOnly),
    )
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    val player: MediaPlayer = MediaPlayer(libVlc)

    private val vlcExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "vlc-control") }
    private val vlcThread = vlcExecutor.asCoroutineDispatcher()
    private val prepareGeneration = AtomicInteger()

    /** Last URL + options handed to VLC, replayed verbatim on reconnect. */
    private var currentUrl: String? = null
    private var currentIsLive = false

    private var channelList: List<Channel> = emptyList()
    private var channelIndex = -1

    // ------------------------------------------------------------ live guide

    private val guideCategory = MutableStateFlow(route.categoryId)

    private val lockedCategories: Flow<Set<String>> =
        combine(content.lockedCategoryIds(route.playlistId), gate.enforcing) { ids, enforce -> if (enforce) ids else emptySet() }

    private val guideChannels: Flow<List<Channel>> = combine(guideCategory, lockedCategories) { cat, locked -> cat to locked }
        .flatMapLatest { (cat, locked) ->
            when {
                cat != null && cat in locked -> flowOf(emptyList())
                cat == null && route.favoritesOnly -> content.favoriteChannels(route.playlistId)
                cat == null -> content.channels(route.playlistId, null).map { list -> list.filter { it.categoryId !in locked } }
                else -> content.channels(route.playlistId, cat)
            }
        }

    val guide: StateFlow<LiveGuideState> = combine(
        content.categories(route.playlistId, ContentType.LIVE),
        guideCategory,
        lockedCategories,
        guideChannels,
        guideChannels.flatMapLatest { list -> content.nowPlaying(list.mapNotNull { it.epgChannelId }.take(400)) },
    ) { cats, cat, locked, channels, epg ->
        LiveGuideState(categories = cats.filter { it.id !in locked }, selectedCategoryId = cat, lockedCategoryIds = locked, channels = channels, nowPlaying = epg)
    }.let { base ->
        combine(base, reminders.upcoming) { s, upcoming -> s.copy(reminders = upcoming.associateBy { reminderKey(it.epgChannelId, it.startMillis) }) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveGuideState())

    fun selectGuideCategory(id: String?) { guideCategory.value = id }

    fun programsFor(epgChannelId: String): Flow<List<EpgProgram>> {
        val now = System.currentTimeMillis()
        return content.programsForChannel(epgChannelId, now - 60 * 60 * 1000L, now + 12 * 60 * 60 * 1000L)
    }

    /** Zaps to a channel picked in the overlay; Channel +/- then cycles within that channel's list. */
    fun playFromGuide(channel: Channel) {
        val list = guide.value.channels
        val index = list.indexOfFirst { it.id == channel.id }
        if (index >= 0) {
            channelList = list
            channelIndex = index
            _state.update { it.copy(channels = list) }
        }
        playChannel(channel)
    }

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch { content.toggleFavorite(channel.playlistId, channel.id, ContentType.LIVE) }
    }

    fun setReminder(channel: Channel, program: EpgProgram, autoSwitch: Boolean) {
        viewModelScope.launch { reminders.set(channel, program, autoSwitch) }
    }

    fun removeReminder(id: Long) {
        viewModelScope.launch { reminders.remove(id) }
    }
    private var currentEpisode: Episode? = null
    private var currentContentId: String = route.contentId
    private var reconnectJob: Job? = null
    private var progressJob: Job? = null
    private var epgJob: Job? = null
    private var lastSavedPosition = 0L

    private val listener = MediaPlayer.EventListener { event ->
        when (event.type) {
            MediaPlayer.Event.Playing -> _state.update {
                it.copy(isPlaying = true, isBuffering = false, error = null, reconnectAttempt = 0)
            }
            MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> _state.update { it.copy(isPlaying = false) }
            // VLC keeps reporting cache fill while playing; only a partial fill means the stream is actually stalled.
            MediaPlayer.Event.Buffering -> _state.update { it.copy(isBuffering = event.buffering < 100f) }
            MediaPlayer.Event.EncounteredError -> {
                _state.update { it.copy(error = "PLAYBACK_ERROR", isBuffering = false, isPlaying = false) }
                scheduleReconnect()
            }
            MediaPlayer.Event.EndReached -> if (_state.value.isLive) scheduleReconnect() else onEnded()
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> refreshTracks()
            MediaPlayer.Event.LengthChanged -> _state.update { it.copy(durationMillis = event.lengthChanged.coerceAtLeast(0L)) }
        }
    }

    init {
        player.setEventListener(listener)
        viewModelScope.launch {
            _state.update { it.copy(aspect = settings.current().aspectRatio) }
            when (route.contentType) {
                ContentType.LIVE.name -> startLive()
                ContentType.MOVIE.name -> startMovie()
                else -> startEpisode(route.contentId)
            }
        }
        progressJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                val duration = player.length.coerceAtLeast(0L)
                val position = player.time.coerceAtLeast(0L)
                _state.update { it.copy(positionMillis = position, durationMillis = duration) }
                if (!_state.value.isLive && duration > 0 && abs(position - lastSavedPosition) > 5_000) {
                    persistProgress()
                }
            }
        }
    }

    // ------------------------------------------------------------------ start

    private suspend fun startLive() {
        val source = if (route.favoritesOnly) content.favoriteChannels(route.playlistId)
        else content.channels(route.playlistId, route.categoryId)
        val channels = source.first()
        channelList = channels
        channelIndex = channels.indexOfFirst { it.id == route.contentId }
        _state.update { it.copy(channels = channels) }
        val channel = channels.getOrNull(channelIndex) ?: content.channel(route.playlistId, route.contentId) ?: return
        playChannel(channel)
    }

    fun playChannelById(id: String) {
        val index = channelList.indexOfFirst { it.id == id }
        if (index < 0) return
        channelIndex = index
        playChannel(channelList[index])
    }

    private fun playChannel(channel: Channel) {
        currentContentId = channel.id
        _state.update {
            it.copy(
                title = channel.name, subtitle = channel.categoryName, isLive = true,
                channelNumber = channel.channelNumber, nowPlaying = null, nextProgram = null, error = null,
                currentChannelId = channel.id,
            )
        }
        prepare(channel.streamUrl, live = true)
        viewModelScope.launch {
            settings.setLastChannel(LastChannel(route.playlistId, channel.id, route.categoryId, route.favoritesOnly))
        }
        epgJob?.cancel()
        val epgId = channel.epgChannelId ?: return
        epgJob = viewModelScope.launch {
            val now = System.currentTimeMillis()
            content.programsForChannel(epgId, now - 6 * 3_600_000L, now + 12 * 3_600_000L).collect { programs ->
                val current = programs.firstOrNull { it.isLiveAt(System.currentTimeMillis()) }
                val next = programs.firstOrNull { it.startMillis > System.currentTimeMillis() }
                _state.update { it.copy(nowPlaying = current, nextProgram = next) }
            }
        }
    }

    private suspend fun startMovie() {
        val movie = content.movie(route.playlistId, route.contentId).first() ?: return
        _state.update { it.copy(title = movie.title, subtitle = movie.releaseYear, isLive = false) }
        val resume = movie.progress?.takeIf { !it.isFinished }?.positionMillis ?: 0L
        prepare(movie.streamUrl, live = false, startPosition = resume)
    }

    private suspend fun startEpisode(id: String) {
        val episode = content.episode(route.playlistId, id) ?: return
        currentEpisode = episode
        currentContentId = episode.id
        val series = content.seriesById(route.playlistId, episode.seriesId).first()
        val progress = content.progress(route.playlistId, episode.id, ContentType.SERIES)
        val next = content.nextEpisode(route.playlistId, episode)
        _state.update {
            it.copy(
                title = series?.title ?: episode.title,
                subtitle = "S${episode.seasonNumber} E${episode.episodeNumber} · ${episode.title}",
                isLive = false, hasNextEpisode = next != null,
            )
        }
        val resume = progress?.takeIf { !it.isFinished }?.positionMillis ?: 0L
        prepare(episode.streamUrl, live = false, startPosition = resume)
    }

    /**
     * `MediaPlayer.stop()` joins the VLC input thread, which can block for seconds on a
     * stalled network, so every stop/media switch runs on [vlcThread] and never on main.
     */
    private fun prepare(url: String, live: Boolean, startPosition: Long = 0L) {
        reconnectJob?.cancel()
        currentUrl = url
        currentIsLive = live
        val generation = prepareGeneration.incrementAndGet()
        _state.update { it.copy(isBuffering = true, isPaused = false, audioTracks = emptyList(), subtitleTracks = emptyList()) }
        viewModelScope.launch(vlcThread) {
            player.stop()
            if (generation != prepareGeneration.get()) return@launch
            val media = Media(libVlc, Uri.parse(url)).apply {
                setHWDecoderEnabled(true, false)
                addOption(":network-caching=$NETWORK_CACHING_MS")
                if (live) addOption(":live-caching=$NETWORK_CACHING_MS")
                if (startPosition > 0L) addOption(":start-time=${startPosition / 1000}")
            }
            player.media = media
            media.release()
            player.play()
        }
    }

    /** Adds an external .srt/.vtt sidecar (picked by the user) to the current item and selects it. */
    fun addExternalSubtitle(uri: Uri) {
        player.addSlave(IMedia.Slave.Type.Subtitle, uri, true)
        _state.update { it.copy(subtitlesEnabled = true) }
    }

    /** Binds / unbinds the Compose-hosted surface; VLC starts video output as soon as a surface is attached. */
    fun attachVideo(layout: VLCVideoLayout) {
        if (!player.vlcVout.areViewsAttached()) player.attachViews(layout, null, true, false)
        applyScale(_state.value.aspect)
    }

    fun detachVideo() { player.detachViews() }

    // -------------------------------------------------------------- reconnect

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            val attempt = _state.value.reconnectAttempt + 1
            if (attempt > MAX_RECONNECTS) return@launch
            _state.update { it.copy(reconnectAttempt = attempt) }
            delay((1_000L * attempt).coerceAtMost(8_000L))
            val url = currentUrl ?: return@launch
            val resume = if (currentIsLive) 0L else player.time.coerceAtLeast(0L)
            prepare(url, currentIsLive, resume)
            _state.update { it.copy(reconnectAttempt = attempt) }
        }
    }

    // ------------------------------------------------------------------ tracks

    private fun refreshTracks() {
        fun options(type: Int, descriptions: Array<MediaPlayer.TrackDescription>?, selectedId: Int): List<TrackOption> =
            descriptions.orEmpty().filter { it.id >= 0 }.mapIndexed { i, d ->
                TrackOption(d.id, type, d.name?.takeIf { it.isNotBlank() } ?: "Track ${i + 1}", d.id == selectedId)
            }
        val spuId = player.spuTrack
        _state.update {
            it.copy(
                audioTracks = options(IMedia.Track.Type.Audio, player.audioTracks, player.audioTrack),
                subtitleTracks = options(IMedia.Track.Type.Text, player.spuTracks, spuId),
                subtitlesEnabled = spuId >= 0,
            )
        }
    }

    fun selectTrack(option: TrackOption) {
        when (option.type) {
            IMedia.Track.Type.Audio -> player.setAudioTrack(option.id)
            IMedia.Track.Type.Text -> player.setSpuTrack(option.id)
        }
        refreshTracks()
    }

    fun disableSubtitles() {
        player.setSpuTrack(-1)
        _state.update { it.copy(subtitlesEnabled = false) }
        refreshTracks()
    }

    // ---------------------------------------------------------------- controls

    fun togglePlayPause() { if (_state.value.isPaused) play() else pause() }

    fun play() {
        _state.update { it.copy(isPaused = false) }
        player.play()
    }

    fun pause() {
        _state.update { it.copy(isPaused = true) }
        player.pause()
    }

    fun stop() {
        reconnectJob?.cancel()
        prepareGeneration.incrementAndGet()
        _state.update { it.copy(isPaused = true, isPlaying = false, isBuffering = false) }
        viewModelScope.launch(vlcThread) { player.stop() }
    }

    fun toggleMute() {
        val muted = player.volume == 0
        player.volume = if (muted) 100 else 0
        _state.update { it.copy(isMuted = !muted) }
    }

    fun seekForward() = seekBy(SEEK_FORWARD_MS)
    fun seekBack() = seekBy(-SEEK_BACK_MS)

    private fun seekBy(deltaMs: Long) {
        if (_state.value.isLive || !player.isSeekable) return
        val length = player.length.coerceAtLeast(0L)
        val target = (player.time + deltaMs).coerceIn(0L, if (length > 0) length else Long.MAX_VALUE)
        player.time = target
        _state.update { it.copy(positionMillis = target) }
    }

    fun seekTo(fraction: Float) {
        if (!player.isSeekable || player.length <= 0L) return
        player.position = fraction.coerceIn(0f, 1f)
    }

    fun channelUp() = switchChannel(+1)
    fun channelDown() = switchChannel(-1)

    private fun switchChannel(delta: Int) {
        if (channelList.isEmpty()) return
        channelIndex = ((channelIndex + delta) % channelList.size + channelList.size) % channelList.size
        playChannel(channelList[channelIndex])
    }

    fun cycleAspect() {
        val entries = AspectRatioMode.entries
        setAspect(entries[(entries.indexOf(_state.value.aspect) + 1) % entries.size])
    }

    fun setAspect(mode: AspectRatioMode) {
        _state.update { it.copy(aspect = mode) }
        applyScale(mode)
        viewModelScope.launch { settings.setAspectRatio(mode) }
    }

    private fun applyScale(mode: AspectRatioMode) {
        player.videoScale = when (mode) {
            AspectRatioMode.FIT -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
            AspectRatioMode.RATIO_16_9 -> MediaPlayer.ScaleType.SURFACE_16_9
            AspectRatioMode.RATIO_4_3 -> MediaPlayer.ScaleType.SURFACE_4_3
            AspectRatioMode.ZOOM -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            AspectRatioMode.STRETCH -> MediaPlayer.ScaleType.SURFACE_FILL
        }
    }

    fun playNextEpisode() {
        val ep = currentEpisode ?: return
        viewModelScope.launch {
            persistProgress()
            val next = content.nextEpisode(route.playlistId, ep) ?: return@launch
            startEpisode(next.id)
        }
    }

    private fun onEnded() {
        viewModelScope.launch {
            persistProgress(finished = true)
            if (_state.value.hasNextEpisode) playNextEpisode()
        }
    }

    private suspend fun persistProgress(finished: Boolean = false) {
        val s = _state.value
        if (s.isLive) return
        val duration = player.length.takeIf { it > 0L } ?: return
        val position = if (finished) duration else player.time.coerceAtLeast(0L)
        lastSavedPosition = position
        content.saveProgress(
            playlistId = route.playlistId,
            id = currentContentId,
            type = s.contentType,
            position = position,
            duration = duration,
            seriesId = currentEpisode?.seriesId,
        )
    }

    fun onLeave() { viewModelScope.launch { persistProgress() } }

    override fun onCleared() {
        reconnectJob?.cancel()
        progressJob?.cancel()
        player.setEventListener(null)
        player.detachViews()
        vlcExecutor.execute {
            player.stop()
            player.release()
        }
        vlcExecutor.shutdown()
        super.onCleared()
    }

    companion object { const val MAX_RECONNECTS = 8 }
}

private const val NETWORK_CACHING_MS = PlayerModule.NETWORK_CACHING_MS
private const val SEEK_BACK_MS = 10_000L
private const val SEEK_FORWARD_MS = 30_000L
