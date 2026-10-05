package com.maslarski.iptv.ui.player

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * Buffer thresholds for one kind of content; see [ProfileLoadControl].
 */
data class BufferProfile(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
)

/**
 * A [LoadControl] that switches between a fast-zapping live profile and a high-retention VOD profile
 * on a single [androidx.media3.exoplayer.ExoPlayer] instance. Both delegates share one allocator and
 * receive every lifecycle callback; threshold decisions come from the profile selected with [setLive]
 * before the next media item is prepared.
 */
@UnstableApi
class ProfileLoadControl(live: BufferProfile, vod: BufferProfile) : LoadControl {
    private val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
    private val liveControl = live.toLoadControl()
    private val vodControl = vod.toLoadControl()

    @Volatile
    private var active: DefaultLoadControl = liveControl

    fun setLive(isLive: Boolean) {
        active = if (isLive) liveControl else vodControl
    }

    private fun BufferProfile.toLoadControl(): DefaultLoadControl =
        DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

    /** The active delegate goes last so its allocator target wins. */
    private inline fun forEach(block: (DefaultLoadControl) -> Unit) {
        val current = active
        if (current !== liveControl) block(liveControl)
        if (current !== vodControl) block(vodControl)
        block(current)
    }

    override fun onPrepared(playerId: PlayerId) = forEach { it.onPrepared(playerId) }

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = forEach { it.onTracksSelected(parameters, trackGroups, trackSelections) }

    override fun onStopped(playerId: PlayerId) = forEach { it.onStopped(playerId) }

    override fun onReleased(playerId: PlayerId) = forEach { it.onReleased(playerId) }

    override fun getAllocator(playerId: PlayerId): Allocator = allocator

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = active.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = active.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean = active.shouldContinueLoading(parameters)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean = active.shouldStartPlayback(parameters)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        targetPreloadPositionUs: Long,
    ): Boolean = active.shouldContinuePreloading(playerId, timeline, mediaPeriodId, targetPreloadPositionUs)
}
