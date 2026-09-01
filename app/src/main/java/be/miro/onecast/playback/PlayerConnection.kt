package be.miro.onecast.playback

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import be.miro.onecast.appSettings
import be.miro.onecast.data.Episode
import be.miro.onecast.data.Podcast
import com.google.common.util.concurrent.ListenableFuture

/**
 * Lifecycle-aware bridge to the [PlaybackService] via a Media3 [MediaController].
 * Connects on START, disconnects on STOP, and calls [onUpdate] on every player
 * event plus a steady progress tick so the UI can refresh the seek bar.
 */
class PlayerConnection(
    private val context: Context,
    lifecycle: Lifecycle,
) : DefaultLifecycleObserver {

    private var future: ListenableFuture<MediaController>? = null

    var controller: MediaController? = null
        private set

    /** Called on player state changes and ~2x/second while connected. */
    var onUpdate: (() -> Unit)? = null

    /**
     * A tap that arrives before the service has finished binding — most likely on the very first
     * play after a cold start, which is exactly when the connection is slowest. Held here and run
     * on connect instead of being dropped, so the episode doesn't need a second tap.
     */
    private var pendingAction: ((MediaController) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            onUpdate?.invoke()
            handler.postDelayed(this, PROGRESS_TICK_MS)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            onUpdate?.invoke()
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener(
            {
                controller = runCatching { f.get() }.getOrNull()?.also {
                    it.addListener(playerListener)
                    pendingAction?.invoke(it)
                }
                pendingAction = null
                onUpdate?.invoke()
                handler.post(ticker)
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    override fun onStop(owner: LifecycleOwner) {
        handler.removeCallbacks(ticker)
        pendingAction = null
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
    }

    /** Start an episode. It always starts as audio; see [VideoMode] for what happens to the video. */
    fun loadEpisode(episode: Episode, podcast: Podcast?, startPositionMs: Long) = withController { c ->
        VideoMode.load(c, episode, podcast, startPositionMs, context.appSettings.preloadVideo)
    }

    /** Switch the current episode between watching and listening. */
    fun setVideoShowing(showing: Boolean) {
        val c = controller ?: return
        VideoMode.setShowing(c, showing, context.appSettings.preloadVideo)
    }

    /**
     * Hand the player somewhere to draw. A [TextureView] rather than a SurfaceView because the
     * player screen is a sheet the user can drag and fade away, which a surface wouldn't follow.
     */
    fun attachVideo(view: TextureView) {
        controller?.setVideoTextureView(view)
    }

    fun detachVideo(view: TextureView) {
        controller?.clearVideoTextureView(view)
    }

    /** True if the controller is currently playing the given episode id. */
    fun isCurrent(episodeId: Long): Boolean =
        controller?.currentMediaItem?.mediaId == episodeId.toString()

    /** Unload whatever is playing. Used when the episode's file is about to be deleted. */
    fun clear() {
        val c = controller ?: return
        c.pause()
        c.clearMediaItems()
    }

    fun togglePlayPause() = withController { c ->
        if (c.isPlaying) c.pause() else c.play()
    }

    /** Runs [action] now, or as soon as the controller connects (see [pendingAction]). */
    private fun withController(action: (MediaController) -> Unit) {
        val c = controller
        if (c != null) action(c) else pendingAction = action
    }

    // Seek by the user's current skip amounts directly (rather than the player's built-in
    // increments, which are fixed when the service is built) so changes apply without a restart.
    fun seekBack() {
        val c = controller ?: return
        val amount = context.appSettings.rewindSeconds * 1000L
        c.seekTo((c.currentPosition - amount).coerceAtLeast(0))
    }

    fun seekForward() {
        val c = controller ?: return
        val amount = context.appSettings.forwardSeconds * 1000L
        val target = c.currentPosition + amount
        val duration = c.duration
        c.seekTo(if (duration > 0) target.coerceAtMost(duration) else target)
    }
    fun seekTo(positionMs: Long) = controller?.seekTo(positionMs) ?: Unit
    fun setSpeed(speed: Float) = controller?.setPlaybackSpeed(speed) ?: Unit

    private companion object {
        const val PROGRESS_TICK_MS = 500L
    }
}
