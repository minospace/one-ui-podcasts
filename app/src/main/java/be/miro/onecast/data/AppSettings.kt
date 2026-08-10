package be.miro.onecast.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** User preferences backed by SharedPreferences, exposed as Flows so the UI re-renders on change. */
class AppSettings(private val prefs: SharedPreferences) {

    /** When true, episodes already marked played are omitted from episode lists. */
    var hidePlayedEpisodes: Boolean
        get() = prefs.getBoolean(KEY_HIDE_PLAYED, false)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_PLAYED, value).apply()

    fun observeHidePlayedEpisodes(): Flow<Boolean> = observeBoolean(KEY_HIDE_PLAYED) { hidePlayedEpisodes }

    /**
     * When true, starting an episode lines up that podcast's newer unplayed episodes in the Up Next
     * queue (chronologically). Defaults on; the user can turn it off in Settings.
     */
    val autoQueueNewer: Boolean
        get() = prefs.getBoolean(KEY_AUTO_QUEUE_NEWER, true)

    /**
     * Id of the episode currently loaded into the player, or -1 if none. Persisted so a
     * picked-but-not-yet-played episode can be restored into the player after the (non-foreground,
     * killable) playback service is torn down on backgrounding.
     */
    var lastEpisodeId: Long
        get() = prefs.getLong(KEY_LAST_EPISODE, -1L)
        set(value) = prefs.edit().putLong(KEY_LAST_EPISODE, value).apply()

    /** How many seconds the rewind control jumps back. User-configurable in Settings. */
    val rewindSeconds: Int
        get() = prefs.getString(KEY_REWIND_SECONDS, null)?.toIntOrNull() ?: DEFAULT_REWIND_SECONDS

    /** How many seconds the forward control jumps ahead. User-configurable in Settings. */
    val forwardSeconds: Int
        get() = prefs.getString(KEY_FORWARD_SECONDS, null)?.toIntOrNull() ?: DEFAULT_FORWARD_SECONDS

    /**
     * Use a true-black background in dark mode (saves power on AMOLED screens). Only takes effect
     * while the system is in dark mode; the day theme is unaffected.
     */
    val amoledBlack: Boolean
        get() = prefs.getBoolean(KEY_AMOLED_BLACK, false)

    /**
     * Keep the One UI 8.5 grouped-card look on the Settings screen even in pure-black mode
     * (normally flattened so the list sits flat on the true-black surface). Only meaningful while
     * [amoledBlack] is on.
     */
    val amoledShowCards: Boolean
        get() = prefs.getBoolean(KEY_AMOLED_SHOW_CARDS, false)

    /**
     * Draw the accent in the Material 3 Expressive palette instead of the One UI signature blue.
     * On Android 12+ that palette is derived from the wallpaper; below it falls back to the M3
     * baseline. Surfaces stay One UI's flat neutral either way — only the accent moves.
     */
    val expressiveColor: Boolean
        get() = prefs.getBoolean(KEY_EXPRESSIVE_COLOR, false)

    /**
     * The playback speeds the player cycles through, sorted ascending. The user picks which ones
     * to include in Settings; an empty selection falls back to normal speed so the chip never dies.
     */
    val playbackSpeeds: List<Float>
        get() {
            val stored = prefs.getStringSet(KEY_PLAYBACK_SPEEDS, null)
                ?.mapNotNull { it.toFloatOrNull() }
                ?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_PLAYBACK_SPEEDS
            return stored.sorted()
        }

    private fun observeBoolean(key: String, read: () -> Boolean): Flow<Boolean> = callbackFlow {
        trySend(read())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == key) trySend(read())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        // Public so the settings PreferenceFragment can write into this same prefs file.
        const val PREFS_NAME = "app_settings"
        private const val KEY_HIDE_PLAYED = "hide_played_episodes"
        private const val KEY_AUTO_QUEUE_NEWER = "auto_queue_newer"
        private const val KEY_LAST_EPISODE = "last_episode_id"
        const val KEY_REWIND_SECONDS = "rewind_seconds"
        const val KEY_FORWARD_SECONDS = "forward_seconds"
        const val KEY_AMOLED_BLACK = "amoled_black"
        const val KEY_AMOLED_SHOW_CARDS = "amoled_show_cards"
        const val KEY_EXPRESSIVE_COLOR = "expressive_color"
        const val KEY_PLAYBACK_SPEEDS = "playback_speeds"

        const val DEFAULT_REWIND_SECONDS = 15
        const val DEFAULT_FORWARD_SECONDS = 30
        val DEFAULT_PLAYBACK_SPEEDS = listOf(0.8f, 1.0f, 1.2f, 1.5f, 1.75f, 2.0f)

        fun create(context: Context): AppSettings =
            AppSettings(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
