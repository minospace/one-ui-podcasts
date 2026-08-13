package be.miro.onecast.ui.player

import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import be.miro.onecast.databinding.ActivityPlayerBinding
import kotlin.math.abs

/**
 * The full-screen player's vertical gestures, arbitrated in one place because they share the
 * artwork as their touch surface:
 *
 * - **Scrolling down** (finger up) on the artwork scrolls the whole player up by exactly the
 *   artwork zone's height, bringing the episode notes into the space it vacates.
 * - **Dragging down** on the artwork dismisses the player.
 * - **Dragging down** on the notes — on their header, or on the text once it's scrolled to the
 *   top — puts them away again.
 *
 * The sheet only ever rests in one of two states, so every release animates to whichever one it's
 * closest to: past [OPEN_FRACTION] of the way (or released with a flick) it settles open,
 * otherwise it settles back down.
 */
internal class PlayerSheet(
    private val binding: ActivityPlayerBinding,
    private val onDismiss: () -> Unit,
) {

    /** One UI's smooth standard easing curve. */
    private val easing = PathInterpolator(0.22f, 0.25f, 0f, 1f)
    private val density = binding.root.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(binding.root.context).scaledTouchSlop
    private val dismissThreshold = density * 140f
    private val flingSpeed = density * 320f

    /** 0 = player only, 1 = notes fully open. Every intermediate value is a finger mid-drag. */
    private var expansion = 0f
    private var settleAnimator: ValueAnimator? = null

    /** How far the content travels between the two states: the artwork zone's height. */
    private var panDistance = 0f
    private var panelHeight = 0

    private var velocity = 0f
    private var lastRawY = 0f
    private var lastTime = 0L

    val isExpanded: Boolean get() = expansion > 0f

    @Suppress("ClickableViewAccessibility")
    fun attach() {
        // The travel distance is whatever the artwork zone ends up with — it's the weighted child,
        // so it changes with screen size, insets and font scale. Re-measure on every layout.
        binding.playerDragZone.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> measure() }
        bindArtworkGesture()
        bindNotesGesture()
    }

    /** Put the notes away, animated. Returns false when they were already down. */
    fun collapse(): Boolean {
        if (!isExpanded) return false
        animateTo(0f)
        return true
    }

    // ── Geometry ───────────────────────────────────────────────────────────

    private fun measure() {
        val pan = binding.playerDragZone.height.toFloat()
        if (pan <= 0f) return
        // The notes fill everything the artwork vacates, less a breath below the controls (the
        // content's own bottom padding carries the navigation-bar inset, hence the +).
        val height = (pan + binding.playerContent.paddingBottom - GAP_DP * density).toInt()
        if (pan == panDistance && height == panelHeight) return
        panDistance = pan
        panelHeight = height
        binding.playerInfo.updateHeight(height)
        // Don't touch the content's translation while the sheet is down: the enter transition
        // animates that same property, and resetting it here mid-flight makes the player jump.
        if (isExpanded) apply(expansion) else park()
    }

    private fun View.updateHeight(height: Int) {
        val params = layoutParams ?: return
        if (params.height == height) return
        params.height = height
        layoutParams = params
    }

    /** Park the notes just off the bottom edge, out of reach of taps meant for the controls. */
    private fun park() {
        binding.playerInfo.translationY = panelHeight.toFloat()
        binding.playerInfo.alpha = 0f
        binding.playerInfo.visibility = View.INVISIBLE
    }

    private fun apply(fraction: Float) {
        expansion = fraction
        if (fraction <= 0f) {
            binding.playerContent.translationY = 0f
            park()
            return
        }
        binding.playerContent.translationY = -panDistance * fraction
        binding.playerInfo.translationY = panelHeight * (1f - fraction)
        // Reach full opacity before the end of the travel, so the notes are readable rather than
        // ghosted for most of the drag.
        binding.playerInfo.alpha = (fraction * 1.6f).coerceAtMost(1f)
        binding.playerInfo.visibility = View.VISIBLE
    }

    // ── Settling ───────────────────────────────────────────────────────────

    private fun settle() {
        val target = when {
            velocity < -flingSpeed -> 1f
            velocity > flingSpeed -> 0f
            expansion > OPEN_FRACTION -> 1f
            else -> 0f
        }
        animateTo(target)
    }

    private fun animateTo(target: Float) {
        settleAnimator?.cancel()
        if (expansion == target) {
            apply(target)
            return
        }
        settleAnimator = ValueAnimator.ofFloat(expansion, target).apply {
            duration = (180 + 140 * abs(target - expansion)).toLong()
            interpolator = easing
            addUpdateListener { apply(it.animatedValue as Float) }
            start()
        }
    }

    // ── Gestures ───────────────────────────────────────────────────────────

    @Suppress("ClickableViewAccessibility")
    private fun bindArtworkGesture() {
        var downY = 0f
        var mode = Mode.UNDECIDED
        val sheet = binding.root
        binding.playerDragZone.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    mode = Mode.UNDECIDED
                    settleAnimator?.cancel()
                    sheet.animate().cancel()
                    startTracking(event)
                }
                MotionEvent.ACTION_MOVE -> {
                    track(event)
                    val dy = event.rawY - downY
                    if (mode == Mode.UNDECIDED && abs(dy) > touchSlop) {
                        // Up opens the notes, down dismisses the player.
                        mode = if (dy < 0) Mode.NOTES else Mode.DISMISS
                    }
                    when (mode) {
                        // Discount the slop so the content starts moving from under the finger
                        // rather than jumping.
                        Mode.NOTES -> apply(((-dy - touchSlop) / panDistance).coerceIn(0f, 1f))
                        Mode.DISMISS -> dragDismiss(dy)
                        Mode.UNDECIDED -> Unit
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> when (mode) {
                    Mode.NOTES -> settle()
                    Mode.DISMISS ->
                        releaseDismiss(event.actionMasked == MotionEvent.ACTION_UP)
                    Mode.UNDECIDED -> Unit
                }
            }
            true
        }
    }

    @Suppress("ClickableViewAccessibility")
    private fun bindNotesGesture() {
        var downY = 0f
        var startExpansion = 1f
        var dragging = false

        // The header is nothing but a handle, so it takes the drag as soon as it clears the slop.
        binding.playerInfoHeader.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    startExpansion = expansion
                    dragging = false
                    settleAnimator?.cancel()
                    startTracking(event)
                }
                MotionEvent.ACTION_MOVE -> {
                    track(event)
                    val dy = event.rawY - downY
                    if (!dragging && abs(dy) > touchSlop) dragging = true
                    if (dragging) apply((startExpansion - dy / panDistance).coerceIn(0f, 1f))
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) settle()
            }
            true
        }

        // The notes themselves scroll first; only a downward pull with nothing left to scroll
        // hands the gesture over — and it re-bases on the spot, so the sheet follows the finger
        // from where it was rather than snapping.
        val scroll = binding.playerInfoScroll
        scroll.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    startTracking(event)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val step = track(event)
                    if (!dragging && scroll.scrollY == 0 && step > 0f) {
                        dragging = true
                        downY = event.rawY
                        startExpansion = expansion
                        settleAnimator?.cancel()
                    }
                    if (dragging) {
                        apply((startExpansion - (event.rawY - downY) / panDistance).coerceIn(0f, 1f))
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        settle()
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    // ── Drag to dismiss ────────────────────────────────────────────────────

    private fun dragDismiss(dy: Float) {
        val sheet = binding.root
        val travel = dy.coerceAtLeast(0f)
        sheet.translationY = travel
        sheet.alpha = (1f - travel / sheet.height.coerceAtLeast(1) * 0.6f).coerceIn(0.4f, 1f)
    }

    private fun releaseDismiss(lifted: Boolean) {
        val sheet = binding.root
        if (lifted && sheet.translationY > dismissThreshold) {
            sheet.animate()
                .translationY(sheet.height.toFloat())
                .alpha(0f)
                .setDuration(220)
                .setInterpolator(easing)
                .withEndAction(onDismiss)
                .start()
        } else {
            sheet.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(220)
                .setInterpolator(easing)
                .start()
        }
    }

    // ── Finger speed ───────────────────────────────────────────────────────
    //
    // Tracked by hand rather than with a VelocityTracker: the views under the finger are being
    // translated as it moves, so their local coordinates — the only ones a VelocityTracker
    // reads — drift against the finger. Screen coordinates don't.

    private fun startTracking(event: MotionEvent) {
        velocity = 0f
        lastRawY = event.rawY
        lastTime = event.eventTime
    }

    /** Feeds one move into the running speed and returns how far it travelled — down is positive. */
    private fun track(event: MotionEvent): Float {
        val step = event.rawY - lastRawY
        val dt = event.eventTime - lastTime
        if (dt > 0) {
            val sample = step / dt * 1000f
            velocity = if (velocity == 0f) sample else velocity * 0.4f + sample * 0.6f
            lastTime = event.eventTime
        }
        lastRawY = event.rawY
        return step
    }

    private enum class Mode { UNDECIDED, NOTES, DISMISS }

    private companion object {
        /** Release past this much of the travel and the notes stay open. */
        const val OPEN_FRACTION = 0.3f

        /** Gap left between the player's controls and the top of the notes. */
        const val GAP_DP = 16f
    }
}
