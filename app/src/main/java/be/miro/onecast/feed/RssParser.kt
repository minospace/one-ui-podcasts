package be.miro.onecast.feed

import android.util.Xml
import be.miro.onecast.data.Chapter
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Minimal RSS 2.0 / iTunes podcast feed parser built on the platform pull parser.
 * Namespace processing is left off so prefixed tags arrive verbatim (e.g. "itunes:duration").
 */
class RssParser {

    fun parse(input: InputStream): ParsedFeed {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var channelTitle: String? = null
        var channelAuthor: String? = null
        var channelDescription: String? = null
        var channelImage: String? = null

        val episodes = mutableListOf<ParsedEpisode>()

        var insideItem = false
        var itTitle: String? = null
        var itGuid: String? = null
        var itDesc: String? = null
        var itAudio: String? = null
        var itVideo: String? = null
        var itPubDate = 0L
        var itDuration = 0L
        var itImage: String? = null
        var itChaptersUrl: String? = null
        val itChapters = mutableListOf<Chapter>()
        // The mime type of the <podcast:alternateEnclosure> currently open, if any: its URL lives in
        // a nested <podcast:source>, so the type has to be carried across to that tag.
        var altEnclosureType: String? = null

        var text = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val name: String? = parser.name
            when (event) {
                XmlPullParser.START_TAG -> {
                    text = StringBuilder()
                    when {
                        name.equals("item", true) -> {
                            insideItem = true
                            itTitle = null; itGuid = null; itDesc = null; itAudio = null
                            itVideo = null; itPubDate = 0L; itDuration = 0L; itImage = null
                            itChaptersUrl = null; itChapters.clear(); altEnclosureType = null
                        }
                        name.equals("enclosure", true) && insideItem -> {
                            val url = parser.getAttributeValue(null, "url")
                            if (url != null) {
                                val type = parser.getAttributeValue(null, "type")
                                if (isVideo(type, url)) {
                                    if (itVideo == null) itVideo = url
                                } else if (itAudio == null && isAudio(type, url)) {
                                    itAudio = url
                                }
                            }
                        }
                        // Podcasting 2.0: extra renditions of the same episode (e.g. a video cut
                        // alongside the audio enclosure), each with its URL in a nested <source>.
                        name.equals("podcast:alternateEnclosure", true) && insideItem -> {
                            altEnclosureType = parser.getAttributeValue(null, "type").orEmpty()
                        }
                        name.equals("podcast:source", true) && altEnclosureType != null -> {
                            // One alternate enclosure can list the same file several ways (https,
                            // IPFS, torrent…); only a plain web URL is something the player can open.
                            val url = parser.getAttributeValue(null, "uri")
                                ?.takeIf { it.startsWith("http", true) }
                            if (url != null) {
                                if (isVideo(altEnclosureType, url)) {
                                    if (itVideo == null) itVideo = url
                                } else if (itAudio == null && isAudio(altEnclosureType, url)) {
                                    // An audio rendition of a video episode: worth having, so
                                    // listening (and downloading to listen) needn't carry video.
                                    itAudio = url
                                }
                            }
                        }
                        // Media RSS, which some video podcasts use instead of a video enclosure.
                        name.equals("media:content", true) && insideItem -> {
                            val url = parser.getAttributeValue(null, "url")
                            val medium = parser.getAttributeValue(null, "medium")
                            val type = parser.getAttributeValue(null, "type")
                            if (url != null && itVideo == null && !medium.equals("audio", true) &&
                                (medium.equals("video", true) || isVideo(type, url))
                            ) {
                                itVideo = url
                            }
                        }
                        name.equals("itunes:image", true) -> {
                            val href = parser.getAttributeValue(null, "href")
                            if (href != null) {
                                if (insideItem) itImage = href
                                else if (channelImage == null) channelImage = href
                            }
                        }
                        // Podcasting 2.0: a link to an external JSON chapters file.
                        name.equals("podcast:chapters", true) && insideItem -> {
                            val url = parser.getAttributeValue(null, "url")
                            val type = parser.getAttributeValue(null, "type")
                            if (url != null && (type == null || type.contains("json", true))) {
                                itChaptersUrl = url
                            }
                        }
                        // Podlove Simple Chapters: inline, self-closing chapter elements.
                        name.equals("psc:chapter", true) && insideItem -> {
                            val start = parser.getAttributeValue(null, "start")
                            val chapterTitle = parser.getAttributeValue(null, "title")
                            if (start != null && !chapterTitle.isNullOrBlank()) {
                                itChapters.add(
                                    Chapter(
                                        startMs = parseTimecode(start),
                                        title = chapterTitle.trim(),
                                        imageUrl = parser.getAttributeValue(null, "image"),
                                        url = parser.getAttributeValue(null, "href"),
                                    ),
                                )
                            }
                        }
                    }
                }

                XmlPullParser.TEXT -> text.append(parser.text)

                XmlPullParser.END_TAG -> {
                    val value = text.toString().trim()
                    if (insideItem) {
                        when {
                            name.equals("podcast:alternateEnclosure", true) -> altEnclosureType = null
                            name.equals("item", true) -> {
                                val audio = itAudio
                                val video = itVideo
                                // A video-only item still counts: it plays as audio until the user
                                // switches to video.
                                if (audio != null || video != null) {
                                    episodes.add(
                                        ParsedEpisode(
                                            guid = itGuid ?: audio ?: video!!,
                                            title = itTitle ?: "(untitled)",
                                            description = itDesc,
                                            audioUrl = audio,
                                            videoUrl = video,
                                            pubDate = itPubDate,
                                            durationMs = itDuration,
                                            imageUrl = itImage,
                                            chapters = itChapters.sortedBy { it.startMs },
                                            chaptersUrl = itChaptersUrl,
                                        ),
                                    )
                                }
                                insideItem = false
                            }
                            name.equals("title", true) -> if (value.isNotBlank()) itTitle = value
                            name.equals("guid", true) -> if (value.isNotBlank()) itGuid = value
                            name.equals("pubDate", true) -> itPubDate = parseDate(value)
                            name.equals("itunes:duration", true) -> itDuration = parseDuration(value)
                            name.equals("content:encoded", true) -> if (value.isNotBlank()) itDesc = value
                            name.equals("description", true) -> if (itDesc == null && value.isNotBlank()) itDesc = value
                            name.equals("itunes:summary", true) -> if (itDesc == null && value.isNotBlank()) itDesc = value
                        }
                    } else {
                        when {
                            name.equals("title", true) -> if (channelTitle == null && value.isNotBlank()) channelTitle = value
                            name.equals("itunes:author", true) -> if (channelAuthor == null && value.isNotBlank()) channelAuthor = value
                            name.equals("description", true) -> if (channelDescription == null && value.isNotBlank()) channelDescription = value
                            name.equals("itunes:summary", true) -> if (channelDescription == null && value.isNotBlank()) channelDescription = value
                            // <image><url>…</url></image> at channel level
                            name.equals("url", true) -> if (channelImage == null && value.isNotBlank()) channelImage = value
                        }
                    }
                }
            }
            event = parser.next()
        }

