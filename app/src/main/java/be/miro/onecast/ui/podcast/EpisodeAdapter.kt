package be.miro.onecast.ui.podcast

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import be.miro.onecast.R
import be.miro.onecast.data.Episode
import be.miro.onecast.data.Podcast
import be.miro.onecast.ui.Format
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners

/** Header (artwork/author/description) followed by the episode rows. */
class EpisodeAdapter(
    private val onPlay: (Episode) -> Unit,
    private val onTogglePlayed: (Episode) -> Unit,
    private val onLongPress: (Episode, View) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var podcast: Podcast? = null
    private var episodes: List<Episode> = emptyList()
    private var currentEpisodeId: Long? = null
    private var downloadingIds: Set<Long> = emptySet()
    private var descriptionExpanded = false
    private val expandedEpisodeIds = mutableSetOf<Long>()

    /**
     * The episode list re-emits from Room on every position-save write (every ~5s while an
     * episode is playing), even though almost nothing in the list actually changed. Diffing
     * against the previous list avoids rebinding (and re-decoding artwork for) every row on each
     * of those emissions.
     */
    fun submit(podcast: Podcast?, episodes: List<Episode>) {
        val oldPodcast = this.podcast
        val oldEpisodes = this.episodes
        this.podcast = podcast
        this.episodes = episodes
        if (podcast != oldPodcast) notifyItemChanged(0)
        val diff = DiffUtil.calculateDiff(EpisodeDiffCallback(oldEpisodes, episodes))
        diff.dispatchUpdatesTo(HeaderOffsetListUpdateCallback(this))
    }

    fun setCurrentEpisode(id: Long?) {
        if (id == currentEpisodeId) return
        val previousId = currentEpisodeId
        currentEpisodeId = id
        previousId?.let { notifyIfPresent(it) }
        id?.let { notifyIfPresent(it) }
    }

    /**
     * Which episodes are queued/downloading right now. Only the membership matters, not the byte
     * counter, so rows rebind when a download starts or ends rather than on every progress tick.
     */
    fun setDownloadingIds(ids: Set<Long>) {
        if (ids == downloadingIds) return
        val changed = (ids - downloadingIds) + (downloadingIds - ids)
        downloadingIds = ids
        changed.forEach { notifyIfPresent(it) }
    }

    private fun notifyIfPresent(episodeId: Long) {
        val index = episodes.indexOfFirst { it.id == episodeId }
        if (index >= 0) notifyItemChanged(index + 1)
    }

    override fun getItemCount(): Int = episodes.size + 1

    override fun getItemViewType(position: Int): Int =
        if (position == 0) TYPE_HEADER else TYPE_EPISODE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_podcast_header, parent, false))
        } else {
            EpisodeHolder(inflater.inflate(R.layout.item_episode, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is HeaderHolder) {
            holder.bind(podcast)
        } else if (holder is EpisodeHolder) {
            holder.bind(episodes[position - 1])
        }
    }

    private inner class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val art: ImageView = view.findViewById(R.id.header_art)
        private val author: TextView = view.findViewById(R.id.header_author)
        private val description: TextView = view.findViewById(R.id.header_description)

        fun bind(podcast: Podcast?) {
            author.text = podcast?.author ?: ""
            author.visibility = if (podcast?.author.isNullOrBlank()) View.GONE else View.VISIBLE
            val rawDescription = podcast?.description
            description.text = rawDescription?.let {
                HtmlCompat.fromHtml(it, HtmlCompat.FROM_HTML_MODE_COMPACT).trim()
            }
            description.visibility = if (rawDescription.isNullOrBlank()) View.GONE else View.VISIBLE
            description.maxLines = if (descriptionExpanded) Integer.MAX_VALUE else 5
            description.setOnClickListener {
                descriptionExpanded = !descriptionExpanded
                description.maxLines = if (descriptionExpanded) Integer.MAX_VALUE else 5
            }
            Glide.with(art)
                .load(podcast?.artworkUrl)
                .transform(RoundedCorners(24))
                .placeholder(R.drawable.bg_art_placeholder)
                .into(art)
        }
    }

    private inner class EpisodeHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.episode_title)
        private val meta: TextView = view.findViewById(R.id.episode_meta)
        private val played: ImageView = view.findViewById(R.id.episode_played)
        private val expand: ImageView = view.findViewById(R.id.episode_expand)
        private val description: TextView = view.findViewById(R.id.episode_description)

        fun bind(episode: Episode) {
            title.text = episode.title

            val isCurrent = episode.id == currentEpisodeId
            val primaryColor = title.context.themeColor(android.R.attr.textColorPrimary)
            val secondaryColor = title.context.themeColor(android.R.attr.textColorSecondary)
            val accentColor = title.context.themeColor(
                androidx.appcompat.R.attr.colorPrimary,
            )
            title.setTextColor(
                when {
                    isCurrent -> accentColor
                    episode.isPlayed -> secondaryColor
                    else -> primaryColor
                },
            )

            meta.text = buildMeta(episode)
            played.setColorFilter(if (episode.isPlayed) accentColor else secondaryColor)

            val hasDescription = !episode.description.isNullOrBlank()
            expand.visibility = if (hasDescription) View.VISIBLE else View.GONE
            val isExpanded = hasDescription && expandedEpisodeIds.contains(episode.id)
            expand.rotation = if (isExpanded) 180f else 0f
            if (isExpanded) {
                description.text = HtmlCompat
                    .fromHtml(episode.description!!, HtmlCompat.FROM_HTML_MODE_COMPACT)
                    .trim()
                description.visibility = View.VISIBLE
            } else {
                description.visibility = View.GONE
            }

            itemView.setOnClickListener { onPlay(episode) }
            itemView.setOnLongClickListener {
                onLongPress(episode, itemView)
                true
            }
            played.setOnClickListener { onTogglePlayed(episode) }
            expand.setOnClickListener {
                if (expandedEpisodeIds.contains(episode.id)) {
                    expandedEpisodeIds.remove(episode.id)
                } else {
                    expandedEpisodeIds.add(episode.id)
                }
                notifyItemChanged(bindingAdapterPosition)
            }
        }

        private fun buildMeta(episode: Episode): String {
            val parts = mutableListOf<String>()
            Format.relativeDate(episode.pubDate).takeIf { it.isNotBlank() }?.let { parts += it }
            Format.durationLabel(episode.durationMs).takeIf { it.isNotBlank() }?.let { parts += it }
            when {
                episode.isPlayed -> parts += "Played"
                episode.positionMs > 0 && episode.durationMs > 0 -> {
                    // Feeds often under-report duration, so positionMs can reach/exceed it;
                    // only show "… left" when there's a real (positive) remainder to label.
                    val remaining = episode.durationMs - episode.positionMs
                    Format.durationLabel(remaining).takeIf { it.isNotBlank() }
                        ?.let { parts += "$it left" }
                }
            }
            when {
                episode.id in downloadingIds -> parts += "Downloading…"
                episode.downloadPath != null -> parts += "Downloaded"
            }
            if (episode.hasVideo) parts += title.context.getString(R.string.video_label)
            return parts.joinToString("  ·  ")
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_EPISODE = 1
    }
}

