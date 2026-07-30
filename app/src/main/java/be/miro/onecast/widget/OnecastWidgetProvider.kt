package be.miro.onecast.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import be.miro.onecast.R
import be.miro.onecast.playback.PlaybackService
import be.miro.onecast.ui.Format
import be.miro.onecast.ui.MainActivity
import be.miro.onecast.ui.player.PlayerActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Compact home-screen widget: artwork, current/last episode, play/pause + skip-forward. */
class OnecastWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refreshWidgets(context, manager, ids)
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE -> handleAction(context) { if (it.isPlaying) it.pause() else it.play() }
            ACTION_SKIP_FORWARD -> handleAction(context) { it.seekForward() }
            ACTION_SKIP_BACK -> handleAction(context) { it.seekBack() }
            else -> super.onReceive(context, intent)
        }
    }

    /**
     * Connects a one-shot [MediaController] to issue a single command. [goAsync] extends the
     * receiver's lifetime past `onReceive` returning, since the connection is asynchronous; a
     * timeout guards against a hung connection leaking the pending result.
     */
    private fun handleAction(context: Context, action: (MediaController) -> Unit) {
        val pendingResult = goAsync()
        // A BroadcastReceiver's own Context is bind-restricted; MediaController.buildAsync()
        // binds to PlaybackService internally, so it needs the real application Context.
        val appContext = context.applicationContext
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        val timeoutHandler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            future.cancel(false)
            pendingResult.finish()
        }
        timeoutHandler.postDelayed(timeoutRunnable, ACTION_TIMEOUT_MS)
        future.addListener(
            {
                timeoutHandler.removeCallbacks(timeoutRunnable)
                runCatching { future.get() }.getOrNull()?.let { controller ->
                    action(controller)
                    controller.release()
                }
                pendingResult.finish()
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    companion object {
        const val ACTION_TOGGLE = "be.miro.onecast.widget.ACTION_TOGGLE"
        const val ACTION_SKIP_FORWARD = "be.miro.onecast.widget.ACTION_SKIP_FORWARD"
        const val ACTION_SKIP_BACK = "be.miro.onecast.widget.ACTION_SKIP_BACK"
        private const val ACTION_TIMEOUT_MS = 5_000L
        private const val ART_SIZE_PX = 220
        // ~20% of the art size, matching the 12dp corners of the 60dp placeholder behind it.
        private const val ART_CORNER_RADIUS_PX = 44
        // Inset applied to the artwork slot only in the empty state, so the Onecast mark doesn't
        // touch the rounded edges; real artwork fills the slot edge-to-edge (padding 0).
        private const val EMPTY_ART_PADDING_DP = 10

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // The service pushes a widget refresh every ~5s while playing (progress ticks), but the
        // artwork itself rarely changes. Cache the last bitmap so those refreshes reuse it instead
        // of blanking the art and re-fetching it from Glide every time, which caused a visible
        // flicker loop on the home-screen widget for as long as something was playing.
        @Volatile
        private var cachedArtwork: Pair<String, Bitmap>? = null

        fun refreshWidgets(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val state = WidgetState.read(context)
            val cached = cachedArtwork
            val artwork = if (state?.artworkUrl != null && cached?.first == state.artworkUrl) {
                cached.second
            } else {
                null
            }
            for (id in ids) {
                manager.updateAppWidget(id, buildViews(context, state, artwork))
            }
            if (state?.artworkUrl != null && artwork == null) {
                loadArtworkAndUpdate(context, manager, ids, state)
            }
        }

        private fun loadArtworkAndUpdate(
            context: Context,
            manager: AppWidgetManager,
            ids: IntArray,
            state: WidgetState,
        ) {
            val artworkUrl = state.artworkUrl ?: return
            scope.launch {
                val bitmap = runCatching {
                    Glide.with(context.applicationContext)
                        .asBitmap()
                        .load(artworkUrl)
                        .transform(RoundedCorners(ART_CORNER_RADIUS_PX))
                        .submit(ART_SIZE_PX, ART_SIZE_PX)
                        .get()
                }.getOrNull()
                if (bitmap != null) {
                    cachedArtwork = artworkUrl to bitmap
                    for (id in ids) manager.updateAppWidget(id, buildViews(context, state, bitmap))
                }
            }
        }

        private fun buildViews(context: Context, state: WidgetState?, artwork: Bitmap?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_podcast)
            if (state == null) {
                views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_empty_title))
                views.setTextViewText(R.id.widget_subtitle, context.getString(R.string.widget_empty_subtitle))
                // Show the Onecast mark, inset from the rounded slot edges.
                val pad = (EMPTY_ART_PADDING_DP * context.resources.displayMetrics.density).toInt()
                views.setImageViewResource(R.id.widget_art, R.drawable.ic_widget_logo)
                views.setViewPadding(R.id.widget_art, pad, pad, pad, pad)
                views.setViewVisibility(R.id.widget_play_pause, View.GONE)
                views.setViewVisibility(R.id.widget_skip_back, View.GONE)
                views.setViewVisibility(R.id.widget_skip_forward, View.GONE)
                views.setViewVisibility(R.id.widget_time_row, View.GONE)
                views.setOnClickPendingIntent(R.id.widget_root, openActivityIntent(context, MainActivity::class.java))
                return views
            }
            views.setTextViewText(R.id.widget_title, state.title)
            views.setTextViewText(R.id.widget_subtitle, state.podcastTitle)
            views.setViewVisibility(R.id.widget_play_pause, View.VISIBLE)
            views.setViewVisibility(R.id.widget_skip_back, View.VISIBLE)
            views.setViewVisibility(R.id.widget_skip_forward, View.VISIBLE)
            views.setImageViewResource(
                R.id.widget_play_pause,
                if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
            )
            if (state.durationMs > 0) {
                views.setViewVisibility(R.id.widget_time_row, View.VISIBLE)
                views.setProgressBar(R.id.widget_progress, 1000, state.progressPermille, false)
                views.setTextViewText(R.id.widget_elapsed, Format.clock(state.positionMs))
                views.setTextViewText(R.id.widget_duration, Format.clock(state.durationMs))
            } else {
                views.setViewVisibility(R.id.widget_time_row, View.GONE)
            }
            // Real artwork fills the slot edge-to-edge (no empty-state inset); until the bitmap
            // arrives, clear the slot so the rounded placeholder shows instead of the last logo.
            views.setViewPadding(R.id.widget_art, 0, 0, 0, 0)
            if (artwork != null) {
                views.setImageViewBitmap(R.id.widget_art, artwork)
            } else {
                views.setImageViewResource(R.id.widget_art, android.R.color.transparent)
            }
            views.setOnClickPendingIntent(R.id.widget_root, openActivityIntent(context, PlayerActivity::class.java))
            views.setOnClickPendingIntent(R.id.widget_play_pause, actionIntent(context, ACTION_TOGGLE))
            views.setOnClickPendingIntent(R.id.widget_skip_back, actionIntent(context, ACTION_SKIP_BACK))
            views.setOnClickPendingIntent(R.id.widget_skip_forward, actionIntent(context, ACTION_SKIP_FORWARD))
            return views
        }

        private fun openActivityIntent(context: Context, activity: Class<*>): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, activity),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun actionIntent(context: Context, action: String): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                Intent(context, OnecastWidgetProvider::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
