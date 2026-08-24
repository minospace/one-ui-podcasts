package be.miro.onecast.ui.player

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import be.miro.onecast.R
import be.miro.onecast.data.Chapter
import be.miro.onecast.ui.Format

/** Chapter list for the player's "choose chapter" sheet; highlights the active one. */
class ChapterAdapter(
    private val chapters: List<Chapter>,
    private var currentIndex: Int,
    private val onSelect: (Chapter) -> Unit,
) : RecyclerView.Adapter<ChapterAdapter.Holder>() {

    fun setCurrentIndex(index: Int) {
        if (index == currentIndex) return
        val previous = currentIndex
        currentIndex = index
        if (previous in chapters.indices) notifyItemChanged(previous)
        if (index in chapters.indices) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chapter, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = chapters.size

    override fun onBindViewHolder(holder: Holder, position: Int) =
        holder.bind(chapters[position], position == currentIndex)

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.chapter_title)
        private val start: TextView = view.findViewById(R.id.chapter_start)

        fun bind(chapter: Chapter, isCurrent: Boolean) {
            title.text = chapter.title
            start.text = Format.clock(chapter.startMs)
            val color = if (isCurrent) {
                itemView.context.themeColor(androidx.appcompat.R.attr.colorPrimary)
            } else {
                itemView.context.themeColor(android.R.attr.textColorPrimary)
            }
            title.setTextColor(color)
            itemView.setOnClickListener { onSelect(chapter) }
        }
    }
}

private fun Context.themeColor(attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return if (tv.resourceId != 0) ContextCompat.getColor(this, tv.resourceId) else tv.data
}
