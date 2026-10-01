package com.maslarski.iptv.ui.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.maslarski.iptv.data.settings.DecoderMode

data class DecoderPreferences(val video: DecoderMode, val audio: DecoderMode)

/**
 * Renderers factory that lets video and audio each choose between the hardware MediaCodec pipeline and
 * software decoding. Hardware mode keeps `MediaCodecSelector.DEFAULT` (hardware codecs ranked first by the
 * platform) and no extension renderers; software mode ranks software-only MediaCodec decoders
 * (`c2.android.*` / `OMX.google.*`) ahead of hardware ones and prefers bundled extension renderers
 * (e.g. FFmpeg) when present on the classpath, so the hardware pipeline is bypassed whenever an
 * alternative exists. Decoder fallback is left on so an unsupported stream still plays.
 */
@UnstableApi
class DecoderEngineRenderersFactory(context: Context, private val prefs: DecoderPreferences) : DefaultRenderersFactory(context) {

    init {
        val anySoftware = prefs.video == DecoderMode.SOFTWARE || prefs.audio == DecoderMode.SOFTWARE
        setExtensionRendererMode(if (anySoftware) EXTENSION_RENDERER_MODE_PREFER else EXTENSION_RENDERER_MODE_OFF)
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) = super.buildVideoRenderers(
        context,
        if (prefs.video == DecoderMode.SOFTWARE) extensionRendererMode else EXTENSION_RENDERER_MODE_OFF,
        selectorFor(prefs.video),
        enableDecoderFallback,
        eventHandler,
        eventListener,
        allowedVideoJoiningTimeMs,
        out,
    )

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) = super.buildAudioRenderers(
        context,
        if (prefs.audio == DecoderMode.SOFTWARE) extensionRendererMode else EXTENSION_RENDERER_MODE_OFF,
        selectorFor(prefs.audio),
        enableDecoderFallback,
        audioSink,
        eventHandler,
        eventListener,
        out,
    )

    private fun selectorFor(mode: DecoderMode): MediaCodecSelector = when (mode) {
        DecoderMode.HARDWARE -> MediaCodecSelector.DEFAULT
        DecoderMode.SOFTWARE -> SoftwareFirstCodecSelector
    }

    private object SoftwareFirstCodecSelector : MediaCodecSelector {
        override fun getDecoderInfos(mimeType: String, requiresSecureDecoder: Boolean, requiresTunnelingDecoder: Boolean): List<MediaCodecInfo> {
            val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            val (software, hardware) = all.partition { it.softwareOnly || !it.hardwareAccelerated }
            return software + hardware
        }
    }
}
