package be.miro.onecast.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import be.miro.onecast.data.Episode
import be.miro.onecast.data.Podcast
import java.io.File

/** Builds Media3 [MediaItem]s, carrying the episode id as the media id. */
object MediaItems {

    private const val KEY_DURATION_MS = "be.miro.onecast.DURATION_MS"

    fun fromEpisode(episode: Episode, podcast: Podcast?): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(episode.title)
            .setArtist(podcast?.title)
            .setArtworkUri((episode.imageUrl ?: podcast?.artworkUrl)?.let(Uri::parse))
            // Carry the known duration so the seek bar can show the saved position immediately,
            // before the stream loads and the player learns its real duration (otherwise the bar
            // collapses to the start while the position text is already correct).
            .setExtras(Bundle().apply { putLong(KEY_DURATION_MS, episode.durationMs) })
            .build()
        return MediaItem.Builder()
            .setMediaId(episode.id.toString())
            .setUri(localUri(episode) ?: Uri.parse(episode.audioUrl))
            .setMediaMetadata(metadata)
            .build()
    }

    /** A downloaded episode plays from disk; anything else streams. */
    private fun localUri(episode: Episode): Uri? {
        val path = episode.downloadPath ?: return null
        val file = File(path)
        return if (file.exists()) Uri.fromFile(file) else null
    }

    fun episodeId(mediaItem: MediaItem?): Long? = mediaItem?.mediaId?.toLongOrNull()

    /** The duration baked into the media item at build time, or 0 if unknown. */
    fun durationMs(mediaItem: MediaItem?): Long =
        mediaItem?.mediaMetadata?.extras?.getLong(KEY_DURATION_MS, 0L) ?: 0L
}
