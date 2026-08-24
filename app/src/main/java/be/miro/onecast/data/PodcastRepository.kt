package be.miro.onecast.data

import be.miro.onecast.download.DownloadStore
import be.miro.onecast.feed.ChaptersClient
import be.miro.onecast.feed.FeedFetcher
import be.miro.onecast.feed.ItunesSearchClient
import be.miro.onecast.feed.ParsedFeed
import be.miro.onecast.feed.PodcastSearchResult
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import java.io.File

/** Single source of truth: wraps the DAOs and the network feed/search clients. */
class PodcastRepository(
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val queueDao: QueueDao,
    private val downloadStore: DownloadStore,
    httpClient: OkHttpClient = OkHttpClient(),
) {
    private val feedFetcher = FeedFetcher(httpClient)
    private val itunes = ItunesSearchClient(httpClient)
    private val chaptersClient = ChaptersClient(httpClient)

    fun observePodcasts(): Flow<List<Podcast>> = podcastDao.observeAll()
    fun observePodcast(id: Long): Flow<Podcast?> = podcastDao.observeById(id)
    fun observeEpisodes(podcastId: Long): Flow<List<Episode>> = episodeDao.observeForPodcast(podcastId)
    fun observeEpisode(id: Long): Flow<Episode?> = episodeDao.observeById(id)

    suspend fun getPodcast(id: Long): Podcast? = podcastDao.getById(id)
    suspend fun getEpisode(id: Long): Episode? = episodeDao.getById(id)

    suspend fun search(term: String): List<PodcastSearchResult> = itunes.search(term)

    /** Subscribe to a feed URL (idempotent). Returns the podcast id. */
    suspend fun subscribe(feedUrl: String): Long {
        val parsed = feedFetcher.fetch(feedUrl)
        val existing = podcastDao.getByFeedUrl(feedUrl)
        val podcastId: Long = if (existing == null) {
            val inserted = podcastDao.insert(
                Podcast(
                    feedUrl = feedUrl,
                    title = parsed.title ?: feedUrl,
                    author = parsed.author,
                    description = parsed.description,
                    artworkUrl = parsed.imageUrl,
                    lastRefreshed = System.currentTimeMillis(),
                ),
            )
            if (inserted == -1L) podcastDao.getByFeedUrl(feedUrl)!!.id else inserted
        } else {
            updatePodcastFromFeed(existing, parsed)
            existing.id
        }
        insertNewEpisodes(podcastId, parsed)
        return podcastId
    }

    /** Re-fetch a subscribed feed and add any new episodes. */
    suspend fun refresh(podcastId: Long) {
        val podcast = podcastDao.getById(podcastId) ?: return
        val parsed = feedFetcher.fetch(podcast.feedUrl)
        updatePodcastFromFeed(podcast, parsed)
        insertNewEpisodes(podcastId, parsed)
    }

    /** Refresh subscribed feeds not refreshed within [maxAgeMs] (default 30 min); broken feeds are skipped. */
    suspend fun refreshStalePodcasts(maxAgeMs: Long = 30 * 60 * 1000L) {
        val now = System.currentTimeMillis()
        for (podcast in podcastDao.getAll()) {
            if (now - podcast.lastRefreshed < maxAgeMs) continue
            try {
                refresh(podcast.id)
            } catch (_: Exception) {
            }
        }
    }

    /** Unsubscribing throws away the episode rows, so their downloaded files go with them. */
    suspend fun unsubscribe(podcastId: Long) {
        for (episode in episodeDao.getDownloadedForPodcast(podcastId)) {
            downloadStore.delete(episode.downloadPath)
        }
        podcastDao.deleteById(podcastId)
    }

    // ── Downloads ──────────────────────────────────────────────────────────

    fun observeDownloads(): Flow<List<EpisodeWithPodcast>> = episodeDao.observeDownloaded()
    fun observeDownloadedEpisodeIds(): Flow<List<Long>> = episodeDao.observeDownloadedIds()

    /** Records a completed download; [hasVideo] is true when the saved file carries the video track. */
    suspend fun setDownloaded(episodeId: Long, path: String, sizeBytes: Long, hasVideo: Boolean) =
        episodeDao.setDownload(episodeId, path, sizeBytes, System.currentTimeMillis(), hasVideo)

    /** Deletes an episode's downloaded audio; the episode itself (and its progress) stays. */
    suspend fun deleteDownload(episodeId: Long) {
        val episode = episodeDao.getById(episodeId) ?: return
        downloadStore.delete(episode.downloadPath)
        episodeDao.setDownload(episodeId, null, 0, 0, false)
    }

    suspend fun deleteAllDownloads() {
        for (episode in episodeDao.getDownloaded()) {
            downloadStore.delete(episode.downloadPath)
            episodeDao.setDownload(episode.id, null, 0, 0, false)
        }
    }

    /**
     * Drops files in the download directory that no episode row points at any more (left behind by
     * an unsubscribe or a crash mid-download), and clears rows whose file has vanished.
     */
    suspend fun pruneDownloads(activePaths: () -> Collection<String> = ::emptyList) {
        val downloaded = episodeDao.getDownloaded()
        val known = downloaded.mapNotNull { it.downloadPath }
        // Asked for here, not passed in already-evaluated: the database read above suspends, and a
        // download started while it was in flight would be missing from a list snapshotted before
        // it — the sweep would delete the part file out from under the running transfer.
        downloadStore.deleteExcept(known + activePaths())
        for (episode in downloaded) {
            val path = episode.downloadPath ?: continue
            if (!File(path).exists()) episodeDao.setDownload(episode.id, null, 0, 0, false)
        }
    }

    // ── Up Next queue ──────────────────────────────────────────────────────

    fun observeQueue(): Flow<List<EpisodeWithPodcast>> = queueDao.observeQueue()
    fun observeQueueEpisodeIds(): Flow<List<Long>> = queueDao.observeEpisodeIds()

    /** Append an episode to the end of the queue (no-op if already queued). */
    suspend fun addToQueue(episodeId: Long) {
        val end = queueDao.maxPosition() ?: 0L
        queueDao.insert(QueueItem(episodeId, end + 1))
    }

    /** Move an episode to the front of the queue so it plays next. */
    suspend fun playNext(episodeId: Long) {
        val front = queueDao.minPosition() ?: 0L
        // Re-insert at the front; remove first so an already-queued episode moves rather than sticks.
        queueDao.remove(episodeId)
        queueDao.insert(QueueItem(episodeId, front - 1))
    }

    suspend fun removeFromQueue(episodeId: Long) = queueDao.remove(episodeId)
    suspend fun clearQueue() = queueDao.clear()

    /**
     * The user just started [episodeId]: it's now the current episode, so drop it from the queue,
     * and — when [autoQueueNewer] is on — line up that podcast's newer unplayed episodes behind it.
     */
    suspend fun onEpisodeStarted(episodeId: Long, autoQueueNewer: Boolean) {
        queueDao.remove(episodeId)
        if (autoQueueNewer) autoEnqueueNewerEpisodes(episodeId)
    }

    /** The episode id at the head of the queue (next to autoplay), without removing it. */
    suspend fun peekNextId(): Long? = queueDao.firstEpisodeId()

    /** Append the current episode's newer, still-unplayed siblings to the queue, oldest first. */
    private suspend fun autoEnqueueNewerEpisodes(currentEpisodeId: Long) {
        val current = episodeDao.getById(currentEpisodeId) ?: return
        val newer = episodeDao.newerUnplayed(current.podcastId, current.pubDate)
        if (newer.isEmpty()) return
        val alreadyQueued = queueDao.getAll().mapTo(HashSet()) { it.episodeId }
        var position = queueDao.maxPosition() ?: 0L
        val toAdd = newer
            .filter { it.id != currentEpisodeId && it.id !in alreadyQueued }
            .map { QueueItem(it.id, ++position) }
        if (toAdd.isNotEmpty()) queueDao.insertAll(toAdd)
    }

    suspend fun setPlayed(episodeId: Long, played: Boolean) = episodeDao.setPlayed(episodeId, played)
    suspend fun setAllPlayed(podcastId: Long, played: Boolean) = episodeDao.setAllPlayed(podcastId, played)
    suspend fun savePosition(episodeId: Long, positionMs: Long) = episodeDao.updatePosition(episodeId, positionMs)
    suspend fun saveDurationIfUnknown(episodeId: Long, durationMs: Long) =
        episodeDao.updateDurationIfUnknown(episodeId, durationMs)

    /**
     * Returns the episode's chapters, lazily fetching and caching a Podcasting 2.0
     * JSON chapters file on first request when no inline chapters were in the feed.
     */
    suspend fun ensureChapters(episodeId: Long): List<Chapter> {
        val episode = episodeDao.getById(episodeId) ?: return emptyList()
        if (episode.chapters.isNotEmpty()) return episode.chapters
        val url = episode.chaptersUrl ?: return emptyList()
        return try {
            chaptersClient.fetch(url).also {
                if (it.isNotEmpty()) episodeDao.updateChapters(episodeId, it)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun updatePodcastFromFeed(existing: Podcast, parsed: ParsedFeed) {
        podcastDao.update(
            existing.copy(
                title = parsed.title ?: existing.title,
                author = parsed.author ?: existing.author,
                description = parsed.description ?: existing.description,
                artworkUrl = parsed.imageUrl ?: existing.artworkUrl,
                lastRefreshed = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun insertNewEpisodes(podcastId: Long, parsed: ParsedFeed) {
        val episodes = parsed.episodes.map { e ->
            Episode(
                podcastId = podcastId,
                guid = e.guid,
                title = e.title,
                description = e.description,
                // A video-only item has no audio enclosure to fall back on: play the video file
                // (its audio track) rather than dropping the episode.
                audioUrl = e.audioUrl ?: e.videoUrl.orEmpty(),
                videoUrl = e.videoUrl,
                pubDate = e.pubDate,
                durationMs = e.durationMs,
                imageUrl = e.imageUrl,
                chapters = e.chapters,
                chaptersUrl = e.chaptersUrl,
            )
        }
        // IGNORE conflicts on (podcastId, guid) → only genuinely new episodes are added.
        episodeDao.insertAll(episodes)
        // Episodes that already existed (e.g. added before chapter/image support was introduced)
        // were skipped by the insert above; backfill that info from this parse.
        for (e in episodes) {
            if (e.chapters.isNotEmpty() || e.chaptersUrl != null) {
                episodeDao.backfillChapters(podcastId, e.guid, e.chapters, e.chaptersUrl)
            }
            if (e.imageUrl != null) {
                episodeDao.backfillImage(podcastId, e.guid, e.imageUrl)
            }
            if (e.videoUrl != null) {
                episodeDao.backfillVideo(podcastId, e.guid, e.videoUrl)
            }
        }
    }
}
