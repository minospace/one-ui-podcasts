package be.miro.onecast.ui.player

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import be.miro.onecast.R
import be.miro.onecast.playback.MediaItems
import be.miro.onecast.playback.PlayerConnection
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners

/** Persistent bottom mini-player; shows the currently loaded episode and a play/pause toggle. */
class MiniPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val card: View
    private val art: ImageView
    private val title: TextView
    private val subtitle: TextView
    private val playPause: ImageButton
    private val skipForward: ImageButton
    private val progress: ProgressBar

    /** The artwork view, exposed so the host can use it as a shared element into the full player. */
    val artView: ImageView get() = art

    private var loadedArtworkUri: String? = null

    init {
        orientation = VERTICAL
        // The footer sits outside the content panel, so it would otherwise show the window
        // colour behind the floating card — a darker band that breaks the seam. Paint it the
        // content colour so the card floats cleanly over one continuous surface down to the
        // (matching) navigation bar.
        setBackgroundColor(context.getColor(R.color.app_content_background))
        LayoutInflater.from(context).inflate(R.layout.view_mini_player, this, true)
        card = findViewById(R.id.mini_card)
        art = findViewById(R.id.mini_art)
        title = findViewById(R.id.mini_title)
        subtitle = findViewById(R.id.mini_subtitle)
        playPause = findViewById(R.id.mini_play_pause)
        skipForward = findViewById(R.id.mini_skip_forward)
        progress = findViewById(R.id.mini_progress)
        visibility = GONE
    }

    /** Wire click handlers once. The hosting activity drives [refresh] from its update callback. */
    fun bind(connection: PlayerConnection, onOpenPlayer: () -> Unit) {
        playPause.setOnClickListener { connection.togglePlayPause() }
        skipForward.setOnClickListener { connection.seekForward() }
        card.setOnClickListener { onOpenPlayer() }
    }

    fun refresh(connection: PlayerConnection) {
        val c = connection.controller
        val item = c?.currentMediaItem
        if (c == null || item == null) {
            visibility = GONE
            return
        }
        if (visibility != View.VISIBLE) {
            // First appearance: slide the card up and fade it in instead of snapping into place.
            visibility = View.VISIBLE
            card.alpha = 0f
            card.translationY = card.height.takeIf { it > 0 }?.toFloat()
                ?: (resources.displayMetrics.density * 64f)
            card.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(260)
                .setInterpolator(PathInterpolator(0.22f, 0.25f, 0f, 1f))
                .start()
        }
        val md = c.mediaMetadata
        title.text = md.title ?: ""
        subtitle.text = md.artist ?: ""
        // Use playWhenReady (intent), not isPlaying, so the icon flips immediately on tap even while
        // the stream is still buffering — otherwise it looks unresponsive and invites repeated taps.
        playPause.setImageResource(
            if (c.playWhenReady) R.drawable.ic_pause_rounded else R.drawable.ic_play_rounded,
        )
        // Before the stream loads the player's duration is unknown; fall back to the duration carried
        // in the media item so the bar shows the saved position instead of snapping to the start.
        val duration = c.duration.takeIf { it > 0 } ?: MediaItems.durationMs(item)
        progress.progress = if (duration > 0) ((c.currentPosition * 1000) / duration).toInt() else 0
        // refresh() runs on every player tick (~2x/sec); only reload artwork when it changes.
        val artworkUri = md.artworkUri?.toString()
        if (artworkUri != loadedArtworkUri) {
            loadedArtworkUri = artworkUri
            // Keep the artwork corners concentric with the card: the card's 20dp radius minus its
            // 8dp padding leaves a 12dp inner radius, which also matches the placeholder drawable.
            val artRadius = (12 * resources.displayMetrics.density).toInt()
            Glide.with(art)
                .load(md.artworkUri)
                .transform(RoundedCorners(artRadius))
                .placeholder(R.drawable.bg_art_placeholder)
                .into(art)
        }
    }
}
