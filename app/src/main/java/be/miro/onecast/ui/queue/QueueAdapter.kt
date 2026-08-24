package be.miro.onecast.ui.queue

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import be.miro.onecast.R
import be.miro.onecast.data.Episode
import be.miro.onecast.data.EpisodeWithPodcast
import be.miro.onecast.ui.Format
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners

/** The Up Next list: tap a row to play it now, or hit the ✕ to drop it from the queue. */
class QueueAdapter(
    private val onPlay: (Episode) -> Unit,
    private val onRemove: (Episode) -> Unit,
) : ListAdapter<EpisodeWithPodcast, QueueAdapter.QueueHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_queue, parent, false)
        return QueueHolder(view)
    }

    override fun onBindViewHolder(holder: QueueHolder, position: Int) = holder.bind(getItem(position))

    inner class QueueHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val art: ImageView = view.findViewById(R.id.queue_art)
        private val title: TextView = view.findViewById(R.id.queue_title)
        private val subtitle: TextView = view.findViewById(R.id.queue_subtitle)
        private val remove: ImageButton = view.findViewById(R.id.queue_remove)

        fun bind(item: EpisodeWithPodcast) {
            val episode = item.episode
            title.text = episode.title
            subtitle.text = buildSubtitle(item)
            Glide.with(art)
                .load(episode.imageUrl ?: item.podcastArtwork)
                .transform(RoundedCorners(16))
                .placeholder(R.drawable.bg_art_placeholder)
                .into(art)
            itemView.setOnClickListener { onPlay(episode) }
            remove.setOnClickListener { onRemove(episode) }
        }

        private fun buildSubtitle(item: EpisodeWithPodcast): String {
            val parts = mutableListOf(item.podcastTitle)
            Format.durationLabel(item.episode.durationMs).takeIf { it.isNotBlank() }?.let { parts += it }
            return parts.joinToString("  ·  ")
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<EpisodeWithPodcast>() {
            override fun areItemsTheSame(old: EpisodeWithPodcast, new: EpisodeWithPodcast) =
                old.episode.id == new.episode.id

            override fun areContentsTheSame(old: EpisodeWithPodcast, new: EpisodeWithPodcast) = old == new
        }
    }
}
