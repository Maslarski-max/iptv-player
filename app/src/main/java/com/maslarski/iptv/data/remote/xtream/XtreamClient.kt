package com.maslarski.iptv.data.remote.xtream

import com.maslarski.iptv.data.local.entity.CategoryEntity
import com.maslarski.iptv.data.local.entity.ChannelEntity
import com.maslarski.iptv.data.local.entity.EpisodeEntity
import com.maslarski.iptv.data.local.entity.MovieEntity
import com.maslarski.iptv.data.local.entity.SeriesEntity
import com.maslarski.iptv.domain.model.ContentType
import com.maslarski.iptv.domain.model.Playlist
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** Lenient accessors for the loosely typed JSON returned by Xtream servers. */
object XtreamJson {
    fun JsonElement?.str(vararg keys: String): String? {
        val obj = this as? JsonObject ?: return null
        for (key in keys) {
            val v = obj[key] ?: continue
            val s = when (v) {
                is JsonPrimitive -> v.contentOrNull
                is JsonNull -> null
                is JsonArray -> v.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
                else -> null
            }
            if (!s.isNullOrBlank() && s != "null") return s
        }
        return null
    }

    fun JsonElement?.long(vararg keys: String): Long? = str(*keys)?.trim()?.toDoubleOrNull()?.toLong()
    fun JsonElement?.int(vararg keys: String): Int? = long(*keys)?.toInt()
    fun JsonElement?.double(vararg keys: String): Double? = str(*keys)?.trim()?.toDoubleOrNull()?.takeIf { it > 0.0 }
    fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
    fun JsonElement?.arr(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray

    fun JsonElement.asArrayOrEmpty(): List<JsonElement> = when (this) {
        is JsonArray -> this
        is JsonObject -> values.toList()
        else -> emptyList()
    }
}

data class XtreamAccount(
    val status: String?,
    val expiresAt: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
    val allowedOutputFormats: List<String>,
    val serverUrl: String,
    val timezone: String?,
)

data class XtreamSeriesDetails(
    val series: SeriesEntity,
    val episodes: List<EpisodeEntity>,
)

@Singleton
class XtreamClient @Inject constructor(private val api: XtreamApi) {

    private fun Playlist.base(): String = url.trimEnd('/')
    private fun Playlist.apiUrl(): String = "${base()}/player_api.php"
    private fun Playlist.user() = username.orEmpty()
    private fun Playlist.pass() = password.orEmpty()

    fun epgUrl(playlist: Playlist): String =
        "${playlist.base()}/xmltv.php?username=${playlist.user()}&password=${playlist.pass()}"

    suspend fun authenticate(playlist: Playlist): XtreamAccount = with(XtreamJson) {
        val root = api.call(playlist.apiUrl(), playlist.user(), playlist.pass())
        val user = root.obj("user_info")
        val server = root.obj("server_info")
        if (user == null || user.str("auth") == "0") error("Authentication failed")
        val formats = user.arr("allowed_output_formats")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        XtreamAccount(
            status = user.str("status"),
            expiresAt = user.long("exp_date")?.times(1000),
            maxConnections = user.int("max_connections"),
            activeConnections = user.int("active_cons"),
            allowedOutputFormats = formats,
            serverUrl = playlist.base(),
            timezone = server.str("timezone"),
        )
    }

    suspend fun categories(playlist: Playlist, type: ContentType): List<CategoryEntity> = with(XtreamJson) {
        val action = when (type) {
            ContentType.LIVE -> XtreamActions.LIVE_CATEGORIES
            ContentType.MOVIE -> XtreamActions.VOD_CATEGORIES
            ContentType.SERIES -> XtreamActions.SERIES_CATEGORIES
        }
        api.call(playlist.apiUrl(), playlist.user(), playlist.pass(), action).asArrayOrEmpty().mapNotNull { item ->
            val id = item.str("category_id") ?: return@mapNotNull null
            CategoryEntity(
                id = id,
                playlistId = playlist.id,
                name = item.str("category_name") ?: "Category $id",
                type = type,
            )
        }
    }

    suspend fun liveStreams(playlist: Playlist, categoryNames: Map<String, String>): List<ChannelEntity> = with(XtreamJson) {
        val preferHls = false
        api.call(playlist.apiUrl(), playlist.user(), playlist.pass(), XtreamActions.LIVE_STREAMS)
            .asArrayOrEmpty().mapIndexedNotNull { index, item ->
                val id = item.str("stream_id") ?: return@mapIndexedNotNull null
                val ext = if (preferHls) "m3u8" else "ts"
                val categoryId = item.str("category_id")
                ChannelEntity(
                    id = id,
                    playlistId = playlist.id,
                    name = item.str("name") ?: "Channel $id",
                    streamUrl = "${playlist.base()}/live/${playlist.user()}/${playlist.pass()}/$id.$ext",
                    logoUrl = item.str("stream_icon"),
                    categoryId = categoryId,
                    categoryName = categoryId?.let(categoryNames::get),
                    epgChannelId = item.str("epg_channel_id"),
                    channelNumber = item.int("num"),
                    sortOrder = item.int("num") ?: index,
                )
            }
    }

