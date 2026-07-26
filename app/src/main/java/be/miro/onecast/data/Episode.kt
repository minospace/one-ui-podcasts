package be.miro.onecast.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A single episode belonging to a [Podcast]. */
@Entity(
    tableName = "episodes",
    foreignKeys = [
        ForeignKey(
            entity = Podcast::class,
            parentColumns = ["id"],
            childColumns = ["podcastId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["podcastId"]),
        // Dedup key: the same feed item is never inserted twice.
        Index(value = ["podcastId", "guid"], unique = true),
    ],
)
data class Episode(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val podcastId: Long,
    val guid: String,
    val title: String,
    val description: String? = null,
    val audioUrl: String,
    val pubDate: Long = 0,
    val durationMs: Long = 0,
    val isPlayed: Boolean = false,
    /** Resume position in milliseconds. */
    val positionMs: Long = 0,
    /** Per-episode artwork (itunes:image on the item); falls back to the podcast art when null. */
    val imageUrl: String? = null,
    /** Chapter markers (inline PSC chapters, or fetched from [chaptersUrl]). */
    val chapters: List<Chapter> = emptyList(),
    /** Podcasting 2.0 JSON chapters URL, fetched lazily when none are inline. */
    val chaptersUrl: String? = null,
    /**
     * Absolute path of the downloaded audio file, or null when the episode isn't downloaded.
     * Only ever set by an explicit user download — nothing downloads on its own.
     */
    val downloadPath: String? = null,
    /** Size on disk of [downloadPath], in bytes. */
    val downloadSizeBytes: Long = 0,
    /** When the download finished (epoch millis) — newest first in the Downloads list. */
    val downloadedAt: Long = 0,
)
