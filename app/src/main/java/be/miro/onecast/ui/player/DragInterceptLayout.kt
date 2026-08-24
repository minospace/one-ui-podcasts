package be.miro.onecast.ui.player

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout

/**
 * A [LinearLayout] that can take a gesture away from its children part-way through.
 *
 * It's how the player's vertical drags work anywhere on the screen rather than only over the
 * artwork ([PlayerSheet] supplies the rule): every child keeps its taps, and the seek bar and chip
 * row keep their sideways drags, because the gesture is only claimed once the finger has clearly
 * moved vertically.
 */
class DragInterceptLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /**
     * Consulted on every touch this layout is dispatched, before its children see it. Returning
     * true claims the rest of the gesture: whatever was under the finger is sent a cancel, and
     * every event from then on arrives at this view's own touch listener.
     */
    var onInterceptDrag: ((MotionEvent) -> Boolean)? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        onInterceptDrag?.invoke(ev) ?: false
}
