package com.maslarski.iptv.ui.player

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
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

/** The live and VOD thresholds that make up one user-selectable buffer size. */
data class BufferProfiles(val live: BufferProfile, val vod: BufferProfile)

/**
 * A time-prioritised [LoadControl] whose thresholds switch between a fast-zapping live profile and a
 * high-retention VOD profile on a single [androidx.media3.exoplayer.ExoPlayer]. Select the profile with
 * [setLive] before preparing the next media item. Byte accounting is size-prioritised: the target size is
 * the smaller of the selected tracks' default sizes and a per-profile cap derived from the profile's
 * maximum duration, and once it is reached loading stops even below the minimum duration. A stream whose
 * timestamps stop advancing therefore cannot grow the sample queues without bound.
 */
@UnstableApi
class ProfileLoadControl(profiles: BufferProfiles) : LoadControl {
    private val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)

    @Volatile
    private var profiles: BufferProfiles = profiles

    @Volatile
    private var isLive = true
    private val profile: BufferProfile get() = profiles.let { if (isLive) it.live else it.vod }
    private var trackBufferBytes = MIN_TARGET_BYTES
    private var targetBufferBytes = MIN_TARGET_BYTES
    private var isLoading = false

    fun setLive(isLive: Boolean) {
        this.isLive = isLive
    }

    /** Swaps the user-selected size; takes effect on the next loading decision. */
    fun setProfiles(profiles: BufferProfiles) {
        this.profiles = profiles
    }

    override fun onPrepared(playerId: PlayerId) {
        isLoading = false
    }

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) {
        trackBufferBytes = trackSelections.filterNotNull().sumOf { defaultBufferSize(it.trackGroup.type) }
        applyTargetBufferBytes()
    }

    private fun applyTargetBufferBytes() {
        val target = minOf(trackBufferBytes, byteCapFor(profile))
        if (target != targetBufferBytes) {
            targetBufferBytes = target
            allocator.setTargetBufferSize(target)
        }
    }

    /** Bytes a profile may hold: its maximum duration at a generous IPTV bitrate, bounded by the heap. */
    private fun byteCapFor(p: BufferProfile): Int {
        val heapCap = (Runtime.getRuntime().maxMemory() / HEAP_FRACTION).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val byDuration = (p.maxBufferMs.toLong() * PEAK_BYTES_PER_SECOND / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return byDuration.coerceIn(MIN_TARGET_BYTES, heapCap.coerceAtLeast(MIN_TARGET_BYTES))
    }

    override fun onStopped(playerId: PlayerId) = reset()

    override fun onReleased(playerId: PlayerId) = reset()

    override fun getAllocator(playerId: PlayerId): Allocator = allocator

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = 0L

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = false

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val p = profile
        applyTargetBufferBytes()
        val bufferedUs = parameters.bufferedDurationUs
        val minBufferUs = Util.getMediaDurationForPlayoutDuration(Util.msToUs(p.minBufferMs.toLong()), parameters.playbackSpeed)
        val maxBufferUs = Util.msToUs(p.maxBufferMs.toLong())
        val targetBytesReached = allocator.totalBytesAllocated >= targetBufferBytes
        isLoading = when {
            targetBytesReached || bufferedUs >= maxBufferUs -> false
            bufferedUs < minBufferUs -> true
            else -> isLoading
        }
        return isLoading
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val p = profile
        var targetPlayoutUs = Util.msToUs(
            (if (parameters.rebuffering) p.bufferForPlaybackAfterRebufferMs else p.bufferForPlaybackMs).toLong(),
        )
        if (parameters.targetLiveOffsetUs != C.TIME_UNSET) targetPlayoutUs = minOf(parameters.targetLiveOffsetUs / 2, targetPlayoutUs)
        val bufferedPlayoutUs = Util.getPlayoutDurationForMediaDuration(parameters.bufferedDurationUs, parameters.playbackSpeed)
        return targetPlayoutUs <= 0 || bufferedPlayoutUs >= targetPlayoutUs
    }

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaSource.MediaPeriodId,
        targetPreloadPositionUs: Long,
    ): Boolean = false

    private fun reset() {
        isLoading = false
        trackBufferBytes = MIN_TARGET_BYTES
        targetBufferBytes = MIN_TARGET_BYTES
        allocator.setTargetBufferSize(targetBufferBytes)
        allocator.reset()
    }

    private fun defaultBufferSize(trackType: @C.TrackType Int): Int = when (trackType) {
        C.TRACK_TYPE_DEFAULT -> DefaultLoadControl.DEFAULT_MUXED_BUFFER_SIZE
        C.TRACK_TYPE_AUDIO -> DefaultLoadControl.DEFAULT_AUDIO_BUFFER_SIZE
        C.TRACK_TYPE_VIDEO -> DefaultLoadControl.DEFAULT_VIDEO_BUFFER_SIZE
        C.TRACK_TYPE_TEXT -> DefaultLoadControl.DEFAULT_TEXT_BUFFER_SIZE
        C.TRACK_TYPE_METADATA -> DefaultLoadControl.DEFAULT_METADATA_BUFFER_SIZE
        C.TRACK_TYPE_CAMERA_MOTION -> DefaultLoadControl.DEFAULT_CAMERA_MOTION_BUFFER_SIZE
        C.TRACK_TYPE_IMAGE -> DefaultLoadControl.DEFAULT_IMAGE_BUFFER_SIZE
        else -> 0
    }

    private companion object {
        const val MIN_TARGET_BYTES = 16 * 1024 * 1024
        const val PEAK_BYTES_PER_SECOND = 1_572_864L // 12 Mbit/s
        const val HEAP_FRACTION = 4L
    }
}
