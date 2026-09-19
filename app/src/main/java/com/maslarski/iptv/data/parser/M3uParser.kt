package com.maslarski.iptv.data.parser

import com.maslarski.iptv.domain.model.ContentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedReader
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** A single `#EXTINF` entry with its stream URL. */
data class M3uEntry(
    val title: String,
    val url: String,
    val attributes: Map<String, String>,
    val durationSeconds: Long?,
) {
    val tvgId: String? get() = attributes["tvg-id"]?.takeIf { it.isNotBlank() }
    val tvgName: String? get() = attributes["tvg-name"]?.takeIf { it.isNotBlank() }
    val logo: String? get() = attributes["tvg-logo"]?.takeIf { it.isNotBlank() }
    val group: String? get() = attributes["group-title"]?.takeIf { it.isNotBlank() }
    val channelNumber: Int? get() = attributes["tvg-chno"]?.toIntOrNull()

    /** Stable identifier derived from the URL so re-syncs keep favorites and progress. */
    val stableId: String get() = M3uParser.stableId(url)

    val contentType: ContentType
        get() {
            val lower = url.lowercase()
            val g = group?.lowercase().orEmpty()
            return when {
                "/series/" in lower || SERIES_EPISODE.containsMatchIn(title) || g.contains("series") || g.contains("serie") -> ContentType.SERIES
                "/movie/" in lower || g.contains("movie") || g.contains("vod") || g.contains("film") ||
                    VOD_EXTENSIONS.any { lower.substringBefore('?').endsWith(it) } -> ContentType.MOVIE
                else -> ContentType.LIVE
            }
        }

    /** Parses "Show Name S01 E02" style titles into (seriesTitle, season, episode). */
    fun seriesInfo(): SeriesInfo? {
        val m = SERIES_EPISODE.find(title) ?: return null
        val seriesTitle = title.substring(0, m.range.first).trim().trimEnd('-', ':', '|').trim()
        return SeriesInfo(
            seriesTitle = seriesTitle.ifBlank { title },
            season = m.groupValues[1].toIntOrNull() ?: 1,
            episode = m.groupValues[2].toIntOrNull() ?: 1,
        )
    }

    data class SeriesInfo(val seriesTitle: String, val season: Int, val episode: Int)

    private companion object {
        val SERIES_EPISODE = Regex("""\bS(\d{1,3})\s*[ .\-_]?\s*E(\d{1,4})\b""", RegexOption.IGNORE_CASE)
        val VOD_EXTENSIONS = listOf(".mkv", ".mp4", ".avi", ".mov", ".wmv", ".flv", ".webm")
    }
}

sealed interface M3uParseEvent {
    data class Header(val epgUrl: String?) : M3uParseEvent
    data class Batch(val entries: List<M3uEntry>, val parsedSoFar: Int) : M3uParseEvent
    data class Done(val total: Int) : M3uParseEvent
}

/**
 * Streaming M3U/M3U8 parser. It never materialises the whole playlist in memory:
 * entries are emitted in batches so 100k+ channel files can be inserted incrementally.
 */
@Singleton
class M3uParser @Inject constructor() {

    fun parse(input: InputStream, batchSize: Int = DEFAULT_BATCH): Flow<M3uParseEvent> = flow {
        input.bufferedReader().use { reader ->
            var headerEmitted = false
            var pendingTitle: String? = null
            var pendingAttrs: Map<String, String> = emptyMap()
            var pendingDuration: Long? = null
            val extraAttrs = HashMap<String, String>()
            val batch = ArrayList<M3uEntry>(batchSize)
            var count = 0

            var line = reader.readLineOrNull()
            while (line != null) {
                val trimmed = line.trim()
                when {
                    trimmed.isEmpty() -> Unit
                    trimmed.startsWith("#EXTM3U") -> {
                        val attrs = parseAttributes(trimmed)
                        emit(M3uParseEvent.Header(attrs["url-tvg"] ?: attrs["x-tvg-url"]))
                        headerEmitted = true
                    }
                    trimmed.startsWith("#EXTINF") -> {
                        val info = parseExtInf(trimmed)
                        pendingTitle = info.first
                        pendingAttrs = info.second
                        pendingDuration = info.third
                    }
                    trimmed.startsWith("#EXTGRP:") -> extraAttrs["group-title"] = trimmed.substringAfter(':').trim()
                    trimmed.startsWith("#EXTVLCOPT:") -> {
                        val kv = trimmed.substringAfter(':')
                        extraAttrs[kv.substringBefore('=').trim()] = kv.substringAfter('=', "").trim()
                    }
                    trimmed.startsWith("#") -> Unit
                    else -> {
                        val title = pendingTitle
                        if (title != null) {
                            val attrs = if (extraAttrs.isEmpty()) pendingAttrs else HashMap(pendingAttrs).apply {
                                extraAttrs.forEach { (k, v) -> putIfAbsent(k, v) }
                            }
                            batch += M3uEntry(title = title, url = trimmed, attributes = attrs, durationSeconds = pendingDuration)
                            count++
                            if (batch.size >= batchSize) {
                                emit(M3uParseEvent.Batch(ArrayList(batch), count))
                                batch.clear()
                            }
                        }
                        pendingTitle = null
                        pendingAttrs = emptyMap()
                        pendingDuration = null
                        extraAttrs.clear()
                    }
                }
                line = reader.readLineOrNull()
            }
            if (!headerEmitted) emit(M3uParseEvent.Header(null))
            if (batch.isNotEmpty()) emit(M3uParseEvent.Batch(ArrayList(batch), count))
            emit(M3uParseEvent.Done(count))
        }
    }.flowOn(Dispatchers.IO)

    private fun BufferedReader.readLineOrNull(): String? = readLine()

    /** Returns (title, attributes, duration). */
    private fun parseExtInf(line: String): Triple<String, Map<String, String>, Long?> {
        val body = line.substringAfter("#EXTINF:")
        val commaIdx = findTitleComma(body)
        val meta = if (commaIdx >= 0) body.substring(0, commaIdx) else body
        val title = if (commaIdx >= 0) body.substring(commaIdx + 1).trim() else ""
        val duration = meta.takeWhile { it == '-' || it.isDigit() || it == '.' }.toDoubleOrNull()?.toLong()?.takeIf { it > 0 }
        val attrs = parseAttributes(meta)
        val resolvedTitle = title.ifBlank { attrs["tvg-name"] ?: "Unknown" }
        return Triple(resolvedTitle, attrs, duration)
    }

    /** The title starts after the first comma that is outside of a quoted attribute value. */
    private fun findTitleComma(body: String): Int {
        var inQuotes = false
        body.forEachIndexed { i, c ->
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> return i
            }
        }
        return -1
    }

    private fun parseAttributes(text: String): Map<String, String> {
        val result = HashMap<String, String>()
        ATTR_REGEX.findAll(text).forEach { m ->
            val key = m.groupValues[1].lowercase()
            val value = m.groupValues[2].ifEmpty { m.groupValues[3] }
            result[key] = value
        }
        return result
    }

    companion object {
        const val DEFAULT_BATCH = 1000
        private val ATTR_REGEX = Regex("""([\w\-]+)=(?:"([^"]*)"|([^\s",]+))""")

        fun stableId(url: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            return buildString(24) { digest.take(12).forEach { append("%02x".format(it)) } }
        }
    }
}
