package be.miro.onecast.ui.downloads

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import be.miro.onecast.R
import be.miro.onecast.data.EpisodeWithPodcast
import be.miro.onecast.databinding.ActivityDownloadsBinding
import be.miro.onecast.download.DownloadTask
import be.miro.onecast.ui.Format
import be.miro.onecast.ui.MediaActivity
import be.miro.onecast.ui.player.PlayerActivity
import kotlinx.coroutines.launch

/** Every downloaded episode, plus whatever is downloading right now. */
class DownloadsActivity : MediaActivity() {

    private lateinit var binding: ActivityDownloadsBinding
    private lateinit var adapter: DownloadsAdapter

    private var active: List<DownloadTask> = emptyList()
    private var finished: List<EpisodeWithPodcast> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyAmoledBackground(binding.toolbarLayout)

        binding.toolbarLayout.setNavigationButtonAsBack()

        adapter = DownloadsAdapter(
            onPlay = ::play,
            onDelete = ::confirmDelete,
            onCancel = { downloads.cancel(it.episodeId) },
            // Retry what the user originally asked for, video choice included.
            onRetry = { downloads.enqueue(it.episodeId, it.includeVideo) },
        )
        binding.downloadList.layoutManager = LinearLayoutManager(this)
        binding.downloadList.adapter = adapter

        binding.miniPlayer.bind(playerConnection) { openPlayer() }
        playerConnection.onUpdate = { binding.miniPlayer.refresh(playerConnection) }

        observe()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    repository.observeDownloads().collect {
                        finished = it
                        render()
                    }
                }
                launch {
                    downloads.tasks.collect {
                        active = it
                        render()
                    }
                }
            }
        }
    }

    private fun render() {
        val rows = active.map { DownloadRow.Active(it) } + finished.map { DownloadRow.Finished(it) }
        adapter.submitList(rows)
        binding.emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        invalidateOptionsMenu()
    }

    private fun play(item: EpisodeWithPodcast) {
        lifecycleScope.launch {
            val episode = item.episode
            val podcast = repository.getPodcast(episode.podcastId)
            val startAt = if (episode.isPlayed) 0L else episode.positionMs
            playerConnection.loadEpisode(episode, podcast, startAt)
            repository.onEpisodeStarted(episode.id, settings.autoQueueNewer)
        }
    }

    private fun confirmDelete(item: EpisodeWithPodcast) {
        AlertDialog.Builder(this)
            .setTitle(R.string.downloads_delete)
            .setMessage(getString(R.string.downloads_delete_message, item.episode.title))
            .setPositiveButton(R.string.downloads_delete) { _, _ ->
                lifecycleScope.launch {
                    repository.deleteDownload(item.episode.id)
                    toast(getString(R.string.downloads_deleted))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openPlayer() {
        val intent = Intent(this, PlayerActivity::class.java)
        val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
            this, binding.miniPlayer.artView, "player_art",
        )
        startActivity(intent, options.toBundle())
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.downloads, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_delete_all_downloads)?.isVisible = finished.isNotEmpty()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_delete_all_downloads -> {
            confirmDeleteAll()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun confirmDeleteAll() {
        val count = finished.size
        val bytes = finished.sumOf { it.episode.downloadSizeBytes }
        AlertDialog.Builder(this)
            .setTitle(R.string.downloads_delete_all)
            .setMessage(
                resources.getQuantityString(
                    R.plurals.downloads_delete_all_message,
                    count,
                    count,
                    Format.fileSize(bytes),
                ),
            )
            .setPositiveButton(R.string.downloads_delete) { _, _ ->
                lifecycleScope.launch {
                    repository.deleteAllDownloads()
                    toast(getString(R.string.downloads_deleted_all))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, DownloadsActivity::class.java))
        }
    }
}
