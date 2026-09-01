package be.miro.onecast.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A podcast in the library. Usually a subscribed RSS feed; when [isLocal] is set it's one the user
 * created themselves and filled with their own audio files (see `local/LocalMediaStore`).
 */
@Entity(
    tableName = "podcasts",
    indices = [Index(value = ["feedUrl"], unique = true)],
)
data class Podcast(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedUrl: String,
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val artworkUrl: String? = null,
    val lastRefreshed: Long = 0,
    /**
     * True for a podcast the user created in the app rather than subscribed to. It has no feed to
     * fetch — [feedUrl] is only a synthetic `onecast:local/<uuid>` value keeping the unique index
     * happy — so it is never refreshed, and its episodes point at files copied into app storage.
     */
    val isLocal: Boolean = false,
)
