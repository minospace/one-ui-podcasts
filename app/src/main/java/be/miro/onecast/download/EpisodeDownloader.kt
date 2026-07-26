package be.miro.onecast.download

import android.content.Context
import android.os.SystemClock
import be.miro.onecast.data.Episode
import be.miro.onecast.data.PodcastRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Downloads episode audio for offline playback — always because the user asked for it; nothing is
 * ever downloaded automatically.
 *
 * Downloads run one at a time off a small in-memory queue ([tasks]), while [DownloadService] keeps
 * the process in the foreground and mirrors that queue into a live progress notification. A
 * download that fails, or that stops making progress for [STALL_TIMEOUT_MS], is aborted: its
 * partial file is deleted and the user gets a notification saying why.
 */
class EpisodeDownloader(
    context: Context,
    private val repository: PodcastRepository,
    private val store: DownloadStore,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Guards [_tasks] mutations and the [worker] handoff. */
    private val lock = Any()

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())

    /** Queued, running and just-failed downloads. Finished ones live in the database. */
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private var worker: Job? = null

    @Volatile
    private var active: ActiveDownload? = null

    // Podcast media URLs redirect through several tracking/CDN hops, often crossing http<->https
    // (OkHttp follows both by default). No call timeout — episodes are large — but a read that
    // goes quiet for READ_TIMEOUT_SECONDS fails, which the stall watchdog below backs up.
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** Queue an episode for download (no-op if it's already queued or downloading). */
    fun enqueue(episodeId: Long) {
        synchronized(lock) {
            if (_tasks.value.any { it.episodeId == episodeId && it.state != DownloadState.FAILED }) return
            // A previous failed attempt for the same episode is replaced by this fresh one.
            _tasks.value = _tasks.value.filterNot { it.episodeId == episodeId } +
                DownloadTask(episodeId, DownloadState.QUEUED)
        }
        DownloadNotifications.clearFailure(appContext, episodeId)
        DownloadService.start(appContext)
        ensureWorker()
        // Fill in the title/artwork so a queued row and the notification aren't blank while waiting.
        scope.launch { loadMetadata(episodeId) }
    }

    /** Abort a queued or running download; a running one's partial file is deleted. */
    fun cancel(episodeId: Long) {
        synchronized(lock) {
            _tasks.value = _tasks.value.filterNot { it.episodeId == episodeId }
        }
        active?.takeIf { it.episodeId == episodeId }?.let {
            it.cancelledByUser = true
            it.abort()
        }
        DownloadNotifications.clearFailure(appContext, episodeId)
    }

    fun cancelAll() {
        val ids = synchronized(lock) {
            val ids = _tasks.value.map { it.episodeId }
            _tasks.value = emptyList()
            ids
        }
        active?.let {
            it.cancelledByUser = true
            it.abort()
        }
        ids.forEach { DownloadNotifications.clearFailure(appContext, it) }
    }

    /** True while the episode is queued or downloading (a failed task doesn't count). */
    fun isPending(episodeId: Long): Boolean =
        _tasks.value.any { it.episodeId == episodeId && it.state != DownloadState.FAILED }

    /** Paths a download is currently writing to, so a prune doesn't sweep them away. */
    fun activePaths(): List<String> =
        _tasks.value.filter { it.state != DownloadState.FAILED }
            .map { store.partFileFor(it.episodeId).absolutePath }

    private fun ensureWorker() {
        synchronized(lock) {
            if (worker != null) return
            worker = scope.launch { drainQueue() }
        }
    }

    private suspend fun drainQueue() {
        while (true) {
            // Clearing [worker] under the same lock that picks the next task means an enqueue can
            // never slip in between "queue is empty" and "no worker running" and be left stranded.
            val next = synchronized(lock) {
                val task = _tasks.value.firstOrNull { it.state == DownloadState.QUEUED }
                if (task == null) worker = null
                task
            } ?: return
            runDownload(next.episodeId)
        }
    }

    private suspend fun runDownload(episodeId: Long) {
        val episode = repository.getEpisode(episodeId)
        if (episode == null) {
            removeTask(episodeId)
            return
        }
        if (!episode.downloadPath.isNullOrBlank() && File(episode.downloadPath).exists()) {
            removeTask(episodeId)
            return
        }

        updateTask(episodeId) { it.copy(state = DownloadState.RUNNING, downloadedBytes = 0, error = null) }
        loadMetadata(episodeId)

        val partFile = store.partFileFor(episodeId)
        val targetFile = store.fileFor(episodeId, episode.audioUrl)
        val current = ActiveDownload(episodeId)
        active = current
        val watchdog = scope.launch { watchForStall(current) }
        try {
            val bytes = withContext(Dispatchers.IO) { writeToFile(episode, partFile, current) }
            if (!partFile.renameTo(targetFile)) throw IOException("Couldn't save the downloaded file")
            repository.setDownloaded(episodeId, targetFile.absolutePath, bytes)
            removeTask(episodeId)
        } catch (e: CancellationException) {
            partFile.delete()
            removeTask(episodeId)
            throw e
        } catch (e: Throwable) {
            // Abort means abort: nothing half-downloaded is kept around.
            partFile.delete()
            if (current.cancelledByUser) {
                removeTask(episodeId)
            } else {
                failTask(episodeId, describe(e, current))
            }
        } finally {
            watchdog.cancel()
            active = null
        }
    }

    /** Streams the response into [partFile], publishing progress as it goes. Returns bytes written. */
    private fun writeToFile(episode: Episode, partFile: File, current: ActiveDownload): Long {
        val request = Request.Builder()
            .url(episode.audioUrl)
            .header("User-Agent", USER_AGENT)
            .build()
        val call = client.newCall(request)
        current.call = call
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("Server returned HTTP ${response.code}")
            val body = response.body ?: throw IOException("The server sent no audio")
            val total = body.contentLength().takeIf { it > 0 } ?: 0L
            if (total > 0 && store.usableSpaceBytes() < total + FREE_SPACE_HEADROOM_BYTES) {
                throw IOException("Not enough storage space")
            }
            updateTask(episode.id) { it.copy(totalBytes = total) }

            var written = 0L
            var lastPublishedAt = 0L
            body.byteStream().use { input ->
                FileOutputStream(partFile).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        val now = SystemClock.elapsedRealtime()
                        current.lastProgressAt = now
                        if (now - lastPublishedAt >= PROGRESS_PUBLISH_MS) {
                            lastPublishedAt = now
                            val soFar = written
                            updateTask(episode.id) { it.copy(downloadedBytes = soFar) }
                        }
                    }
                    output.flush()
                }
            }
            // A connection dropped by a proxy can end "cleanly" mid-file; a short file would play
            // as a truncated episode, so treat it as a failure rather than a finished download.
            if (total > 0 && written < total) throw IOException("The download ended early")
            if (written <= 0L) throw IOException("The server sent no audio")
            return written
        }
    }

    /**
     * Aborts a download that has stopped moving. OkHttp's read timeout catches a dead socket, but
     * not a server that trickles a handful of bytes now and then, which this does.
     */
    private suspend fun watchForStall(current: ActiveDownload) {
        while (coroutineContext.isActive) {
            delay(STALL_CHECK_MS)
            if (SystemClock.elapsedRealtime() - current.lastProgressAt > STALL_TIMEOUT_MS) {
                current.stalled = true
                current.abort()
                return
            }
        }
    }

    private suspend fun loadMetadata(episodeId: Long) {
        val episode = repository.getEpisode(episodeId) ?: return
        val podcast = repository.getPodcast(episode.podcastId)
        updateTask(episodeId) {
            it.copy(
                title = episode.title,
                podcastTitle = podcast?.title.orEmpty(),
                artworkUrl = episode.imageUrl ?: podcast?.artworkUrl,
            )
        }
    }

    private fun describe(error: Throwable, current: ActiveDownload): String = when {
        current.stalled -> "The download stopped responding"
        error is UnknownHostException -> "No internet connection"
        error is SocketTimeoutException -> "The server stopped responding"
        else -> error.message?.takeIf { it.isNotBlank() } ?: "Something went wrong"
    }

    private fun updateTask(episodeId: Long, transform: (DownloadTask) -> DownloadTask) {
        synchronized(lock) {
            _tasks.value = _tasks.value.map { if (it.episodeId == episodeId) transform(it) else it }
        }
    }

    private fun removeTask(episodeId: Long) {
        synchronized(lock) {
            _tasks.value = _tasks.value.filterNot { it.episodeId == episodeId }
        }
    }

    private fun failTask(episodeId: Long, reason: String) {
        val failed = synchronized(lock) {
            val task = _tasks.value.firstOrNull { it.episodeId == episodeId } ?: return
            val updated = task.copy(state = DownloadState.FAILED, error = reason)
            _tasks.value = _tasks.value.map { if (it.episodeId == episodeId) updated else it }
            updated
        }
        DownloadNotifications.notifyFailed(appContext, failed)
    }

    /** The one download currently in flight, and the handles needed to abort it mid-read. */
    private class ActiveDownload(val episodeId: Long) {
        @Volatile
        var call: Call? = null

        @Volatile
        var cancelledByUser = false

        @Volatile
        var stalled = false

        @Volatile
        var lastProgressAt = SystemClock.elapsedRealtime()

        /** Cancelling the call makes the blocking read throw straight away. */
        fun abort() {
            call?.cancel()
        }
    }

    private companion object {
        const val USER_AGENT = "OnecastApp/1.0 (Android)"
        const val BUFFER_BYTES = 64 * 1024
        const val READ_TIMEOUT_SECONDS = 30L
        const val STALL_CHECK_MS = 5_000L
        const val STALL_TIMEOUT_MS = 60_000L
        const val PROGRESS_PUBLISH_MS = 250L

        /** Leave a little breathing room rather than filling the volume to the last byte. */
        const val FREE_SPACE_HEADROOM_BYTES = 50L * 1024 * 1024
    }
}
