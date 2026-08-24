package be.miro.onecast.ui.downloads

import be.miro.onecast.data.EpisodeWithPodcast
import be.miro.onecast.download.DownloadTask

/** One line on the Downloads screen: either a download in flight, or a finished one on disk. */
sealed interface DownloadRow {

    val key: Long

    /** Queued, downloading, or failed — lives in memory until it finishes or is dismissed. */
    data class Active(val task: DownloadTask) : DownloadRow {
        override val key: Long get() = task.episodeId
    }

    /** Downloaded and playable offline. */
    data class Finished(val item: EpisodeWithPodcast) : DownloadRow {
        override val key: Long get() = item.episode.id
    }
}
