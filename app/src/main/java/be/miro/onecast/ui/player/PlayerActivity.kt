package be.miro.onecast.ui.player

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.TransitionDrawable
import android.net.Uri
import android.os.Bundle
import android.transition.ChangeBounds
import android.transition.ChangeImageTransform
import android.transition.ChangeTransform
import android.transition.Slide
import android.transition.TransitionSet
import android.view.Gravity
import android.view.animation.PathInterpolator
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import be.miro.onecast.R
import be.miro.onecast.data.Chapter
import be.miro.onecast.data.indexAt
import be.miro.onecast.databinding.ActivityPlayerBinding
import be.miro.onecast.playback.MediaItems
import be.miro.onecast.ui.Format
import be.miro.onecast.ui.MediaActivity
import be.miro.onecast.ui.queue.QueueSheet
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import kotlinx.coroutines.launch

/** Full-screen now-playing UI driven entirely by the shared MediaController. */
class PlayerActivity : MediaActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var userSeeking = false
    private var enterTransitionStarted = false
    private var loadedArtworkUri: String? = null

    private var chapters: List<Chapter> = emptyList()
    private var chaptersEpisodeId: Long? = null
    private var currentChapterIndex = -1
    private var chapterAdapter: ChapterAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setupTransitions()
        setContentView(binding.root)
        drawBehindSystemBars()

        // Hold the enter transition until the artwork is ready so it grows cleanly from the
        // mini-player; a fallback starts it anyway if the controller is slow to connect.
        postponeEnterTransition()
        binding.root.postDelayed({ beginEnterTransition() }, 300)

        binding.playerUpNext.setOnClickListener { QueueSheet.show(this) }
        setupDragToDismiss()
        binding.playerPlayPause.setOnClickListener { playerConnection.togglePlayPause() }
        binding.playerSkipBack.setOnClickListener { playerConnection.seekBack() }
        binding.playerSkipForward.setOnClickListener { playerConnection.seekForward() }
        binding.playerSpeed.setOnClickListener { cycleSpeed() }
        binding.playerMarkPlayed.setOnClickListener { markPlayed() }
        binding.playerChapter.setOnClickListener { showChapterPicker() }

        binding.playerSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.playerPosition.text = Format.clock(progress * 1000L)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                userSeeking = false
                playerConnection.seekTo(seekBar.progress * 1000L)
            }
        })

        playerConnection.onUpdate = { render() }
    }

    /**
     * Draw the artwork gradient full-bleed, behind the status and navigation bars, so it
     * reaches every edge. The background views take no insets; only [R.id.player_content] is
     * padded by the system-bar insets (added on top of its design padding) so the grabber
     * and controls stay clear of the bars.
     */
    private fun drawBehindSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val content = binding.playerContent
        val baseLeft = content.paddingLeft
        val baseTop = content.paddingTop
        val baseRight = content.paddingRight
        val baseBottom = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top,
                baseRight + bars.right,
                baseBottom + bars.bottom,
            )
            insets
        }
    }

    /** Grow the artwork up out of the mini-player while the rest slides in from the bottom. */
    private fun setupTransitions() {
        // One UI's smooth standard easing curve.
        val easing = PathInterpolator(0.22f, 0.25f, 0f, 1f)
        val sharedElement = TransitionSet().apply {
            addTransition(ChangeBounds())
            addTransition(ChangeTransform())
            addTransition(ChangeImageTransform())
            duration = 320
            interpolator = easing
        }
        window.sharedElementEnterTransition = sharedElement
        window.sharedElementReturnTransition = sharedElement

        val slide = Slide(Gravity.BOTTOM).apply {
            duration = 300
            interpolator = easing
            excludeTarget(R.id.player_art, true)
            excludeTarget(android.R.id.statusBarBackground, true)
            excludeTarget(android.R.id.navigationBarBackground, true)
        }
        window.enterTransition = slide
        window.returnTransition = slide
    }

    /**
     * Let the user drag the whole player sheet down to dismiss it — anywhere in the zone above
     * the title (grabber, close button gap and artwork) starts the drag. Past a threshold it
     * slides the rest of the way off-screen and finishes; otherwise it springs back.
     */
    @Suppress("ClickableViewAccessibility")
    private fun setupDragToDismiss() {
        val easing = PathInterpolator(0.22f, 0.25f, 0f, 1f)
        val dismissThreshold = resources.displayMetrics.density * 140f
        val sheet = binding.root
        var downRawY = 0f
        var dragging = false
        binding.playerDragZone.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downRawY = event.rawY
                    dragging = true
                    sheet.animate().cancel()
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return@setOnTouchListener false
                    val dy = (event.rawY - downRawY).coerceAtLeast(0f)
                    sheet.translationY = dy
                    sheet.alpha = (1f - dy / (sheet.height.coerceAtLeast(1)) * 0.6f).coerceIn(0.4f, 1f)
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) return@setOnTouchListener false
                    dragging = false
                    if (event.actionMasked == android.view.MotionEvent.ACTION_UP &&
                        sheet.translationY > dismissThreshold
                    ) {
                        sheet.animate()
                            .translationY(sheet.height.toFloat())
                            .alpha(0f)
                            .setDuration(220)
                            .setInterpolator(easing)
                            .withEndAction {
                                finish()
                                overridePendingTransition(0, 0)
                            }
                            .start()
                    } else {
                        sheet.animate()
                            .translationY(0f)
                            .alpha(1f)
                            .setDuration(220)
                            .setInterpolator(easing)
                            .start()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun beginEnterTransition() {
        if (!enterTransitionStarted) {
            enterTransitionStarted = true
            startPostponedEnterTransition()
        }
    }

    private fun render() {
        val controller = playerConnection.controller ?: return
        if (controller.currentMediaItem == null) return

        val metadata = controller.mediaMetadata
        binding.playerTitle.text = metadata.title ?: ""
        binding.playerPodcast.text = metadata.artist ?: ""
        // Reflect the *intent* (playWhenReady), not isPlaying: during buffering isPlaying is still
        // false, so an isPlaying-driven icon stays on "play" after a tap and looks unresponsive,
        // prompting repeated taps. playWhenReady flips to "pause" the moment the tap registers.
        binding.playerPlayPause.setImageResource(
            if (controller.playWhenReady) R.drawable.ic_pause_rounded else R.drawable.ic_play_rounded,
        )
        binding.playerSpeed.text = formatSpeed(controller.playbackParameters.speed)

        // render() runs on every player tick (~2x/sec); only reload artwork when it actually
        // changes, otherwise begin the postponed enter transition straight away.
        val artworkUri = metadata.artworkUri?.toString()
        if (artworkUri != loadedArtworkUri) {
            loadedArtworkUri = artworkUri
            applyDynamicBackground(metadata.artworkUri)
            Glide.with(this)
                .load(metadata.artworkUri)
                .transform(RoundedCorners(32))
                .placeholder(R.drawable.bg_art_placeholder)
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean,
                    ): Boolean {
                        binding.playerArt.post { beginEnterTransition() }
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean,
                    ): Boolean {
                        binding.playerArt.post { beginEnterTransition() }
                        return false
                    }
                })
                .into(binding.playerArt)
        } else {
            beginEnterTransition()
        }

        // Prefer the player's real duration; before the stream loads, fall back to the duration
        // carried in the media item so the bar reflects the saved position instead of snapping to 0.
        val duration = controller.duration.takeIf { it > 0 }
            ?: MediaItems.durationMs(controller.currentMediaItem).takeIf { it > 0 }
            ?: 0L
        binding.playerSeekbar.max = (duration / 1000).toInt()
        binding.playerDuration.text = Format.clock(duration)
        if (!userSeeking) {
            binding.playerSeekbar.progress = (controller.currentPosition / 1000).toInt()
            binding.playerPosition.text = Format.clock(controller.currentPosition)
        }

        updateChapters(MediaItems.episodeId(controller.currentMediaItem), controller.currentPosition)
    }

    /**
     * Tints the player background with a gradient derived from the current artwork's dominant
     * colour, fading down to the normal surface colour so the controls stay readable.
     */
    private fun applyDynamicBackground(artworkUri: Uri?) {
        if (artworkUri == null) {
            crossfadeBackground(GradientDrawable().apply { setColor(surfaceColor()) })
            return
        }
        Glide.with(this)
            .asBitmap()
            .load(artworkUri)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    Palette.from(resource).generate { palette ->
                        // The view may be gone (player closed) by the time the palette is ready.
                        if (isFinishing || isDestroyed || palette == null) return@generate
                        val base = surfaceColor()
                        val accent = palette.getVibrantColor(
                            palette.getDominantColor(palette.getMutedColor(base)),
                        )
                        crossfadeBackground(buildBackground(accent))
                    }
                }

                override fun onLoadCleared(placeholder: Drawable?) = Unit
            })
    }

    /** A vertical wash: muted artwork colour at the top fading to the surface near the controls. */
    private fun buildBackground(accent: Int): GradientDrawable {
        val base = surfaceColor()
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        // Pull the accent well toward the surface so it reads as a tint, not a solid fill.
        val top = ColorUtils.blendARGB(accent, base, if (night) 0.55f else 0.70f)
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(top, ColorUtils.blendARGB(top, base, 0.6f), base),
        )
    }

    private fun crossfadeBackground(next: Drawable) {
        val view = binding.playerBackground
        val current = view.background
        if (current == null) {
            view.background = next
            return
        }
        view.background = TransitionDrawable(arrayOf(current, next)).apply {
            isCrossFadeEnabled = true
            startTransition(450)
        }
    }

    private fun surfaceColor(): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.colorBackground, value, true)
        return value.data
    }

    /** Loads chapters when the episode changes and tracks the active chapter as it plays. */
    private fun updateChapters(episodeId: Long?, positionMs: Long) {
        if (episodeId != chaptersEpisodeId) {
            chaptersEpisodeId = episodeId
            chapters = emptyList()
            currentChapterIndex = -1
            chapterAdapter = null
            binding.playerChapter.visibility = android.view.View.GONE
            if (episodeId != null) loadChapters(episodeId)
            return
        }
        if (chapters.isEmpty()) return

        val index = chapters.indexAt(positionMs)
        if (index != currentChapterIndex) {
            currentChapterIndex = index
            binding.playerChapter.text = chapterLabel(index)
            chapterAdapter?.setCurrentIndex(index)
        }
    }

    private fun loadChapters(episodeId: Long) {
        lifecycleScope.launch {
            val loaded = repository.ensureChapters(episodeId)
            // The user may have skipped to another episode while this was loading.
            if (chaptersEpisodeId != episodeId) return@launch
            chapters = loaded
            currentChapterIndex = -1
            if (loaded.isEmpty()) {
                binding.playerChapter.visibility = android.view.View.GONE
            } else {
                binding.playerChapter.visibility = android.view.View.VISIBLE
                val position = playerConnection.controller?.currentPosition ?: 0L
                currentChapterIndex = loaded.indexAt(position)
                binding.playerChapter.text = chapterLabel(currentChapterIndex)
            }
        }
    }

    private fun chapterLabel(index: Int): String =
        chapters.getOrNull(index)?.title ?: getString(R.string.chapters)

    private fun showChapterPicker() {
        if (chapters.isEmpty()) return
        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@PlayerActivity)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.chapters)
            .setView(recycler)
            .setNegativeButton(R.string.close, null)
            .setOnDismissListener { chapterAdapter = null }
            .create()
        val adapter = ChapterAdapter(chapters, currentChapterIndex) { chapter ->
            playerConnection.seekTo(chapter.startMs)
            dialog.dismiss()
        }
        chapterAdapter = adapter
        recycler.adapter = adapter
        dialog.show()
        // Open scrolled to the chapter that's playing.
        currentChapterIndex.takeIf { it >= 0 }?.let { recycler.scrollToPosition(it) }
    }

    private fun cycleSpeed() {
        // The set of speeds is user-configurable in Settings; read it fresh so changes apply live.
        val speeds = settings.playbackSpeeds
        if (speeds.isEmpty()) return
        // Advance from the player's real current speed rather than a local index, so the chip
        // stays correct after reopening the player or a process restart.
        val current = playerConnection.controller?.playbackParameters?.speed ?: 1.0f
        val currentIndex = speeds.indexOfFirst { kotlin.math.abs(it - current) < 0.01f }
        val speed = speeds[(currentIndex + 1).mod(speeds.size)]
        playerConnection.setSpeed(speed)
        binding.playerSpeed.text = formatSpeed(speed)
    }

    private fun formatSpeed(speed: Float): String = "${speed}×"

    private fun markPlayed() {
        val episodeId = MediaItems.episodeId(playerConnection.controller?.currentMediaItem) ?: return
        lifecycleScope.launch { repository.setPlayed(episodeId, true) }
        Toast.makeText(this, "Marked as played", Toast.LENGTH_SHORT).show()
    }
}
