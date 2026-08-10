package be.miro.onecast.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceViewHolder

/**
 * One UI Settings colours the *value* summary of a picker row (e.g. "15 seconds", "HEVC") in the
 * accent blue, while descriptive subtitles stay grey. Plain [ListPreference]/[MultiSelectListPreference]
 * draw every summary grey, so these variants tint their own summary with the accent on bind. Use
 * them in the settings XML for rows whose summary is the current selection; leave descriptive
 * toggles as-is.
 */
private fun tintSummaryAccent(context: Context, holder: PreferenceViewHolder) {
    // Read from the theme rather than @color/app_primary so the row follows the optional Material
    // You accent (ui/ExpressiveTheme) instead of staying blue while the rest of the screen moves.
    (holder.findViewById(android.R.id.summary) as? TextView)
        ?.setTextColor(ExpressiveTheme.accent(context))
}

/** [ListPreference] whose value summary is drawn in the One UI accent colour. */
class AccentListPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ListPreference(context, attrs) {
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        tintSummaryAccent(context, holder)
    }
}

/** [MultiSelectListPreference] whose value summary is drawn in the One UI accent colour. */
class AccentMultiSelectListPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : MultiSelectListPreference(context, attrs) {
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        tintSummaryAccent(context, holder)
    }
}