private fun android.content.Context.themeColor(attr: Int): Int {
    val tv = android.util.TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return if (tv.resourceId != 0) androidx.core.content.ContextCompat.getColor(this, tv.resourceId) else tv.data
}

private class EpisodeDiffCallback(
    private val old: List<Episode>,
    private val new: List<Episode>,
) : DiffUtil.Callback() {
    override fun getOldListSize() = old.size
    override fun getNewListSize() = new.size
    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
        old[oldItemPosition].id == new[newItemPosition].id
    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
        old[oldItemPosition] == new[newItemPosition]
}

/** Shifts diff positions by 1 to account for the header occupying position 0. */
private class HeaderOffsetListUpdateCallback(
    private val adapter: RecyclerView.Adapter<*>,
) : androidx.recyclerview.widget.ListUpdateCallback {
    override fun onInserted(position: Int, count: Int) = adapter.notifyItemRangeInserted(position + 1, count)
    override fun onRemoved(position: Int, count: Int) = adapter.notifyItemRangeRemoved(position + 1, count)
    override fun onMoved(fromPosition: Int, toPosition: Int) =
        adapter.notifyItemMoved(fromPosition + 1, toPosition + 1)
    override fun onChanged(position: Int, count: Int, payload: Any?) =
        adapter.notifyItemRangeChanged(position + 1, count, payload)
}
