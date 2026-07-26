package be.miro.onecast.download

/** Where a download is in its (short) life. Finished downloads live in the database instead. */
enum class DownloadState { QUEUED, RUNNING, FAILED }

/**
 * One in-flight (or just-failed) episode download. Held in memory only — progress changes far too
 * often to persist, and a download that doesn't survive a process death is simply retried.
 */
data class DownloadTask(
    val episodeId: Long,
    val state: DownloadState,
    val title: String = "",
    val podcastTitle: String = "",
    val artworkUrl: String? = null,
    val downloadedBytes: Long = 0,
    /** Total size from the server's Content-Length, or 0 when it didn't say. */
    val totalBytes: Long = 0,
    /** Why it failed, for the Downloads row and the notification. */
    val error: String? = null,
) {
    /** 0..100, or null when the total size is unknown (indeterminate progress). */
    val percent: Int?
        get() = if (totalBytes > 0) {
            ((downloadedBytes * 100 / totalBytes).coerceIn(0, 100)).toInt()
        } else {
            null
        }
}
