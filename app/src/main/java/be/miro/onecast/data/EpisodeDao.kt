package be.miro.onecast.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EpisodeDao {

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY pubDate DESC")
    fun observeForPodcast(podcastId: Long): Flow<List<Episode>>

    @Query("SELECT * FROM episodes WHERE id = :id")
    fun observeById(id: Long): Flow<Episode?>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun getById(id: Long): Episode?

    /**
     * Unplayed episodes of a podcast published after [afterPubDate], oldest first — the newer
     * episodes to line up chronologically behind the one the user just started.
     */
    @Query(
        "SELECT * FROM episodes WHERE podcastId = :podcastId AND isPlayed = 0 " +
            "AND pubDate > :afterPubDate ORDER BY pubDate ASC, id ASC",
    )
    suspend fun newerUnplayed(podcastId: Long, afterPubDate: Long): List<Episode>

    /** Only new items are written; existing (podcastId, guid) rows are kept as-is. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(episodes: List<Episode>): List<Long>

    /** Marking played also clears the resume position so it won't auto-resume. */
    @Query(
        "UPDATE episodes SET isPlayed = :played, " +
            "positionMs = CASE WHEN :played THEN 0 ELSE positionMs END WHERE id = :id",
    )
    suspend fun setPlayed(id: Long, played: Boolean)

    /** Bulk mark every episode of a podcast played/unplayed; clears resume positions when played. */
    @Query(
        "UPDATE episodes SET isPlayed = :played, " +
            "positionMs = CASE WHEN :played THEN 0 ELSE positionMs END WHERE podcastId = :podcastId",
    )
    suspend fun setAllPlayed(podcastId: Long, played: Boolean)

    @Query("UPDATE episodes SET positionMs = :positionMs WHERE id = :id")
    suspend fun updatePosition(id: Long, positionMs: Long)

    @Query("UPDATE episodes SET durationMs = :durationMs WHERE id = :id AND durationMs <= 0")
    suspend fun updateDurationIfUnknown(id: Long, durationMs: Long)

    /** Caches chapters fetched from a Podcasting 2.0 JSON file on first play. */
    @Query("UPDATE episodes SET chapters = :chapters WHERE id = :id")
    suspend fun updateChapters(id: Long, chapters: List<Chapter>)

    /**
     * Backfills chapter info parsed from the feed onto an episode row that already existed
     * (e.g. from before chapter support was added), without touching any chapters already
     * fetched and cached from a Podcasting 2.0 JSON chapters file.
     */
    @Query(
        "UPDATE episodes SET chapters = :chapters, chaptersUrl = :chaptersUrl " +
            "WHERE podcastId = :podcastId AND guid = :guid AND chapters = '[]' AND chaptersUrl IS NULL",
    )
    suspend fun backfillChapters(podcastId: Long, guid: String, chapters: List<Chapter>, chaptersUrl: String?)

    /** Backfills per-episode artwork onto a row that already existed before image support. */
    @Query("UPDATE episodes SET imageUrl = :imageUrl WHERE podcastId = :podcastId AND guid = :guid AND imageUrl IS NULL")
    suspend fun backfillImage(podcastId: Long, guid: String, imageUrl: String?)

    // ── Downloads ──────────────────────────────────────────────────────────

    /** Downloaded episodes, newest download first, each carrying its podcast title + artwork. */
    @Query(
        "SELECT e.*, p.title AS podcastTitle, p.artworkUrl AS podcastArtwork " +
            "FROM episodes e " +
            "INNER JOIN podcasts p ON e.podcastId = p.id " +
            "WHERE e.downloadPath IS NOT NULL " +
            "ORDER BY e.downloadedAt DESC",
    )
    fun observeDownloaded(): Flow<List<EpisodeWithPodcast>>

    /** Ids of the episodes that have a downloaded file (cheap badge check for episode lists). */
    @Query("SELECT id FROM episodes WHERE downloadPath IS NOT NULL")
    fun observeDownloadedIds(): Flow<List<Long>>

    @Query("SELECT * FROM episodes WHERE downloadPath IS NOT NULL")
    suspend fun getDownloaded(): List<Episode>

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId AND downloadPath IS NOT NULL")
    suspend fun getDownloadedForPodcast(podcastId: Long): List<Episode>

    /** Records a finished download; passing nulls/zeroes clears it again. */
    @Query(
        "UPDATE episodes SET downloadPath = :path, downloadSizeBytes = :sizeBytes, " +
            "downloadedAt = :downloadedAt WHERE id = :id",
    )
    suspend fun setDownload(id: Long, path: String?, sizeBytes: Long, downloadedAt: Long)
}
