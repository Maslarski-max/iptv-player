package com.maslarski.iptv.ui.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * [MediaCodecVideoRenderer] tuned for live IPTV on weak TV chipsets:
 *  - the decoder is flushed, never re-created, on position/stream resets and surface changes, so the
 *    MediaCodec + Surface pair survives channel/segment switches (no "SurfaceUtils: disconnecting from surface");
 *  - a frame that is late by more than [DROP_LATE_FRAME_US] is dropped on its own instead of waiting for it,
 *    and the renderer never jumps to the next keyframe (which would blank the picture);
 *  - jittery / non-advancing timestamps from TS muxers are tolerated by the frame release control rather
 *    than treated as a stream discontinuity.
 */
@UnstableApi
@androidx.annotation.OptIn(ExperimentalApi::class)
class StableVideoRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    selector: MediaCodecSelector,
    allowedJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler,
    eventListener: VideoRendererEventListener,
    maxDroppedFramesToNotify: Int,
) : MediaCodecVideoRenderer(
    context, codecAdapterFactory, selector, allowedJoiningTimeMs, enableDecoderFallback, eventHandler, eventListener, maxDroppedFramesToNotify,
) {
    init {
        experimentalDisableAdvancingTimestampChecksInVideoFrameReleaseControl()
    }

    override fun shouldReinitCodec(): Boolean = false

    override fun codecNeedsSetOutputSurfaceWorkaround(name: String): Boolean = false

    override fun shouldDropOutputBuffer(earlyUs: Long, elapsedRealtimeUs: Long, isLastBuffer: Boolean): Boolean =
        earlyUs < -DROP_LATE_FRAME_US && !isLastBuffer

    override fun shouldDropBuffersToKeyframe(earlyUs: Long, elapsedRealtimeUs: Long, isLastBuffer: Boolean): Boolean = false

    override fun shouldSkipBuffersWithIdenticalReleaseTime(): Boolean = false

    private companion object {
        const val DROP_LATE_FRAME_US = 30_000L
    }
}
