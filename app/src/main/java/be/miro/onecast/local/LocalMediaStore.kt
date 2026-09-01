package be.miro.onecast.local

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Where a user-created podcast's own media lives: one directory per podcast under the app's private
 * files dir, so nothing needs a storage permission and everything goes away on uninstall.
 *
 * Files picked through the system picker are *copied* in rather than referenced. A picker URI only
 * carries a short-lived read grant and points at content the user can move or delete from another
 * app, so keeping the URI would leave episodes that silently stop playing; a copy is ours for as
 * long as the episode row exists.
 */
class LocalMediaStore(context: Context) {

    private val appContext = context.applicationContext

    private val root: File get() = File(appContext.filesDir, DIR_NAME)

    /** What an imported audio file turned out to be, ready to become an [be.miro.onecast.data.Episode]. */
    data class ImportedAudio(
        /** `file://` URI of the copy, for both playback and later deletion. */
        val fileUri: String,
        val title: String,
        val durationMs: Long,
        /** `file://` URI of the artwork embedded in the file, when it had any. */
        val artworkUri: String?,
    )

    private fun directoryFor(podcastId: Long): File =
        File(root, podcastId.toString()).apply { if (!exists()) mkdirs() }

    /**
     * Copies the picked audio file in and reads its tags. Returns null when the file can't be read
     * at all — one unreadable pick shouldn't abandon the rest of a multi-file selection.
     */
    suspend fun importAudio(podcastId: Long, uri: Uri): ImportedAudio? = withContext(Dispatchers.IO) {
        val displayName = displayNameOf(uri)
        val target = File(directoryFor(podcastId), "${UUID.randomUUID()}.${extensionFor(uri, displayName, "mp3")}")
        if (!copyIn(uri, target)) return@withContext null

        var title: String? = null
        var durationMs = 0L
        var artwork: ByteArray? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(target.absolutePath)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            artwork = retriever.embeddedPicture
        } catch (_: Exception) {
            // A file with no readable tags is still perfectly playable — fall back to its name.
        } finally {
            runCatching { retriever.release() }
        }

        val artworkFile = artwork?.let {
            runCatching {
                File(target.parentFile, "${target.nameWithoutExtension}.art").apply { writeBytes(it) }
            }.getOrNull()
        }

        ImportedAudio(
            fileUri = Uri.fromFile(target).toString(),
            title = title?.takeIf { it.isNotBlank() }
                ?: displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                ?: target.nameWithoutExtension,
            durationMs = durationMs,
            artworkUri = artworkFile?.let { Uri.fromFile(it).toString() },
        )
    }

    /** Copies a picked cover image in. Returns its `file://` URI, or null if it couldn't be read. */
    suspend fun importImage(podcastId: Long, uri: Uri): String? = withContext(Dispatchers.IO) {
        // Named uniquely rather than "cover": Glide keys its cache on the URI, so reusing the path
        // for a replacement cover would keep showing the old picture.
        val target = File(directoryFor(podcastId), "cover-${UUID.randomUUID()}.${extensionFor(uri, displayNameOf(uri), "jpg")}")
        if (copyIn(uri, target)) Uri.fromFile(target).toString() else null
    }

    /** Removes everything belonging to a podcast the user deleted. */
    suspend fun deleteForPodcast(podcastId: Long) = withContext(Dispatchers.IO) {
        File(root, podcastId.toString()).deleteRecursively()
        Unit
    }

    /**
     * Deletes a file this store owns. Anything else — an `http` URL from a feed, a path outside the
     * store — is ignored, so this can be pointed at any episode's URLs without checking first.
     */
    suspend fun deleteOwned(uriOrPath: String?) = withContext(Dispatchers.IO) {
        val file = fileOf(uriOrPath) ?: return@withContext
        if (!file.absolutePath.startsWith(root.absolutePath + File.separator)) return@withContext
        file.delete()
        Unit
    }

    private fun copyIn(uri: Uri, target: File): Boolean = try {
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } != null
    } catch (_: Exception) {
        target.delete()
        false
    }

    private fun displayNameOf(uri: Uri): String? = try {
        appContext.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (_: Exception) {
        null
    }

    /**
     * An extension for the local copy, from the picked file's name or its MIME type. Only used to
     * name the file — playback sniffs the container, not the name.
     */
    private fun extensionFor(uri: Uri, displayName: String?, fallback: String): String {
        val fromName = displayName?.substringAfterLast('.', "")
        if (fromName != null && fromName.length in 1..4 && fromName.all { it.isLetterOrDigit() }) {
            return fromName.lowercase()
        }
        val mimeType = appContext.contentResolver.getType(uri)
        return mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: fallback
    }

    private fun fileOf(uriOrPath: String?): File? {
        if (uriOrPath.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(uriOrPath) }.getOrNull() ?: return null
        return when (uri.scheme) {
            "file" -> uri.path?.let(::File)
            null -> File(uriOrPath)
            else -> null
        }
    }

    private companion object {
        const val DIR_NAME = "local"
    }
}
