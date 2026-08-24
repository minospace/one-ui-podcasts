package be.miro.onecast.playback

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlaybackException
import be.miro.onecast.data.Episode
import be.miro.onecast.data.Podcast

/**
 * Whether the user is currently *watching* a video episode, and the rules for how much of the video
 * the player keeps loaded.
 *
 * An episode always starts as audio — a podcast is something you listen to until you say otherwise
 * — so this is deliberately transient state, shared in-process between the player screen (which
 * flips it) and [PlaybackService] (which clears it when the episode changes). It is never persisted.
 *
 * Two things follow from the choice:
 *  - **which file is loaded**, when the feed publishes the video as a separate file from the audio,
 *  - **whether the video track is enabled**, which is all there is to decide for the common case of
 *    a single video enclosure carrying both tracks.
 *
 * With "preload video" on (the default) both stay switched on the whole time, so showing the video
 * is just a matter of handing the player a surface. With it off, video is only loaded once the user
 * asks for it, which costs a re-buffer at that moment.
 */
object VideoMode {

    @Volatile
    private var showing = false

    /** Set when video broke for the current episode, so nothing loads it again on its own. */
    @Volatile
    private var videoFailed = false

    @Volatile
    private var failureUnreported = false

    val isShowing: Boolean get() = showing

    /** Load an episode from the start of a listening session: audio first, video per the setting. */
    fun load(
        player: Player,
        episode: Episode,
        podcast: Podcast?,
        startPositionMs: Long,
        preloadVideo: Boolean,
    ) {
        showing = false
        videoFailed = false
        failureUnreported = false
        val loadVideo = episode.hasVideo && preloadVideo
        setVideoTrackEnabled(player, loadVideo)
        player.setMediaItem(
            MediaItems.fromEpisode(episode, podcast, loadVideo),
            startPositionMs.coerceAtLeast(0),
        )
        player.prepare()
    }

    /** Start watching (or stop) whatever is loaded, loading the video part if it isn't already. */
    fun setShowing(player: Player, showing: Boolean, preloadVideo: Boolean) {
        this.showing = showing
        // Asking for video by hand is also a request to try again after it broke.
        if (showing) videoFailed = false
        apply(player, preloadVideo)
    }

    /** Back to audio — called when the player moves on to a different episode. */
    fun reset() {
        showing = false
    }

    /**
     * Keeps the episode playing when its video can't be played — video is an extra, and an
     * ExoPlayer error otherwise stops the episode outright, audio included. Drops back to audio
     * from the same position and stops loading video for this episode; the user can ask for it
     * again from the player.
     *
     * Two different failures count, because video can break in two places:
     *  - the **decoder** won't start (an unsupported profile, or no free codec on a busy device),
     *    which arrives as a renderer error naming the format it choked on;
     *  - the video **file** won't load (a dead link, a timeout, a geo-block), which arrives as a
     *    source error naming nothing. That one is only video's fault — and only recoverable — when
     *    the audio lives in a file of its own to fall back to, which is exactly what
     *    [MediaItems.withVideoSource] returning something to switch to tells us. Where one file
     *    carries both tracks it returns null, and a source error is the whole episode failing.
     *
     * Returns true when the error was video's doing and playback was restarted.
     */
    fun recoverFromError(player: Player, error: PlaybackException): Boolean {
        if (error !is ExoPlaybackException) return false
        val audioItem = player.currentMediaItem?.let { MediaItems.withVideoSource(it, false) }
        val videoBroke = when (error.type) {
            ExoPlaybackException.TYPE_RENDERER -> MimeTypes.isVideo(error.rendererFormat?.sampleMimeType)
            ExoPlaybackException.TYPE_SOURCE -> audioItem != null
            else -> false
        }
        if (!videoBroke) return false

        videoFailed = true
        // Only worth a word to the user if they were actually watching; a preloaded video that
        // never made it to the screen is nothing they asked for.
        failureUnreported = showing
        showing = false
        val position = player.currentPosition.coerceAtLeast(0)
        setVideoTrackEnabled(player, false)
        if (audioItem != null) player.setMediaItem(audioItem, position) else player.seekTo(position)
        player.prepare()
        return true
    }

    /** True once after video failed under the user's nose, so the player screen can say so. */
    fun consumeVideoFailure(): Boolean {
        if (!failureUnreported) return false
        failureUnreported = false
        return true
    }

    /**
     * Brings the player in line with the current choice: swaps to the video (or audio) file if the
     * feed publishes them separately, and enables or disables the video track. Playback continues
     * from where it was, in the same play/pause state.
     */
    private fun apply(player: Player, preloadVideo: Boolean) {
        val item = player.currentMediaItem ?: return
        if (!MediaItems.hasVideo(item)) return
        val loadVideo = (showing || preloadVideo) && !videoFailed
        // Null whenever both sources are the same file, which is the common case — then there's
        // nothing to reload and only the track selection below does any work.
        MediaItems.withVideoSource(item, loadVideo)?.let { swapped ->
            val position = player.currentPosition
            val playWhenReady = player.playWhenReady
            player.setMediaItem(swapped, position)
            player.prepare()
            player.playWhenReady = playWhenReady
        }
        setVideoTrackEnabled(player, loadVideo)
    }

    /**
     * Enabling the video track mid-episode makes ExoPlayer re-select tracks and re-buffer from the
     * current position — the pause the user is warned about when they turn preloading off. Skip the
     * write when nothing would change, so a repeated call can't cost a needless re-buffer.
     */
    private fun setVideoTrackEnabled(player: Player, enabled: Boolean) {
        val parameters = player.trackSelectionParameters
        val currentlyEnabled = !parameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO)
        if (currentlyEnabled == enabled) return
        player.trackSelectionParameters = parameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
            .build()
    }
}
