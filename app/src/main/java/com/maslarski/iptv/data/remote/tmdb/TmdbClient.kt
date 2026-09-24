package com.maslarski.iptv.data.remote.tmdb

import com.maslarski.iptv.BuildConfig
import com.maslarski.iptv.data.settings.SettingsRepository
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Normalised TMDB metadata that can be merged into playlist items. */
data class TmdbMetadata(
    val tmdbId: Long,
    val title: String,
    val synopsis: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val releaseYear: String?,
    val rating: Double?,
    val genre: String?,
    val cast: String?,
    val director: String?,
    val durationSeconds: Long?,
)

@Singleton
class TmdbClient @Inject constructor(
    private val api: TmdbApi,
    private val settings: SettingsRepository,
) {

    /** User-provided key wins over the build-time key so APK users can enable TMDB from Settings. */
    suspend fun apiKey(): String? =
        settings.current().tmdbApiKey.takeIf { it.isNotBlank() } ?: BuildConfig.TMDB_API_KEY.takeIf { it.isNotBlank() }

    suspend fun isConfigured(): Boolean = apiKey() != null

    suspend fun findMovie(rawTitle: String, fallbackYear: String? = null): TmdbMetadata? {
        val key = apiKey() ?: return null
        val cleaned = TitleCleaner.clean(rawTitle)
        val year = cleaned.year ?: fallbackYear?.toIntOrNull()
        val lang = language()
        val hit = pick(api.searchMovie(cleaned.title, key, lang, year).results, cleaned.title, year)
            ?: year?.let { pick(api.searchMovie(cleaned.title, key, lang).results, cleaned.title, null) }
            ?: return null
        val details = runCatching { api.movieDetails(hit.id, key, lang) }.getOrNull()
        return merge(hit, details)
    }

    suspend fun findSeries(rawTitle: String, fallbackYear: String? = null): TmdbMetadata? {
        val key = apiKey() ?: return null
        val cleaned = TitleCleaner.clean(rawTitle)
        val year = cleaned.year ?: fallbackYear?.toIntOrNull()
        val lang = language()
        val hit = pick(api.searchTv(cleaned.title, key, lang, year).results, cleaned.title, year)
            ?: year?.let { pick(api.searchTv(cleaned.title, key, lang).results, cleaned.title, null) }
            ?: return null
        val details = runCatching { api.tvDetails(hit.id, key, lang) }.getOrNull()
        return merge(hit, details)
    }

    private suspend fun language(): String {
        val tag = settings.current().languageTag
        return if (tag.isNotBlank()) tag else Locale.getDefault().toLanguageTag()
    }

    private fun pick(results: List<TmdbSearchResult>, title: String, year: Int?): TmdbSearchResult? {
        if (results.isEmpty()) return null
        val wanted = TitleCleaner.normalise(title)
        return results.maxByOrNull { r ->
            var score = 0.0
            val candidates = listOfNotNull(r.title, r.name, r.originalTitle, r.originalName).map(TitleCleaner::normalise)
            if (candidates.any { it == wanted }) score += 100
            else if (candidates.any { it.contains(wanted) || wanted.contains(it) }) score += 40
            if (year != null && r.year != null) score += when (kotlin.math.abs(r.year!! - year)) { 0 -> 30; 1 -> 15; else -> -20 }
            if (r.posterPath != null) score += 5
            score += (r.voteCount ?: 0).coerceAtMost(1000) / 200.0
            score
        }
    }

    private fun merge(hit: TmdbSearchResult, details: TmdbDetails?): TmdbMetadata {
        val poster = details?.posterPath ?: hit.posterPath
        val backdrop = details?.backdropPath ?: hit.backdropPath
        val credits = details?.credits
        return TmdbMetadata(
            tmdbId = hit.id,
            title = details?.title ?: details?.name ?: hit.displayTitle,
            synopsis = (details?.overview ?: hit.overview)?.takeIf { it.isNotBlank() },
            posterUrl = poster?.let { TmdbApi.IMAGE_BASE + TmdbApi.POSTER_SIZE + it },
            backdropUrl = backdrop?.let { TmdbApi.IMAGE_BASE + TmdbApi.BACKDROP_SIZE + it },
            releaseYear = (details?.releaseDate ?: details?.firstAirDate ?: hit.releaseDate ?: hit.firstAirDate)?.take(4)?.takeIf { it.length == 4 },
            rating = (details?.voteAverage ?: hit.voteAverage)?.takeIf { it > 0 },
            genre = details?.genres?.map { it.name }?.takeIf { it.isNotEmpty() }?.joinToString(", "),
            cast = credits?.cast?.take(8)?.map { it.name }?.takeIf { it.isNotEmpty() }?.joinToString(", "),
            director = credits?.crew?.filter { it.job == "Director" || it.job == "Creator" }?.map { it.name }?.distinct()
                ?.takeIf { it.isNotEmpty() }?.joinToString(", "),
            durationSeconds = details?.runtime?.takeIf { it > 0 }?.let { it * 60L },
        )
    }
}

/** Strips the provider noise IPTV titles carry ("EN - Movie (2019) [4K] HD") down to a searchable title. */
object TitleCleaner {

    data class Cleaned(val title: String, val year: Int?)

    private val yearRegex = Regex("""[\(\[\s](19\d{2}|20\d{2})[\)\]\s]?""")
    private val bracketRegex = Regex("""[\[\(\{][^\]\)\}]*[\]\)\}]""")
    private val prefixRegex = Regex("""^\s*(?:[A-Z]{2,3}\s*[-|:]\s*|[A-Z]{2,3}\s*\|\s*)+""")
    private val tagRegex = Regex(
        """\b(4K|UHD|FHD|HD|SD|HDR|1080p|720p|2160p|480p|x264|x265|HEVC|H\.?264|H\.?265|WEB-?DL|WEBRip|BluRay|BRRip|HDRip|DVDRip|CAM|TS|MULTI|DUAL|SUB|SUBBED|DUBBED|VOSTFR|LATINO|CASTELLANO|VF|VO|MULTISUB)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val seasonRegex = Regex("""\b(S\d{1,2}(E\d{1,3})?|Season\s*\d+|Temporada\s*\d+|Staffel\s*\d+|Saison\s*\d+)\b.*$""", RegexOption.IGNORE_CASE)
    private val separatorsRegex = Regex("""[._]+""")
    private val spaceRegex = Regex("""\s{2,}""")
    private val trailingJunkRegex = Regex("""[\s\-–|:•]+$""")

    fun clean(raw: String): Cleaned {
        var s = raw.trim()
        val year = yearRegex.find(s)?.groupValues?.get(1)?.toIntOrNull()
        s = prefixRegex.replace(s, "")
        s = bracketRegex.replace(s, " ")
        s = seasonRegex.replace(s, "")
        s = tagRegex.replace(s, " ")
        if (year != null) s = s.replace(year.toString(), " ")
        s = separatorsRegex.replace(s, " ")
        s = spaceRegex.replace(s, " ")
        s = trailingJunkRegex.replace(s, "").trim()
        return Cleaned(if (s.isBlank()) raw.trim() else s, year)
    }

    fun normalise(s: String): String =
        s.lowercase(Locale.ROOT).replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
}
