package be.miro.onecast.ui

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import be.miro.onecast.R
import be.miro.onecast.appSettings

/**
 * Optional Material 3 Expressive accent, replacing the One UI signature blue everywhere the app
 * draws an accent — progress fills, the playing episode/chapter title, icon tints, control tinting
 * and the Settings value summaries. On Android 12+ the colour comes from the wallpaper-derived
 * system tonal palette (the same source the home-screen widget already uses); below that it falls
 * back to the M3 baseline. See `R.color.app_primary_expressive`.
 *
 * Only the *accent* moves: surfaces stay One UI's flat neutral background, so this composes with
 * [AmoledTheme] and leaves the single-continuous-surface look intact.
 *
 * Unlike [AmoledTheme] — which has to recolour SESL surfaces view-by-view because they're read from
 * colour resources — the accent is reachable through `?attr/colorPrimary`, so a theme overlay is
 * enough. Call [apply] from `Activity.onCreate` *before* `setContentView`; a theme change after
 * inflation would leave the already-inflated views on the old colour.
 */
object ExpressiveTheme {

    /** True when the user opted into Material You colours. */
    fun isActive(context: Context): Boolean = context.appSettings.expressiveColor

    /** Layer the expressive accent over [activity]'s theme. No-op unless [isActive]. */
    fun apply(activity: Activity) {
        if (!isActive(activity)) return
        activity.setTheme(R.style.ThemeOverlay_Onecast_Expressive)
    }

    /**
     * The current accent, resolved from the theme rather than [R.color.app_primary], so it follows
     * the overlay when it's applied. For code that paints an accent by hand instead of through an
     * `?attr/colorPrimary` reference in XML.
     */
    @ColorInt
    fun accent(context: Context): Int = context.themeColor(androidx.appcompat.R.attr.colorPrimary)

    /** Resolve a colour theme attribute against this context's theme. */
    @ColorInt
    private fun Context.themeColor(@AttrRes attr: Int): Int {
        val typed = TypedValue()
        theme.resolveAttribute(attr, typed, true)
        return if (typed.resourceId != 0) ContextCompat.getColor(this, typed.resourceId) else typed.data
    }
}
