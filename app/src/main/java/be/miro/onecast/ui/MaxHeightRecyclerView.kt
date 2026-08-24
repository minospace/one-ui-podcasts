package be.miro.onecast.ui

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

/**
 * A [RecyclerView] that stops growing once it reaches `android:maxHeight` and scrolls instead.
 * Used inside a bottom sheet, where the sheet is sized to its content: without a cap a long list
 * would stretch the sheet to the full screen.
 */
class MaxHeightRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {

    private val maxHeight: Int = context
        .obtainStyledAttributes(attrs, intArrayOf(android.R.attr.maxHeight))
        .let { typed ->
            val value = typed.getDimensionPixelSize(0, 0)
            typed.recycle()
            value
        }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val capped = if (maxHeight > 0) {
            MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
        } else {
            heightSpec
        }
        super.onMeasure(widthSpec, capped)
    }
}
