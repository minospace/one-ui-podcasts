package be.miro.onecast.ui.player

import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import be.miro.onecast.databinding.ActivityPlayerBinding
import kotlin.math.abs

/**
 * The full-screen player's vertical gestures, arbitrated in one place because they share the whole
 * player as their touch surface:
 *
 * - **Scrolling up** anywhere on the player scrolls it up by exactly the artwork zone's height,
 *   bringing the episode notes into the space it vacates.
 * - **Dragging down** anywhere on the player puts the notes away, or dismisses the player when
 *   they're already down.
 * - **Dragging down** on the notes — on their header, or on the text once it's scrolled to the
 *   top — puts them away too.
 *
 * The controls underneath are unaffected: a drag is only claimed from them once the finger has
 * moved further vertically than sideways and past the touch slop, which leaves taps, the seek bar
 * and the chip row's sideways scroll alone (see [DragInterceptLayout]).
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

    /** Where the finger went down, and — from the moment the drag is ours — what it took over. */
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragStartExpansion = 0f
    private var dragStartTranslationY = 0f
    private var dragSlop = 0f
    private var dragMode = Mode.UNDECIDED

    /** Set once the player is on its way out, so nothing can strand it half-dismissed. */
    private var dismissing = false

    val isExpanded: Boolean get() = expansion > 0f

    @Suppress("ClickableViewAccessibility")
    fun attach() {
        // The travel distance is whatever the artwork zone ends up with — it's the weighted child,
        // so it changes with screen size, insets and font scale. Re-measure on every layout.
        binding.playerDragZone.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> measure() }
        bindPlayerGesture()
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

    /**
     * The player's own drags, taken from anywhere on it. The two halves are the same gesture: the
     * intercept decides when it becomes ours, and the touch listener runs it — either from the
     * moment it's claimed, or straight from the finger going down where no control wanted it (the
     * artwork, the space around it).
     */
    @Suppress("ClickableViewAccessibility")
    private fun bindPlayerGesture() {
        binding.playerContent.onInterceptDrag = { event ->
            // On the way out nothing else gets a look in — see [dismissing].
            if (dismissing) true else when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    beginDrag(event)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    track(event)
                    claimDrag(event)
                }
                else -> false
            }
        }
        binding.playerContent.setOnTouchListener { _, event ->
            if (!dismissing) when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> beginDrag(event)
                MotionEvent.ACTION_MOVE -> {
                    track(event)
                    if (claimDrag(event)) drag(event.rawY - dragStartY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    releaseDrag(lifted = event.actionMasked == MotionEvent.ACTION_UP)
            }
            true
        }
    }

    /**
     * Note what a touch starts from, and nothing more: most touches on the player are taps on the
     * controls, and until one has proved itself a drag ([claimDrag]) it must leave whatever is
     * animating alone — cancelling here would strand a settling sheet, or a player mid-dismiss, on
     * every tap that lands during one.
     */
    private fun beginDrag(event: MotionEvent) {
        dragStartX = event.rawX
        dragStartY = event.rawY
        dragMode = Mode.UNDECIDED
        startTracking(event)
    }

    /** True once the finger has moved far enough, and vertically enough, for the drag to be ours. */
    private fun claimDrag(event: MotionEvent): Boolean {
        if (dragMode != Mode.UNDECIDED) return true
        val dy = event.rawY - dragStartY
        // A sideways swipe belongs to whatever is under the finger: the seek bar, the chip row.
        if (abs(dy) <= touchSlop || abs(dy) <= abs(event.rawX - dragStartX)) return false
        // Up always drives the notes, as does down while they're up. Only a downward pull with the
        // notes already away means the player itself.
        dragMode = if (dy < 0 || isExpanded) Mode.NOTES else Mode.DISMISS
        // Now that it's a drag, take over from whatever was animating — from wherever it had got
        // to, so the content carries on under the finger instead of snapping back to where it
        // stood when the finger landed.
        settleAnimator?.cancel()
        binding.root.animate().cancel()
        dragStartExpansion = expansion
        dragStartTranslationY = binding.root.translationY
        // Discount the slop the finger spent getting here so the content starts from rest. Fixed
        // at the direction of the claim: recomputing it from the live delta would flip its sign,
        // and jerk the content by twice the slop, whenever the finger wandered back past its
        // starting point.
        dragSlop = if (dy < 0) touchSlop.toFloat() else -touchSlop.toFloat()
        return true
    }

    private fun drag(dy: Float) {
        val travel = dy + dragSlop
        when (dragMode) {
            Mode.NOTES -> panTo(dragStartExpansion, travel)
            Mode.DISMISS -> dragDismiss(dragStartTranslationY + travel)
            Mode.UNDECIDED -> Unit
        }
    }

    private fun releaseDrag(lifted: Boolean) {
        when (dragMode) {
            Mode.NOTES -> settle()
            Mode.DISMISS -> releaseDismiss(lifted)
            Mode.UNDECIDED -> Unit
        }
        dragMode = Mode.UNDECIDED
    }

    /** Moves the sheet [travel] pixels from [from], down being a close. */
    private fun panTo(from: Float, travel: Float) {
        if (panDistance <= 0f) return
        apply((from - travel / panDistance).coerceIn(0f, 1f))
    }

    @Suppress("ClickableViewAccessibility")
    private fun bindNotesGesture() {
        // A drag in progress, and where it took over from. One per touch surface: shared state
        // would let a stray second finger on one reset the other mid-drag, and the drag it
        // interrupted would never settle.
        val headerDrag = NotesDrag()
        val scrollDrag = NotesDrag()

        // The header is nothing but a handle, so it takes the drag as soon as it clears the slop —
        // but not before, so a tap on it can't cancel a settle in flight and leave the sheet
        // stranded part-way. It re-bases on the spot, like the notes below do.
        binding.playerInfoHeader.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    headerDrag.downY = event.rawY
                    headerDrag.dragging = false
                    startTracking(event)
                }
                MotionEvent.ACTION_MOVE -> {
                    track(event)
                    if (!headerDrag.dragging && abs(event.rawY - headerDrag.downY) > touchSlop) {
                        headerDrag.take(event.rawY)
                    }
                    if (headerDrag.dragging) headerDrag.follow(event.rawY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (headerDrag.dragging) settle()
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
                    scrollDrag.dragging = false
                    startTracking(event)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val step = track(event)
                    if (!scrollDrag.dragging && scroll.scrollY == 0 && step > 0f) {
                        scrollDrag.take(event.rawY)
                    }
                    if (scrollDrag.dragging) {
                        scrollDrag.follow(event.rawY)
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (scrollDrag.dragging) {
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

    /** One touch surface's claim on the sheet: where it took the drag over, and from what. */
    private inner class NotesDrag {
        var dragging = false
        var downY = 0f
        var startExpansion = 1f

        /** Take the drag over here and now, from wherever a settle in flight had got to. */
        fun take(rawY: Float) {
            dragging = true
            settleAnimator?.cancel()
            downY = rawY
            startExpansion = expansion
        }

        fun follow(rawY: Float) = panTo(startExpansion, rawY - downY)
    }

    // ── Drag to dismiss ────────────────────────────────────────────────────

    /** [offset] is where the player should sit, already measured from wherever the drag took over. */
    private fun dragDismiss(offset: Float) {
        val sheet = binding.root
        val travel = offset.coerceAtLeast(0f)
        sheet.translationY = travel
        sheet.alpha = (1f - travel / sheet.height.coerceAtLeast(1) * 0.6f).coerceIn(0.4f, 1f)
    }

    private fun releaseDismiss(lifted: Boolean) {
        val sheet = binding.root
        if (lifted && sheet.translationY > dismissThreshold) {
            dismissing = true
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
