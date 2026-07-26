package be.miro.onecast.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import be.miro.onecast.appSettings
import be.miro.onecast.data.AppSettings
import be.miro.onecast.data.PodcastRepository
import be.miro.onecast.download.EpisodeDownloader
import be.miro.onecast.episodeDownloads
import be.miro.onecast.playback.PlayerConnection
import be.miro.onecast.podcastRepository
import dev.oneuiproject.oneui.layout.ToolbarLayout

/** Base activity that owns a lifecycle-bound [PlayerConnection] and exposes the repository. */
abstract class MediaActivity : AppCompatActivity() {

    protected lateinit var playerConnection: PlayerConnection
        private set

    protected val repository: PodcastRepository get() = podcastRepository

    protected val settings: AppSettings get() = appSettings

    protected val downloads: EpisodeDownloader get() = episodeDownloads

    private var amoledApplied = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playerConnection = PlayerConnection(this, lifecycle)
        // Capture the current AMOLED state as the baseline for the onResume check below, so it
        // fires only when the setting actually changes while we're in the back stack. Subclasses
        // that don't paint the AMOLED surface (e.g. PlayerActivity, which draws its own full-bleed
        // background) would otherwise leave this at its default and recreate() on every onResume —
        // an infinite recreate loop that makes the screen flicker in and out.
        amoledApplied = AmoledTheme.isActive(this)
    }

    /** Recolour the screen to true black if the AMOLED option is on. Call after `setContentView`. */
    protected fun applyAmoledBackground(toolbar: ToolbarLayout?) {
        amoledApplied = AmoledTheme.isActive(this)
        AmoledTheme.apply(this, toolbar)
    }

    override fun onResume() {
        super.onResume()
        // The AMOLED setting may have been toggled (e.g. on the Settings screen) while this
        // activity sat in the back stack; rebuild so the new background takes effect.
        if (AmoledTheme.isActive(this) != amoledApplied) recreate()
    }
}
