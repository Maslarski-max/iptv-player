package com.maslarski.iptv.data.remote.xtream

import kotlinx.serialization.json.JsonElement
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * Xtream Codes compatible `player_api.php` endpoints.
 *
 * Responses are consumed as raw [JsonElement] because Xtream servers are notoriously
 * inconsistent about types (numbers vs. strings, objects vs. arrays); see [XtreamJson].
 */
interface XtreamApi {
    @GET
    suspend fun call(
        @Url url: String,
        @Query("username") username: String,
        @Query("password") password: String,
        @Query("action") action: String? = null,
        @Query("category_id") categoryId: String? = null,
        @Query("vod_id") vodId: String? = null,
        @Query("series_id") seriesId: String? = null,
        @Query("stream_id") streamId: String? = null,
    ): JsonElement

    @Streaming
    @GET
    suspend fun download(@Url url: String): ResponseBody
}

object XtreamActions {
    const val LIVE_CATEGORIES = "get_live_categories"
    const val VOD_CATEGORIES = "get_vod_categories"
    const val SERIES_CATEGORIES = "get_series_categories"
    const val LIVE_STREAMS = "get_live_streams"
    const val VOD_STREAMS = "get_vod_streams"
    const val SERIES = "get_series"
    const val VOD_INFO = "get_vod_info"
    const val SERIES_INFO = "get_series_info"
    const val SHORT_EPG = "get_short_epg"
}
