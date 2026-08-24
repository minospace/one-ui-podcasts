package be.miro.onecast

import android.app.Application
import android.content.Context
import be.miro.onecast.data.AppDatabase
import be.miro.onecast.data.AppSettings
import be.miro.onecast.data.PodcastRepository
import be.miro.onecast.download.DownloadStore
import be.miro.onecast.download.EpisodeDownloader

/** Process-wide singletons (lightweight manual DI). */
class OnecastApp : Application() {

    private val database by lazy { AppDatabase.get(this) }

    private val downloadStore by lazy { DownloadStore(this) }

    val repository by lazy {
        PodcastRepository(
            database.podcastDao(),
            database.episodeDao(),
            database.queueDao(),
            downloadStore,
        )
    }

    val settings by lazy { AppSettings.create(this) }

    val downloads by lazy { EpisodeDownloader(this, repository, downloadStore) }
}

/** Convenience accessor for the shared repository from any Context. */
val Context.podcastRepository: PodcastRepository
    get() = (applicationContext as OnecastApp).repository

/** Convenience accessor for the shared app settings from any Context. */
val Context.appSettings: AppSettings
    get() = (applicationContext as OnecastApp).settings

/** Convenience accessor for the shared episode downloader from any Context. */
val Context.episodeDownloads: EpisodeDownloader
    get() = (applicationContext as OnecastApp).downloads
