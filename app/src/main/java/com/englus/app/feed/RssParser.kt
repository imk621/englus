package com.englus.app.feed

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

/** What a feed advertises, before anything is stored. */
data class ParsedFeed(
    val title: String,
    val imageUrl: String?,
    val items: List<ParsedItem>,
)

data class ParsedItem(
    val guid: String,
    val title: String,
    val audioUrl: String,
    val publishedAt: Long,
    val durationSec: Int,
    val sizeBytes: Long,
)

/**
 * RSS 2.0 / iTunes podcast feeds, via the platform pull parser — no dependency
 * and no DOM for a feed that can list hundreds of episodes.
 *
 * Namespace processing is deliberately off so `itunes:duration` arrives as a
 * plain tag name; feeds are inconsistent about declaring that namespace and
 * treating the prefix as literal text is what actually works across them.
 */
object RssParser {

    class ParseException(message: String) : Exception(message)

    fun parse(input: InputStream): ParsedFeed {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var channelTitle: String? = null
        var imageUrl: String? = null
        val items = mutableListOf<ParsedItem>()

        var inItem = false
        var itemTitle: String? = null
        var guid: String? = null
        var audioUrl: String? = null
        var pubDate: String? = null
        var duration: String? = null
        var size = 0L
        // <image><url>…</url></image> is the RSS 2.0 form; itunes:image is the
        // podcast one. Both appear, so track which element we are inside rather
        // than counting depth — nextText() consumes end tags and makes any depth
        // counter drift.
        var inImage = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "image" -> inImage = true
                    "item" -> {
                        inItem = true
                        itemTitle = null; guid = null; audioUrl = null
                        pubDate = null; duration = null; size = 0L
                    }
                    "title" -> {
                        val text = parser.nextText().trim()
                        when {
                            inItem -> itemTitle = text
                            // <image> carries its own <title>; it is not the feed's.
                            inImage -> Unit
                            channelTitle == null -> channelTitle = text
                        }
                    }
                    "guid" -> if (inItem) guid = parser.nextText().trim()
                    "pubdate" -> if (inItem) pubDate = parser.nextText().trim()
                    "itunes:duration" -> if (inItem) duration = parser.nextText().trim()
                    "enclosure" -> if (inItem) {
                        val type = parser.getAttributeValue(null, "type").orEmpty()
                        val url = parser.getAttributeValue(null, "url")
                        // Feeds also enclose images and transcripts; take audio only.
                        if (!url.isNullOrBlank() && (type.startsWith("audio") || type.isEmpty())) {
                            audioUrl = url
                            size = parser.getAttributeValue(null, "length")?.toLongOrNull() ?: 0L
                        }
                    }
                    "itunes:image" -> if (!inItem && imageUrl == null) {
                        imageUrl = parser.getAttributeValue(null, "href")
                    }
                    "url" -> if (inImage && imageUrl == null) {
                        imageUrl = parser.nextText().trim()
                    }
                    else -> Unit
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("image", ignoreCase = true)) inImage = false
                    if (parser.name.equals("item", ignoreCase = true)) {
                        val url = audioUrl
                        if (url != null) {
                            items += ParsedItem(
                                // Not every feed has a guid; the audio URL is the
                                // next best stable identity for dedup on refresh.
                                guid = guid?.takeIf { it.isNotBlank() } ?: url,
                                title = itemTitle.orEmpty().ifBlank { "(제목 없음)" },
                                audioUrl = url,
                                publishedAt = parseDate(pubDate),
                                durationSec = parseDuration(duration),
                                sizeBytes = size,
                            )
                        }
                        inItem = false
                    }
                }
            }
        }

        val title = channelTitle?.takeIf { it.isNotBlank() }
            ?: throw ParseException("피드에서 제목을 찾을 수 없습니다")
        if (items.isEmpty()) throw ParseException("피드에 오디오 항목이 없습니다")
        return ParsedFeed(title, imageUrl, items)
    }

    /** "2408", "40:08" and "1:02:03" all appear in the wild. */
    internal fun parseDuration(raw: String?): Int {
        if (raw.isNullOrBlank()) return 0
        val parts = raw.split(":")
        return runCatching {
            when (parts.size) {
                1 -> parts[0].trim().toDouble().toInt()
                2 -> parts[0].trim().toInt() * 60 + parts[1].trim().toInt()
                3 -> parts[0].trim().toInt() * 3600 + parts[1].trim().toInt() * 60 + parts[2].trim().toInt()
                else -> 0
            }
        }.getOrDefault(0)
    }

    private val DATE_FORMATS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "dd MMM yyyy HH:mm:ss Z",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
    )

    internal fun parseDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        DATE_FORMATS.forEach { pattern ->
            runCatching {
                return SimpleDateFormat(pattern, Locale.US).parse(raw)!!.time
            }
        }
        return 0L
    }
}
