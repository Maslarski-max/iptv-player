package com.maslarski.iptv.di

import android.content.Context
import androidx.room.Room
import com.maslarski.iptv.data.local.IptvDatabase
import com.maslarski.iptv.data.remote.tmdb.TmdbApi
import com.maslarski.iptv.data.remote.xtream.XtreamApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.sentry.okhttp.SentryOkHttpInterceptor
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): IptvDatabase =
        Room.databaseBuilder(context, IptvDatabase::class.java, IptvDatabase.NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .addInterceptor(SentryOkHttpInterceptor())
        .build()

    @Provides
    @Singleton
    fun xtreamApi(client: OkHttpClient, json: Json): XtreamApi = Retrofit.Builder()
        .baseUrl("http://localhost/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(XtreamApi::class.java)

    @Provides
    @Singleton
    fun tmdbApi(client: OkHttpClient, json: Json): TmdbApi = Retrofit.Builder()
        .baseUrl(TmdbApi.BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(TmdbApi::class.java)

    const val USER_AGENT = "IPTVPlayer/1.0 (Android; Media3)"
}
