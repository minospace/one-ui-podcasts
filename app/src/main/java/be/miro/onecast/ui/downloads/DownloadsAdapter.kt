package be.miro.onecast.ui.downloads

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import be.miro.onecast.R
import be.miro.onecast.data.EpisodeWithPodcast
import be.miro.onecast.download.DownloadState
import be.miro.onecast.download.DownloadTask
import be.miro.onecast.ui.Format
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners

/**
 * The Downloads list: downloads in flight at the top (with progress and a ✕ to abort), finished
 * ones below (tap to play offline, bin icon to delete). A failed download stays as a row that
 * retries on tap until it's dismissed.
 */
class DownloadsAdapter(
    private val onPlay: (EpisodeWithPodcast) -> Unit,
    private val onDelete: (EpisodeWithPodcast) -> Unit,
    private val onCancel: (DownloadTask) -> Unit,
    private val onRetry: (DownloadTask) -> Unit,
) : ListAdapter<DownloadRow, RecyclerView.ViewHolder>(DIFF) {

    override fun getItemViewType(position: Int): Int =
        if (getItem(position) is DownloadRow.Active) TYPE_ACTIVE else TYPE_FINISHED

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_ACTIVE) {
            ActiveHolder(inflater.inflate(R.layout.item_download_active, parent, false))
        } else {
            FinishedHolder(inflater.inflate(R.layout.item_download, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is DownloadRow.Active -> (holder as ActiveHolder).bind(row.task)
            is DownloadRow.Finished -> (holder as FinishedHolder).bind(row.item)
        }
    }

    private inner class ActiveHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val art: ImageView = view.findViewById(R.id.download_art)
        private val title: TextView = view.findViewById(R.id.download_title)
        private val subtitle: TextView = view.findViewById(R.id.download_subtitle)
        private val progress: ProgressBar = view.findViewById(R.id.download_progress)
        private val cancel: ImageButton = view.findViewById(R.id.download_cancel)

        fun bind(task: DownloadTask) {
            val context = itemView.context
            title.text = task.title.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.download_this_episode)
            subtitle.text = statusText(task)
            loadArt(art, task.artworkUrl)

            val failed = task.state == DownloadState.FAILED
            progress.visibility = if (failed) View.GONE else View.VISIBLE
            if (!failed) {
                val percent = task.percent
                progress.isIndeterminate = percent == null
                if (percent != null) progress.progress = percent
            }

            itemView.setOnClickListener { if (failed) onRetry(task) }
            itemView.isClickable = failed
            cancel.setOnClickListener { onCancel(task) }
        }

        private fun statusText(task: DownloadTask): String {
            val context = itemView.context
            return when (task.state) {
                DownloadState.QUEUED -> context.getString(R.string.download_waiting)
                DownloadState.FAILED -> context.getString(
                    R.string.downloads_failed_row,
                    task.error ?: context.getString(R.string.download_failed),
                )
                DownloadState.RUNNING -> if (task.totalBytes > 0) {
                    context.getString(
                        R.string.download_progress_bytes,
                        Format.fileSize(task.downloadedBytes),
                        Format.fileSize(task.totalBytes),
                    )
                } else {
                    Format.fileSize(task.downloadedBytes)
                }
            }
        }
    }

    private inner class FinishedHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val art: ImageView = view.findViewById(R.id.download_art)
        private val title: TextView = view.findViewById(R.id.download_title)
        private val subtitle: TextView = view.findViewById(R.id.download_subtitle)
        private val delete: ImageButton = view.findViewById(R.id.download_delete)

        fun bind(item: EpisodeWithPodcast) {
            val episode = item.episode
            title.text = episode.title
            subtitle.text = buildSubtitle(item)
            loadArt(art, episode.imageUrl ?: item.podcastArtwork)
            itemView.setOnClickListener { onPlay(item) }
            delete.setOnClickListener { onDelete(item) }
        }

        private fun buildSubtitle(item: EpisodeWithPodcast): String {
            val parts = mutableListOf(item.podcastTitle)
            Format.durationLabel(item.episode.durationMs).takeIf { it.isNotBlank() }?.let { parts += it }
            if (item.episode.downloadSizeBytes > 0) {
                parts += Format.fileSize(item.episode.downloadSizeBytes)
            }
            return parts.joinToString("  ·  ")
        }
    }

    private fun loadArt(view: ImageView, url: String?) {
        Glide.with(view)
            .load(url)
            .transform(RoundedCorners(16))
            .placeholder(R.drawable.bg_art_placeholder)
            .into(view)
    }

    private companion object {
        const val TYPE_ACTIVE = 0
        const val TYPE_FINISHED = 1

        val DIFF = object : DiffUtil.ItemCallback<DownloadRow>() {
            override fun areItemsTheSame(old: DownloadRow, new: DownloadRow) =
                old.key == new.key && old.javaClass == new.javaClass

            override fun areContentsTheSame(old: DownloadRow, new: DownloadRow) = old == new
        }
    }
}
