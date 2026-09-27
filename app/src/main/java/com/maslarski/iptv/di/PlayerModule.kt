package com.maslarski.iptv.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.videolan.libvlc.LibVLC
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {

    /**
     * One process-wide LibVLC core; each [PlayerViewModel] creates its own `MediaPlayer` on top of it.
     * Tuned for weak 32-bit (armeabi-v7a) TV boxes: hardware decoding first, strict A/V sync with
     * late-frame dropping, small caches (see [NETWORK_CACHING_MS] etc.) for fast zapping.
     */
    @Provides
    @Singleton
    fun libVlc(@ApplicationContext context: Context): LibVLC = LibVLC(
        context,
        arrayListOf(
            "--network-caching=$NETWORK_CACHING_MS",
            "--live-caching=$LIVE_CACHING_MS",
            "--file-caching=$FILE_CACHING_MS",
            "--sout-mux-caching=$SOUT_MUX_CACHING_MS",
            "--clock-jitter=0",
            "--clock-synchro=1",
            "--skip-frames",
            "--drop-late-frames",
            "--codec=mediacodec_ndk,mediacodec_jni,all",
            "--http-reconnect",
            "--http-user-agent=${AppModule.USER_AGENT}",
            "--audio-time-stretch",
            "--avcodec-skiploopfilter=1",
            "--no-video-title-show",
            "--no-stats",
            "--no-snapshot-preview",
        ),
    )

    const val NETWORK_CACHING_MS = 250
    const val LIVE_CACHING_MS = 150
    const val FILE_CACHING_MS = 150
    const val SOUT_MUX_CACHING_MS = 50
}