        return ParsedFeed(channelTitle, channelAuthor, channelDescription, channelImage, episodes)
    }

    /**
     * Whether a media URL points at video. The feed's mime type decides when it says something
     * useful; plenty of feeds send nothing (or `application/octet-stream`), so fall back to the
     * file extension.
     */
    private fun isVideo(type: String?, url: String): Boolean = when {
        type.isNullOrBlank() -> hasVideoExtension(url)
        type.startsWith("video", true) -> true
        type.startsWith("audio", true) -> false
        else -> hasVideoExtension(url)
    }

    /**
     * Whether a media URL points at the episode's audio. Deliberately a test in its own right and
     * not merely "isn't video": an item can carry enclosures that are neither — a transcript, a
     * chapters file — and taking one of those as the audio costs the episode its real enclosure
     * (the first URL wins) and leaves it unplayable. An untyped enclosure is still taken at face
     * value, since plenty of feeds ship the episode itself with no type at all.
     */
    private fun isAudio(type: String?, url: String): Boolean = when {
        type.isNullOrBlank() -> true
        type.startsWith("audio", true) -> true
        type.startsWith("video", true) -> false
        else -> hasAudioExtension(url)
    }

    private fun hasVideoExtension(url: String): Boolean = extensionOf(url) in VIDEO_EXTENSIONS

    private fun hasAudioExtension(url: String): Boolean = extensionOf(url) in AUDIO_EXTENSIONS

    private fun extensionOf(url: String): String =
        url.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()

    private fun parseDate(value: String): Long {
        if (value.isBlank()) return 0
        for (pattern in DATE_PATTERNS) {
            try {
                return SimpleDateFormat(pattern, Locale.US).parse(value)?.time ?: continue
            } catch (_: ParseException) {
                // try next pattern
            }
        }
        return 0
    }

    private fun parseDuration(value: String): Long {
        val v = value.trim()
        if (v.isBlank()) return 0
        return try {
            if (v.contains(":")) {
                val parts = v.split(":").map { it.trim().toLong() }
                val seconds = when (parts.size) {
                    3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                    2 -> parts[0] * 60 + parts[1]
                    1 -> parts[0]
                    else -> 0L
                }
                seconds * 1000
            } else {
                (v.toDouble() * 1000).toLong()
            }
        } catch (_: NumberFormatException) {
            0
        }
    }

    /** PSC start codes: "HH:MM:SS.mmm", "MM:SS", or plain (fractional) seconds → ms. */
    private fun parseTimecode(value: String): Long {
        val v = value.trim()
        if (v.isBlank()) return 0
        return try {
            var seconds = 0.0
            for (part in v.split(":")) seconds = seconds * 60 + part.toDouble()
            (seconds * 1000).toLong()
        } catch (_: NumberFormatException) {
            0
        }
    }

    private companion object {
        val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "webm", "mkv", "avi", "mpg", "mpeg")

        // Only consulted for an enclosure whose type says nothing useful (octet-stream and friends).
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "wma")

        val DATE_PATTERNS = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm Z",
            "dd MMM yyyy HH:mm:ss Z",
        )
    }
}
