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
    private const val KEY_AUDIO_URI = "be.miro.onecast.AUDIO_URI"
    private const val KEY_VIDEO_URI = "be.miro.onecast.VIDEO_URI"

    /**
     * [loadVideo] picks which of the episode's two sources to play. They're usually the same file
     * (a video enclosure carries both tracks), in which case this changes nothing and only the
     * video *track* is switched on or off — see [VideoMode].
     */
    fun fromEpisode(episode: Episode, podcast: Podcast?, loadVideo: Boolean = false): MediaItem {
        val audioUri = audioSource(episode)
        val videoUri = videoSource(episode)
        val uri = (if (loadVideo) videoUri else null) ?: audioUri
        val metadata = MediaMetadata.Builder()
            .setTitle(episode.title)
            .setArtist(podcast?.title)
            .setArtworkUri((episode.imageUrl ?: podcast?.artworkUrl)?.let(Uri::parse))
            .setExtras(
                Bundle().apply {
                    // Carry the known duration so the seek bar can show the saved position
                    // immediately, before the stream loads and the player learns its real duration
                    // (otherwise the bar collapses to the start while the position text is correct).
                    putLong(KEY_DURATION_MS, episode.durationMs)
                    // Both sources travel with the item so the player screen can switch between
                    // them without going back to the database.
                    putString(KEY_AUDIO_URI, audioUri)
                    putString(KEY_VIDEO_URI, videoUri)
                },
            )
            .build()
        return MediaItem.Builder()
            .setMediaId(episode.id.toString())
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }

    /**
     * The same item pointing at its video (or audio) source, or null when that's the file already
     * loaded — which is the usual case, since one video enclosure serves both.
     */
    fun withVideoSource(item: MediaItem, video: Boolean): MediaItem? {
        val extras = item.mediaMetadata.extras ?: return null
        val audioUri = extras.getString(KEY_AUDIO_URI)
        val videoUri = extras.getString(KEY_VIDEO_URI)
        // One file serving both is checked first because it needs no URI comparison: a media item
        // that has travelled to a controller in another process arrives without its URI.
        if (audioUri == videoUri) return null
        val target = (if (video) videoUri else audioUri) ?: return null
        if (target == item.localConfiguration?.uri?.toString()) return null
        return item.buildUpon().setUri(target).build()
    }

    /** A downloaded episode plays from disk; anything else streams. */
    private fun localFile(episode: Episode): String? {
        val path = episode.downloadPath ?: return null
        val file = File(path)
        return if (file.exists()) Uri.fromFile(file).toString() else null
    }

    private fun audioSource(episode: Episode): String = localFile(episode) ?: episode.audioUrl

    /**
     * Where video comes from: the downloaded file when the user chose to download the video
     * version, otherwise the stream. An audio-only download of a video episode still streams video.
     */
    private fun videoSource(episode: Episode): String? = when {
        episode.downloadHasVideo -> localFile(episode) ?: episode.videoUrl
        else -> episode.videoUrl
    }

    fun episodeId(mediaItem: MediaItem?): Long? = mediaItem?.mediaId?.toLongOrNull()

    /** The duration baked into the media item at build time, or 0 if unknown. */
    fun durationMs(mediaItem: MediaItem?): Long =
        mediaItem?.mediaMetadata?.extras?.getLong(KEY_DURATION_MS, 0L) ?: 0L

    /** True when this episode can be watched, not just listened to. */
    fun hasVideo(mediaItem: MediaItem?): Boolean =
        mediaItem?.mediaMetadata?.extras?.getString(KEY_VIDEO_URI) != null
}
