package be.miro.onecast.playback

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import be.miro.onecast.OnecastApp
import be.miro.onecast.widget.WidgetState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Background audio playback via Media3. Media3 supplies the media notification and
 * lock-screen controls automatically; this service adds podcast-specific behaviour:
 * persisting the resume position and auto-marking an episode played when it finishes.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var progressJob: Job? = null

    private val repository get() = (application as OnecastApp).repository
    private val settings get() = (application as OnecastApp).settings

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        // Podcast audio URLs almost always redirect through tracking/CDN hosts,
        // often across http<->https. Allow that and send a User-Agent CDNs accept.
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("OnecastApp/1.0 (Android)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)
        // Wrapped so the tracking-redirect chain is walked once per URL rather than on every open
        // (see RedirectCachingDataSource) — that chain is most of the wait before the first sound.
        val mediaSourceFactory = DefaultMediaSourceFactory(
            RedirectCachingDataSource.Factory(
                DefaultDataSource.Factory(this, httpDataSourceFactory),
            ),
        )

        // Start playback as soon as a small amount of audio is buffered instead of the Media3
        // default 2.5s. Podcasts are low-bitrate speech, so half a second of audio is a few KB —
        // it arrives in the first packets after the connection is up, and waiting for more just
        // adds dead time to every start. Keep the (generous) steady-state buffer so playback stays
        // smooth once it's going, and refill more before resuming from an actual stall.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                /* bufferForPlaybackMs = */ 500,
                /* bufferForPlaybackAfterRebufferMs = */ 2_000,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            // The user-configured skip amounts drive the notification / lock-screen seek buttons.
            // The in-app controls seek by the latest value directly (see PlayerConnection), so they
            // stay in sync even while this (long-lived) service keeps the increments it was built with.
            .setSeekBackIncrementMs(settings.rewindSeconds * 1000L)
            .setSeekForwardIncrementMs(settings.forwardSeconds * 1000L)
            .build()

        player.addListener(playerListener)
        // Wrap so a finished episode replays from the start when the user hits play again; a raw
        // Media3 player ignores play() in STATE_ENDED, which otherwise leaves every play button
        // (full player, mini-player, widget, notification) dead once an episode reaches the end.
        session = MediaSession.Builder(this, ReplayWhenEndedPlayer(player)).build()
        restoreLastEpisode(player)
        startProgressLoop()
    }

    /**
     * Reloads the episode that was loaded when the service was last torn down, paused at its saved
     * position. Because this service only runs in the foreground while actually playing, an episode
     * that was picked but never played is otherwise lost as soon as the app is backgrounded.
     */
    private fun restoreLastEpisode(player: Player) {
        val episodeId = settings.lastEpisodeId
        if (episodeId < 0 || player.currentMediaItem != null) return
        scope.launch {
            val episode = repository.getEpisode(episodeId) ?: return@launch
            // The user may have started something else while we were loading from the DB.
            if (player.currentMediaItem != null) return@launch
            val podcast = repository.getPodcast(episode.podcastId)
            val startAt = if (episode.isPlayed) 0L else episode.positionMs
            VideoMode.load(player, episode, podcast, startAt, settings.preloadVideo)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        progressJob?.cancel()
        session?.let { saveCurrentPosition(it.player) }
        session?.run {
            player.removeListener(playerListener)
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val episodeId = MediaItems.episodeId(mediaItem) ?: -1L
            // A new episode is always listened to, never watched. Guarded on the id because
            // switching the *same* episode between its audio and video file also lands here.
            if (episodeId != settings.lastEpisodeId) VideoMode.reset()
            // Remember what's loaded so it survives the service being killed while paused.
            settings.lastEpisodeId = episodeId
            // Unloaded rather than swapped — the episode was deleted out from under the player.
            // Nothing else pushes widget state for a null item, so the widget would otherwise keep
            // offering to play something that no longer exists.
            if (mediaItem == null) {
                WidgetState.clear(applicationContext)
                WidgetState.notifyWidgets(applicationContext)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // Video is an extra, not the episode: if its decoder fails, keep the audio going.
            session?.player?.let { VideoMode.recoverFromError(it, error) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            session?.player?.let { saveCurrentPosition(it); pushWidgetState(it) }
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            session?.player?.let { pushWidgetState(it) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED) return
            val player = session?.player ?: return
            val episodeId = MediaItems.episodeId(player.currentMediaItem) ?: return
            scope.launch {
                repository.setPlayed(episodeId, true)
                advanceToQueuedEpisode(player)
            }
        }
    }

    /**
     * Autoplay the next episode from the Up Next queue once the current one finishes. With an empty
     * queue nothing loads, so the finished item stays put and [ReplayWhenEndedPlayer] can replay it.
     */
    private suspend fun advanceToQueuedEpisode(player: Player) {
        // The user may have loaded something else between the episode ending and this running; only
        // follow the queue while the player is still parked at the end of the finished item. Read
        // everything first and re-check right before popping, so a fresh choice isn't overridden and
        // the queue isn't disturbed when we bail.
        if (player.playbackState != Player.STATE_ENDED) return
        val nextId = repository.peekNextId() ?: return
        val next = repository.getEpisode(nextId) ?: return
        val podcast = repository.getPodcast(next.podcastId)
        if (player.playbackState != Player.STATE_ENDED) return
        repository.removeFromQueue(nextId)
        val startAt = if (next.isPlayed) 0L else next.positionMs
        VideoMode.load(player, next, podcast, startAt, settings.preloadVideo)
        player.play()
    }

    /** Mirrors the player's current episode + playing state for the home-screen widget. */
    private fun pushWidgetState(player: Player) {
        val item = player.currentMediaItem ?: return
        val episodeId = MediaItems.episodeId(item) ?: return
        val metadata = player.mediaMetadata
        WidgetState.update(
            context = applicationContext,
            episodeId = episodeId,
            title = metadata.title?.toString() ?: "",
            podcastTitle = metadata.artist?.toString() ?: "",
            artworkUrl = metadata.artworkUri?.toString(),
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition,
            durationMs = player.duration.takeIf { it > 0 } ?: 0L,
        )
        WidgetState.notifyWidgets(applicationContext)
    }

    private fun startProgressLoop() {
        progressJob = scope.launch {
            while (isActive) {
                delay(PROGRESS_SAVE_INTERVAL_MS)
                session?.player?.let {
                    if (it.isPlaying) {
                        saveCurrentPosition(it)
                        // Keep the home-screen widget's progress bar advancing while playing.
                        pushWidgetState(it)
                    }
                }
            }
        }
    }

    /** Reads playback position on the player thread, then persists off the main thread. */
    private fun saveCurrentPosition(player: Player) {
        val item: MediaItem = player.currentMediaItem ?: return
        val episodeId = MediaItems.episodeId(item) ?: return
        // A finished episode should resume from the start, not its very end. setPlayed (on
        // STATE_ENDED) clears this too, but the two writes race, so make this one authoritative.
        val position = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        scope.launch {
            repository.savePosition(episodeId, position)
            if (duration > 0) repository.saveDurationIfUnknown(episodeId, duration)
        }
    }

    companion object {
        private const val PROGRESS_SAVE_INTERVAL_MS = 5_000L
    }
}

/**
 * Makes "play" replay a finished episode instead of doing nothing. A Media3 [Player] ignores
 * `play()` / `setPlayWhenReady(true)` while in [Player.STATE_ENDED], so seek back to the start
 * first. Both entry points are overridden because the session, notification and app controls
 * reach the player through different ones.
 */
private class ReplayWhenEndedPlayer(player: Player) : ForwardingPlayer(player) {
    override fun play() {
        restartIfEnded()
        super.play()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) restartIfEnded()
        super.setPlayWhenReady(playWhenReady)
    }

    private fun restartIfEnded() {
        if (playbackState == Player.STATE_ENDED && mediaItemCount > 0) seekToDefaultPosition()
    }
}
