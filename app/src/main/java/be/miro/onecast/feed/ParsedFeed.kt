package be.miro.onecast.feed

import be.miro.onecast.data.Chapter

/** Plain result of parsing an RSS feed, independent of the database layer. */
data class ParsedFeed(
    val title: String?,
    val author: String?,
    val description: String?,
    val imageUrl: String?,
    val episodes: List<ParsedEpisode>,
)

data class ParsedEpisode(
    val guid: String,
    val title: String,
    val description: String?,
    /** Null for a video-only item, which has no separate audio file. */
    val audioUrl: String?,
    /** The video file, when the feed publishes one. May be the same file as [audioUrl]. */
    val videoUrl: String?,
    val pubDate: Long,
    val durationMs: Long,
    val imageUrl: String?,
    /** Inline Podlove Simple Chapters, if present. */
    val chapters: List<Chapter> = emptyList(),
    /** Podcasting 2.0 JSON chapters URL, if present. */
    val chaptersUrl: String? = null,
)
