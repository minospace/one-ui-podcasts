package be.miro.onecast.data

import androidx.room.Embedded

/**
 * An [Episode] joined with its podcast's title and artwork, so a list that mixes podcasts (Up Next,
 * Downloads) can render a row (which podcast, fallback art) without a second lookup per episode.
 */
data class EpisodeWithPodcast(
    @Embedded val episode: Episode,
    val podcastTitle: String,
    val podcastArtwork: String?,
)
