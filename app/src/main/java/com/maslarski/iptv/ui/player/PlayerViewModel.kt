package com.maslarski.iptv.ui.player

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.navigation.toRoute
import com.maslarski.iptv.data.repository.ContentRepository
import com.maslarski.iptv.data.repository.PlaylistRepository
import com.maslarski.iptv.data.settings.AspectRatioMode
import com.maslarski.iptv.data.settings.SettingsRepository
import com.maslarski.iptv.domain.model.Channel
import com.maslarski.iptv.domain.model.ContentType
import com.maslarski.iptv.domain.model.EpgProgram
import com.maslarski.iptv.domain.model.Episode
import com.maslarski.iptv.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs

data class TrackOption(val group: TrackGroup, val index: Int, val label: String, val selected: Boolean)

data class PlayerUiState(
    val title: String = "",
    val subtitle: String? = null,
    val contentType: ContentType = ContentType.LIVE,
    val isLive: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = true,
    val positionMillis: Long = 0L,
    val durationMillis: Long = 0L,
    val error: String? = null,
    val reconnectAttempt: Int = 0,
    val aspect: AspectRatioMode = AspectRatioMode.FIT,
    val audioTracks: List<TrackOption> = emptyList(),
    val subtitleTracks: List<TrackOption> = emptyList(),
    val subtitlesEnabled: Boolean = true,
    val nowPlaying: EpgProgram? = null,
    val nextProgram: EpgProgram? = null,
    val channelNumber: Int? = null,
    val hasNextEpisode: Boolean = false,
)

