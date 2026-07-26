package be.miro.onecast.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface QueueDao {

    /** The queue in play order, each row carrying its podcast title + artwork for the Up Next UI. */
    @Query(
        "SELECT e.*, p.title AS podcastTitle, p.artworkUrl AS podcastArtwork " +
            "FROM episodes e " +
            "INNER JOIN queue q ON e.id = q.episodeId " +
            "INNER JOIN podcasts p ON e.podcastId = p.id " +
            "ORDER BY q.position ASC",
    )
    fun observeQueue(): Flow<List<EpisodeWithPodcast>>

    /** Just the queued episode ids (cheap membership check for episode lists). */
    @Query("SELECT episodeId FROM queue ORDER BY position ASC")
    fun observeEpisodeIds(): Flow<List<Long>>

    @Query("SELECT * FROM queue ORDER BY position ASC")
    suspend fun getAll(): List<QueueItem>

    /** The next episode to autoplay (head of the queue), or null when empty. */
    @Query("SELECT episodeId FROM queue ORDER BY position ASC LIMIT 1")
    suspend fun firstEpisodeId(): Long?

    @Query("SELECT MAX(position) FROM queue")
    suspend fun maxPosition(): Long?

    @Query("SELECT MIN(position) FROM queue")
    suspend fun minPosition(): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: QueueItem)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<QueueItem>)

    @Query("DELETE FROM queue WHERE episodeId = :episodeId")
    suspend fun remove(episodeId: Long)

    @Query("DELETE FROM queue")
    suspend fun clear()
}
