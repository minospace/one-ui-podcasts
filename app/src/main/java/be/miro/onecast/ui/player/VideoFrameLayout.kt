package be.miro.onecast.ui.player

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Holds the player's video at the aspect ratio the stream reports, letterboxed inside whatever space
 * the artwork used to occupy: as wide as it can be, or as tall as it can be for a portrait clip.
 *
 * (Media3 ships `AspectRatioFrameLayout` for this, but it lives in `media3-ui`, which would drag
 * stock AndroidX/Material back onto the classpath alongside the SESL forks.)
 */
class VideoFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Width ÷ height of the video. 16:9 until the player reports the real size. */
    var aspectRatio: Float = DEFAULT_ASPECT_RATIO
        set(value) {
            if (value <= 0f || value == field) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
        var width = availableWidth
        var height = (width / aspectRatio).toInt()
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED &&
            availableHeight > 0 && height > availableHeight
        ) {
            height = availableHeight
            width = (height * aspectRatio).toInt()
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }

    private companion object {
        const val DEFAULT_ASPECT_RATIO = 16f / 9f
    }
}