    suspend fun vodStreams(playlist: Playlist, categoryNames: Map<String, String>): List<MovieEntity> = with(XtreamJson) {
        api.call(playlist.apiUrl(), playlist.user(), playlist.pass(), XtreamActions.VOD_STREAMS)
            .asArrayOrEmpty().mapNotNull { item ->
                val id = item.str("stream_id") ?: return@mapNotNull null
                val ext = item.str("container_extension") ?: "mp4"
                val categoryId = item.str("category_id")
                MovieEntity(
                    id = id,
                    playlistId = playlist.id,
                    title = item.str("name", "title") ?: "Movie $id",
                    streamUrl = "${playlist.base()}/movie/${playlist.user()}/${playlist.pass()}/$id.$ext",
                    posterUrl = item.str("stream_icon", "cover"),
                    backdropUrl = item.str("backdrop_path"),
                    categoryId = categoryId,
                    categoryName = categoryId?.let(categoryNames::get),
                    synopsis = item.str("plot", "description"),
                    releaseYear = (item.str("year") ?: item.str("releasedate", "release_date"))?.take(4),
                    rating = item.double("rating_5based")?.times(2) ?: item.double("rating"),
                    cast = item.str("cast", "actors"),
                    director = item.str("director"),
                    genre = item.str("genre"),
                    durationSeconds = item.long("duration_secs"),
                    addedAt = item.long("added")?.times(1000),
                )
            }
    }

    suspend fun series(playlist: Playlist, categoryNames: Map<String, String>): List<SeriesEntity> = with(XtreamJson) {
        api.call(playlist.apiUrl(), playlist.user(), playlist.pass(), XtreamActions.SERIES)
            .asArrayOrEmpty().mapNotNull { item ->
                val id = item.str("series_id") ?: return@mapNotNull null
                val categoryId = item.str("category_id")
                SeriesEntity(
                    id = id,
                    playlistId = playlist.id,
                    title = item.str("name", "title") ?: "Series $id",
                    posterUrl = item.str("cover", "stream_icon"),
                    backdropUrl = item.arr("backdrop_path")?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
                        ?: item.str("backdrop_path"),
                    categoryId = categoryId,
                    categoryName = categoryId?.let(categoryNames::get),
                    synopsis = item.str("plot"),
                    releaseYear = item.str("releaseDate", "release_date", "year")?.take(4),
                    rating = item.double("rating_5based")?.times(2) ?: item.double("rating"),
                    cast = item.str("cast"),
                    genre = item.str("genre"),
                )
            }
    }

    suspend fun seriesDetails(playlist: Playlist, existing: SeriesEntity): XtreamSeriesDetails = with(XtreamJson) {
        val root = api.call(playlist.apiUrl(), playlist.user(), playlist.pass(), XtreamActions.SERIES_INFO, seriesId = existing.id)
        val info = root.obj("info")
        val episodesNode = (root as? JsonObject)?.get("episodes")
        val seasonLists: List<Pair<Int?, JsonArray>> = when (episodesNode) {
            is JsonObject -> episodesNode.entries.map { (k, v) -> k.toIntOrNull() to (v as? JsonArray ?: JsonArray(emptyList())) }
            is JsonArray -> episodesNode.map { null to (it as? JsonArray ?: JsonArray(emptyList())) }
            else -> emptyList()
        }
        val episodes = seasonLists.flatMap { (seasonKey, list) ->
            list.mapNotNull { ep ->
                val id = ep.str("id") ?: return@mapNotNull null
                val ext = ep.str("container_extension") ?: "mp4"
                val epInfo = ep.obj("info")
                EpisodeEntity(
                    id = id,
                    seriesId = existing.id,
                    playlistId = playlist.id,
                    seasonNumber = ep.int("season") ?: seasonKey ?: 1,
                    episodeNumber = ep.int("episode_num") ?: 0,
                    title = ep.str("title") ?: "Episode ${ep.str("episode_num")}",
                    streamUrl = "${playlist.base()}/series/${playlist.user()}/${playlist.pass()}/$id.$ext",
                    thumbnailUrl = epInfo.str("movie_image", "cover_big") ?: existing.posterUrl,
                    synopsis = epInfo.str("plot"),
                    durationSeconds = epInfo.long("duration_secs"),
                )
            }
        }
        val updated = existing.copy(
            synopsis = info.str("plot") ?: existing.synopsis,
            cast = info.str("cast") ?: existing.cast,
            genre = info.str("genre") ?: existing.genre,
            rating = info.double("rating_5based")?.times(2) ?: info.double("rating") ?: existing.rating,
            releaseYear = info.str("releaseDate", "release_date")?.take(4) ?: existing.releaseYear,
            backdropUrl = info.arr("backdrop_path")?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull } ?: existing.backdropUrl,
            detailsFetchedAt = System.currentTimeMillis(),
        )
        XtreamSeriesDetails(updated, episodes)
    }

    suspend fun movieDetails(playlist: Playlist, existing: MovieEntity): MovieEntity = with(XtreamJson) {
        val root = api.call(playlist.apiUrl(), playlist.user(), playlist.pass(), XtreamActions.VOD_INFO, vodId = existing.id)
        val info = root.obj("info") ?: return existing
        existing.copy(
            synopsis = info.str("plot", "description") ?: existing.synopsis,
            cast = info.str("cast", "actors") ?: existing.cast,
            director = info.str("director") ?: existing.director,
            genre = info.str("genre") ?: existing.genre,
            releaseYear = info.str("releasedate", "release_date", "year")?.take(4) ?: existing.releaseYear,
            rating = info.double("rating") ?: existing.rating,
            backdropUrl = info.arr("backdrop_path")?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull } ?: existing.backdropUrl,
            durationSeconds = info.long("duration_secs") ?: existing.durationSeconds,
            posterUrl = info.str("movie_image", "cover_big") ?: existing.posterUrl,
        )
    }

    suspend fun downloadEpg(playlist: Playlist) = api.download(epgUrl(playlist))

    suspend fun downloadM3u(url: String) = api.download(url)
}
