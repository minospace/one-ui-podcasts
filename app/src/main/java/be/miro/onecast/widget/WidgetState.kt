package be.miro.onecast.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

/**
 * Snapshot of "what the widget should currently show," persisted so the widget renders
 * correctly even when [be.miro.onecast.playback.PlaybackService] isn't alive
 * (process killed, device rebooted).
 */
data class WidgetState(
    val episodeId: Long,
    val title: String,
    val podcastTitle: String,
    val artworkUrl: String?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
) {
    /** Playback progress in the 0..1000 range the widget's [ProgressBar] expects. */
    val progressPermille: Int
        get() = if (durationMs > 0) ((positionMs * 1000) / durationMs).toInt().coerceIn(0, 1000) else 0

    companion object {
        private const val PREFS_NAME = "widget_state"
        private const val KEY_EPISODE_ID = "episode_id"
        private const val KEY_TITLE = "title"
        private const val KEY_PODCAST_TITLE = "podcast_title"
        private const val KEY_ARTWORK_URL = "artwork_url"
        private const val KEY_IS_PLAYING = "is_playing"
        private const val KEY_POSITION_MS = "position_ms"
        private const val KEY_DURATION_MS = "duration_ms"

        fun update(
            context: Context,
            episodeId: Long,
            title: String,
            podcastTitle: String,
            artworkUrl: String?,
            isPlaying: Boolean,
            positionMs: Long,
            durationMs: Long,
        ) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putLong(KEY_EPISODE_ID, episodeId)
                .putString(KEY_TITLE, title)
                .putString(KEY_PODCAST_TITLE, podcastTitle)
                .putString(KEY_ARTWORK_URL, artworkUrl)
                .putBoolean(KEY_IS_PLAYING, isPlaying)
                .putLong(KEY_POSITION_MS, positionMs)
                .putLong(KEY_DURATION_MS, durationMs)
                .apply()
        }

        /** Null if no episode has ever played. */
        fun read(context: Context): WidgetState? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val episodeId = prefs.getLong(KEY_EPISODE_ID, -1L)
            if (episodeId < 0) return null
            return WidgetState(
                episodeId = episodeId,
                title = prefs.getString(KEY_TITLE, "") ?: "",
                podcastTitle = prefs.getString(KEY_PODCAST_TITLE, "") ?: "",
                artworkUrl = prefs.getString(KEY_ARTWORK_URL, null),
                isPlaying = prefs.getBoolean(KEY_IS_PLAYING, false),
                positionMs = prefs.getLong(KEY_POSITION_MS, 0L),
                durationMs = prefs.getLong(KEY_DURATION_MS, 0L),
            )
        }

        /** Back to "nothing playing" — the episode that was loaded is gone (its files deleted). */
        fun clear(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        }

        fun notifyWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, OnecastWidgetProvider::class.java))
            if (ids.isNotEmpty()) OnecastWidgetProvider.refreshWidgets(context, manager, ids)
        }
    }
}
