package be.miro.onecast.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers where a podcast audio URL finally redirected to, and starts later opens of the same
 * URL at that CDN address instead of walking the tracking chain again.
 *
 * Podcast audio URLs are tracking links that bounce through several hosts before reaching the
 * actual file, and each hop costs a DNS lookup plus a TLS handshake — the bulk of the wait before
 * the first sound. ExoPlayer opens the URL more than once per episode: once from the start to read
 * the file header, again from a byte offset whenever it seeks (resuming mid-episode, or any skip
 * forward/back), and again after a network hiccup. Without this, every one of those pays the full
 * chain.
 *
 * Entries live only as long as the process: CDN links are often signed and expire, so a shortcut
 * that fails is dropped and the original URL retried rather than failing playback.
 */
@UnstableApi
class RedirectCachingDataSource(private val upstream: DataSource) : DataSource {

    override fun open(dataSpec: DataSpec): Long {
        val original = dataSpec.uri
        if (!original.isRemote()) return upstream.open(dataSpec)

        resolved[original.toString()]?.let { shortcut ->
            try {
                return upstream.open(dataSpec.buildUpon().setUri(shortcut).build())
            } catch (e: IOException) {
                resolved.remove(original.toString())
                runCatching { upstream.close() }
            }
        }

        val bytes = upstream.open(dataSpec)
        // getUri() reports where the redirects actually landed.
        upstream.uri?.takeIf { it != original }?.let { resolved[original.toString()] = it }
        return bytes
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    private fun Uri.isRemote(): Boolean =
        scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            RedirectCachingDataSource(upstream.createDataSource())
    }

    private companion object {
        /** Original URL -> the address its redirects resolved to, for this process only. */
        val resolved = ConcurrentHashMap<String, Uri>()
    }
}
