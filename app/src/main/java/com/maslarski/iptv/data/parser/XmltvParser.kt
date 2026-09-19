package com.maslarski.iptv.data.parser

import com.maslarski.iptv.data.local.entity.EpgProgramEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed interface XmltvEvent {
    data class Channels(val displayNames: Map<String, String>) : XmltvEvent
    data class Programs(val programs: List<EpgProgramEntity>, val parsedSoFar: Int) : XmltvEvent
    data class Done(val total: Int) : XmltvEvent
}

/** Streaming XMLTV parser (supports plain and gzip-compressed guides). */
@Singleton
class XmltvParser @Inject constructor() {

    fun parse(
        rawInput: InputStream,
        keepFrom: Long = System.currentTimeMillis() - 6 * 60 * 60 * 1000L,
        keepUntil: Long = System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000L,
        batchSize: Int = 2000,
    ): Flow<XmltvEvent> = flow {
        val input = maybeGunzip(rawInput.buffered())
        input.use { stream ->
            val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()
            parser.setInput(stream, null)

            val names = HashMap<String, String>()
            val batch = ArrayList<EpgProgramEntity>(batchSize)
            var total = 0
            var channelsEmitted = false

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "channel" -> {
                            val id = parser.getAttributeValue(null, "id")
                            val displayName = readChannelDisplayName(parser)
                            if (id != null && displayName != null) names[id] = displayName
                        }
                        "programme" -> {
                            if (!channelsEmitted) {
                                emit(XmltvEvent.Channels(names))
                                channelsEmitted = true
                            }
                            val program = readProgramme(parser)
                            if (program != null && program.endMillis > keepFrom && program.startMillis < keepUntil) {
                                batch += program
                                total++
                                if (batch.size >= batchSize) {
                                    emit(XmltvEvent.Programs(ArrayList(batch), total))
                                    batch.clear()
                                }
                            }
                        }
                    }
                }
                event = parser.next()
            }
            if (!channelsEmitted) emit(XmltvEvent.Channels(names))
            if (batch.isNotEmpty()) emit(XmltvEvent.Programs(ArrayList(batch), total))
            emit(XmltvEvent.Done(total))
        }
    }.flowOn(Dispatchers.IO)

    private fun maybeGunzip(input: InputStream): InputStream {
        input.mark(2)
        val b1 = input.read()
        val b2 = input.read()
        input.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(input) else input
    }

    private fun readChannelDisplayName(parser: XmlPullParser): String? {
        var name: String? = null
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    if (parser.name == "display-name" && name == null) {
                        name = parser.nextText().trim()
                        depth--
                    }
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return name
            }
        }
        return name
    }

    private fun readProgramme(parser: XmlPullParser): EpgProgramEntity? {
        val channel = parser.getAttributeValue(null, "channel") ?: return skipElement(parser)
        val start = parseXmltvTime(parser.getAttributeValue(null, "start"))
        val stop = parseXmltvTime(parser.getAttributeValue(null, "stop"))
        var title: String? = null
        var desc: String? = null
        var category: String? = null
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "title" -> if (title == null) title = parser.nextText().trim() else depth++
                        "desc" -> if (desc == null) desc = parser.nextText().trim() else depth++
                        "category" -> if (category == null) category = parser.nextText().trim() else depth++
                        else -> depth++
                    }
                }
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> depth = 0
            }
        }
        if (start == null || title.isNullOrBlank()) return null
        val end = stop ?: (start + 30 * 60 * 1000L)
        if (end <= start) return null
        return EpgProgramEntity(
            epgChannelId = channel,
            title = title,
            description = desc?.takeIf { it.isNotBlank() },
            startMillis = start,
            endMillis = end,
            category = category?.takeIf { it.isNotBlank() },
        )
    }

    private fun skipElement(parser: XmlPullParser): EpgProgramEntity? {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> depth = 0
            }
        }
        return null
    }

    companion object {
        private val BASE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

        /** Parses XMLTV timestamps such as `20240101123000 +0000` or `20240101123000`. */
        fun parseXmltvTime(value: String?): Long? {
            if (value.isNullOrBlank()) return null
            val trimmed = value.trim()
            val datePart = trimmed.takeWhile { it.isDigit() }.padEnd(14, '0').take(14)
            val offsetPart = trimmed.drop(trimmed.takeWhile { it.isDigit() }.length).trim()
            return runCatching {
                val local = LocalDateTime.parse(datePart, BASE)
                val offset = if (offsetPart.isEmpty()) ZoneOffset.UTC else parseOffset(offsetPart)
                local.toInstant(offset).toEpochMilli()
            }.getOrNull()
        }

        private fun parseOffset(text: String): ZoneOffset {
            val sign = if (text.startsWith("-")) -1 else 1
            val digits = text.trimStart('+', '-').filter { it.isDigit() }.padEnd(4, '0')
            val hours = digits.take(2).toInt()
            val minutes = digits.drop(2).take(2).toInt()
            return ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes)
        }
    }
}
