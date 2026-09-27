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

    /** One process-wide LibVLC core; each [PlayerViewModel] creates its own `MediaPlayer` on top of it. */
    @Provides
    @Singleton
    fun libVlc(@ApplicationContext context: Context): LibVLC = LibVLC(
        context,
        arrayListOf(
            "--network-caching=$NETWORK_CACHING_MS",
            "--live-caching=$NETWORK_CACHING_MS",
            "--clock-jitter=0",
            "--clock-synchro=0",
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

    const val NETWORK_CACHING_MS = 5_000
}
