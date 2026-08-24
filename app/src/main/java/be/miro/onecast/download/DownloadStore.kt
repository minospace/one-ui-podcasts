package be.miro.onecast.download

import android.content.Context
import java.io.File

/**
 * Where downloaded episode audio lives: one file per episode under the app's private files dir, so
 * it needs no storage permission and is cleaned up when the app is uninstalled.
 *
 * A download in flight writes to a `.part` sibling and is only renamed into place once complete, so
 * a partial file can never be mistaken for a finished download.
 */
class DownloadStore(context: Context) {

    private val appContext = context.applicationContext

    val directory: File
        get() = File(appContext.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    /** Final resting place for an episode's audio, extension guessed from its URL. */
    fun fileFor(episodeId: Long, audioUrl: String): File =
        File(directory, "$episodeId.${extensionOf(audioUrl)}")

    /** The partial file a download writes into before it's renamed to [fileFor]. */
    fun partFileFor(episodeId: Long): File = File(directory, "$episodeId.part")

    /** Free space on the volume holding the downloads, in bytes. */
    fun usableSpaceBytes(): Long = directory.usableSpace

    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        val file = File(path)
        if (file.exists()) file.delete()
    }

    /** Deletes every file in the download directory that isn't in [keepPaths] (orphans, leftovers). */
    fun deleteExcept(keepPaths: Collection<String>) {
        val keep = keepPaths.toHashSet()
        directory.listFiles()?.forEach { file ->
            if (file.absolutePath !in keep) file.delete()
        }
    }

    /**
     * The audio extension from a URL's path, ignoring any query string, falling back to `mp3`.
     * Only used to name the local file — playback sniffs the container, not the name.
     */
    private fun extensionOf(audioUrl: String): String {
        val path = audioUrl.substringBefore('?').substringBefore('#')
        val extension = path.substringAfterLast('.', "")
        return if (extension.length in 1..4 && extension.all { it.isLetterOrDigit() }) {
            extension.lowercase()
        } else {
            "mp3"
        }
    }

    private companion object {
        const val DIR_NAME = "downloads"
    }
}