@UnstableApi
@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedState: SavedStateHandle,
    private val content: ContentRepository,
    private val playlists: PlaylistRepository,
    private val settings: SettingsRepository,
    okHttp: OkHttpClient,
) : ViewModel() {

    private val route = savedState.toRoute<Route.Player>()
    private val _state = MutableStateFlow(PlayerUiState(contentType = ContentType.valueOf(route.contentType)))
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val trackSelector = DefaultTrackSelector(context).apply {
        setParameters(buildUponParameters().setPreferredTextLanguage(Locale.getDefault().language))
    }

    val player: ExoPlayer = ExoPlayer.Builder(
        context,
        DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF),
    )
        .setTrackSelector(trackSelector)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(context).setDataSourceFactory(
                OkHttpDataSource.Factory(okHttp).setUserAgent("IPTVPlayer/1.0 (Android)"),
            ),
        )
        .setHandleAudioBecomingNoisy(true)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(30_000)
        .build()

    private var channelList: List<Channel> = emptyList()
    private var channelIndex = -1
    private var currentEpisode: Episode? = null
    private var currentContentId: String = route.contentId
    private var reconnectJob: Job? = null
    private var progressJob: Job? = null
    private var epgJob: Job? = null
    private var lastSavedPosition = 0L

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { _state.update { it.copy(isPlaying = isPlaying) } }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update {
                it.copy(
                    isBuffering = playbackState == Player.STATE_BUFFERING,
                    error = if (playbackState == Player.STATE_READY) null else it.error,
                    reconnectAttempt = if (playbackState == Player.STATE_READY) 0 else it.reconnectAttempt,
                )
            }
            if (playbackState == Player.STATE_ENDED) onEnded()
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.update { it.copy(error = error.errorCodeName) }
            scheduleReconnect()
        }

        override fun onTracksChanged(tracks: Tracks) { refreshTracks(tracks) }
    }

    init {
        player.addListener(listener)
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
                val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                _state.update { it.copy(positionMillis = player.currentPosition, durationMillis = duration) }
                if (!_state.value.isLive && duration > 0 && abs(player.currentPosition - lastSavedPosition) > 5_000) {
                    persistProgress()
                }
            }
        }
    }

    // ------------------------------------------------------------------ start

    private suspend fun startLive() {
        val channels = content.channels(route.playlistId, route.categoryId).first()
        channelList = channels
        channelIndex = channels.indexOfFirst { it.id == route.contentId }
        val channel = channels.getOrNull(channelIndex) ?: content.channel(route.playlistId, route.contentId) ?: return
        playChannel(channel)
    }

    private fun playChannel(channel: Channel) {
        currentContentId = channel.id
        _state.update {
            it.copy(
                title = channel.name, subtitle = channel.categoryName, isLive = true,
                channelNumber = channel.channelNumber, nowPlaying = null, nextProgram = null, error = null,
            )
        }
        prepare(channel.streamUrl, live = true)
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

    private fun prepare(url: String, live: Boolean, startPosition: Long = 0L) {
        reconnectJob?.cancel()
        val builder = MediaItem.Builder().setUri(url)
        if (live) builder.setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMaxPlaybackSpeed(1.02f).build())
        player.setMediaItem(builder.build(), startPosition)
        player.prepare()
        player.playWhenReady = true
    }

    /** Adds an external .srt/.vtt sidecar (picked by the user) to the current item. */
    fun addExternalSubtitle(uri: Uri) {
        val current = player.currentMediaItem ?: return
        val position = player.currentPosition
        val name = uri.lastPathSegment.orEmpty()
        val config = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(if (name.endsWith(".vtt", true)) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
            .setLabel(name.substringAfterLast('/'))
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val updated = current.buildUpon().setSubtitleConfigurations(current.localConfiguration?.subtitleConfigurations.orEmpty() + config).build()
        player.setMediaItem(updated, position)
        player.prepare()
        player.play()
    }

    // -------------------------------------------------------------- reconnect

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            val attempt = _state.value.reconnectAttempt + 1
            if (attempt > MAX_RECONNECTS) return@launch
            _state.update { it.copy(reconnectAttempt = attempt) }
            delay((1_000L * attempt).coerceAtMost(8_000L))
            val position = if (_state.value.isLive) C.TIME_UNSET else player.currentPosition
            player.seekToDefaultPosition()
            if (position != C.TIME_UNSET) player.seekTo(position)
            player.prepare()
            player.playWhenReady = true
        }
    }

    // ------------------------------------------------------------------ tracks

    private fun refreshTracks(tracks: Tracks) {
        fun options(type: Int): List<TrackOption> = tracks.groups.filter { it.type == type && it.isSupported }.flatMap { g ->
            (0 until g.length).map { i ->
                val f = g.getTrackFormat(i)
                val label = listOfNotNull(f.label, f.language?.let { Locale.forLanguageTag(it).displayLanguage.ifBlank { it } })
                    .distinct().joinToString(" · ").ifBlank { "Track ${i + 1}" }
                TrackOption(g.mediaTrackGroup, i, label, g.isTrackSelected(i))
            }
        }
        val subs = options(C.TRACK_TYPE_TEXT)
        _state.update {
            it.copy(
                audioTracks = options(C.TRACK_TYPE_AUDIO),
                subtitleTracks = subs,
                subtitlesEnabled = !player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT),
            )
        }
    }

    fun selectTrack(option: TrackOption) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(option.group.type, false)
            .setOverrideForType(TrackSelectionOverride(option.group, option.index))
            .build()
    }

    fun disableSubtitles() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        _state.update { it.copy(subtitlesEnabled = false) }
    }

    // ---------------------------------------------------------------- controls

    fun togglePlayPause() { if (player.isPlaying) player.pause() else player.play() }
    fun play() = player.play()
    fun pause() = player.pause()
    fun seekForward() { if (!_state.value.isLive) player.seekForward() }
    fun seekBack() { if (!_state.value.isLive) player.seekBack() }
    fun seekTo(fraction: Float) {
        val d = player.duration.takeIf { it != C.TIME_UNSET } ?: return
        player.seekTo((d * fraction.coerceIn(0f, 1f)).toLong())
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
        val next = entries[(entries.indexOf(_state.value.aspect) + 1) % entries.size]
        _state.update { it.copy(aspect = next) }
        viewModelScope.launch { settings.setAspectRatio(next) }
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
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: return
        val position = if (finished) duration else player.currentPosition
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
        player.removeListener(listener)
        player.release()
        super.onCleared()
    }

    companion object { const val MAX_RECONNECTS = 8 }
}
