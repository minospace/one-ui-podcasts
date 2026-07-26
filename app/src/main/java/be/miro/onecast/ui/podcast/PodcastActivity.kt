package be.miro.onecast.ui.podcast

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import be.miro.onecast.R
import be.miro.onecast.data.Episode
import be.miro.onecast.data.Podcast
import be.miro.onecast.databinding.ActivityPodcastBinding
import be.miro.onecast.download.DownloadState
import be.miro.onecast.playback.MediaItems
import be.miro.onecast.ui.MediaActivity
import be.miro.onecast.ui.downloads.DownloadsActivity
import be.miro.onecast.ui.player.PlayerActivity
import be.miro.onecast.ui.queue.QueueActivity
import kotlinx.coroutines.launch

class PodcastActivity : MediaActivity() {

    private lateinit var binding: ActivityPodcastBinding
    private lateinit var adapter: EpisodeAdapter

    private var podcastId = 0L
    private var podcast: Podcast? = null
    private var episodes: List<Episode> = emptyList()
    private var hidePlayed = false
    private var queuedIds: Set<Long> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        podcastId = intent.getLongExtra(EXTRA_PODCAST_ID, 0L)
        if (podcastId == 0L) {
            finish()
            return
        }

        binding = ActivityPodcastBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyAmoledBackground(binding.toolbarLayout)

        binding.toolbarLayout.setNavigationButtonAsBack()

        adapter = EpisodeAdapter(
            onPlay = ::play,
            onTogglePlayed = ::togglePlayed,
            onLongPress = ::showEpisodeMenu,
        )
        binding.episodeList.layoutManager = LinearLayoutManager(this)
        binding.episodeList.adapter = adapter

        binding.swipeRefresh.setOnRefreshListener { refresh() }
        binding.miniPlayer.bind(playerConnection) { openPlayer() }
        playerConnection.onUpdate = {
            binding.miniPlayer.refresh(playerConnection)
            adapter.setCurrentEpisode(MediaItems.episodeId(playerConnection.controller?.currentMediaItem))
        }

        observe()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    repository.observePodcast(podcastId).collect { p ->
                        podcast = p
                        p?.let { binding.toolbarLayout.setTitle(it.title) }
                        render()
                    }
                }
                launch {
                    repository.observeEpisodes(podcastId).collect { list ->
                        episodes = list
                        render()
                    }
                }
                launch {
                    settings.observeHidePlayedEpisodes().collect { hide ->
                        hidePlayed = hide
                        invalidateOptionsMenu()
                        render()
                    }
                }
                launch {
                    repository.observeQueueEpisodeIds().collect { ids ->
                        queuedIds = ids.toHashSet()
                    }
                }
                launch {
                    downloads.tasks.collect { tasks ->
                        adapter.setDownloadingIds(
                            tasks.filter { it.state != DownloadState.FAILED }
                                .mapTo(HashSet()) { it.episodeId },
                        )
                    }
                }
            }
        }
    }

    /** Push the (optionally filtered) episode list to the adapter. */
    private fun render() {
        val visible = if (hidePlayed) episodes.filter { !it.isPlayed } else episodes
        adapter.submit(podcast, visible)
    }

    /** Open the full player, growing the artwork out of the mini-player as a shared element. */
    private fun openPlayer() {
        val intent = Intent(this, PlayerActivity::class.java)
        val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
            this, binding.miniPlayer.artView, "player_art",
        )
        startActivity(intent, options.toBundle())
    }

    private fun play(episode: Episode) {
        val item = MediaItems.fromEpisode(episode, podcast)
        val startAt = if (episode.isPlayed) 0L else episode.positionMs
        playerConnection.loadEpisode(item, startAt)
        // Starting an episode makes it "current", so drop it from the queue and (if enabled) line up
        // this podcast's newer unplayed episodes behind it.
        lifecycleScope.launch { repository.onEpisodeStarted(episode.id, settings.autoQueueNewer) }
    }

    private fun togglePlayed(episode: Episode) {
        lifecycleScope.launch { repository.setPlayed(episode.id, !episode.isPlayed) }
    }

    /** Long-press an episode: quick queue and download actions. */
    private fun showEpisodeMenu(episode: Episode, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_PLAY_NEXT, 0, R.string.queue_play_next)
        if (episode.id in queuedIds) {
            popup.menu.add(0, MENU_REMOVE, 1, R.string.queue_remove)
        } else {
            popup.menu.add(0, MENU_ADD, 1, R.string.queue_add)
        }
        when {
            downloads.isPending(episode.id) ->
                popup.menu.add(0, MENU_CANCEL_DOWNLOAD, 2, R.string.download_cancel_download)
            episode.downloadPath != null ->
                popup.menu.add(0, MENU_DELETE_DOWNLOAD, 2, R.string.downloads_delete)
            else -> popup.menu.add(0, MENU_DOWNLOAD, 2, R.string.downloads_download)
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_PLAY_NEXT -> queueAction(episode, R.string.queue_added_next) { repository.playNext(it) }
                MENU_ADD -> queueAction(episode, R.string.queue_added) { repository.addToQueue(it) }
                MENU_REMOVE -> queueAction(episode, R.string.queue_removed) { repository.removeFromQueue(it) }
                MENU_DOWNLOAD -> {
                    downloads.enqueue(episode.id)
                    toast(getString(R.string.downloads_started))
                    true
                }
                MENU_CANCEL_DOWNLOAD -> {
                    downloads.cancel(episode.id)
                    toast(getString(R.string.downloads_cancelled))
                    true
                }
                MENU_DELETE_DOWNLOAD -> queueAction(episode, R.string.downloads_deleted) {
                    repository.deleteDownload(it)
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun queueAction(
        episode: Episode,
        messageRes: Int,
        action: suspend (Long) -> Unit,
    ): Boolean {
        lifecycleScope.launch {
            action(episode.id)
            toast(getString(messageRes))
        }
        return true
    }

    private fun refresh() {
        lifecycleScope.launch {
            try {
                repository.refresh(podcastId)
            } catch (e: Exception) {
                toast("Couldn't refresh: ${e.message}")
            } finally {
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.podcast, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_hide_played)?.isChecked = hidePlayed
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        if (onMenuItem(item.itemId)) true else super.onOptionsItemSelected(item)

    private fun onMenuItem(itemId: Int): Boolean = when (itemId) {
        R.id.action_refresh -> {
            binding.swipeRefresh.isRefreshing = true
            refresh()
            true
        }
        R.id.action_queue -> {
            QueueActivity.start(this)
            true
        }
        R.id.action_downloads -> {
            DownloadsActivity.start(this)
            true
        }
        R.id.action_hide_played -> {
            // Persisting flips the setting Flow, which updates the checkbox and re-renders.
            settings.hidePlayedEpisodes = !hidePlayed
            true
        }
        R.id.action_mark_all_played -> {
            confirmMarkAll(played = true)
            true
        }
        R.id.action_mark_all_unplayed -> {
            confirmMarkAll(played = false)
            true
        }
        R.id.action_unsubscribe -> {
            confirmUnsubscribe()
            true
        }
        else -> false
    }

    private fun confirmMarkAll(played: Boolean) {
        if (episodes.isEmpty()) return
        val verb = if (played) "played" else "unplayed"
        AlertDialog.Builder(this)
            .setTitle(if (played) "Mark all as played" else "Mark all as unplayed")
            .setMessage("Mark all ${episodes.size} episodes as $verb?")
            .setPositiveButton(if (played) "Mark played" else "Mark unplayed") { _, _ ->
                lifecycleScope.launch {
                    repository.setAllPlayed(podcastId, played)
                    toast("All episodes marked as $verb")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmUnsubscribe() {
        AlertDialog.Builder(this)
            .setTitle("Unsubscribe")
            .setMessage("Remove \"${podcast?.title ?: "this podcast"}\" and its episodes?")
            .setPositiveButton("Unsubscribe") { _, _ ->
                lifecycleScope.launch {
                    repository.unsubscribe(podcastId)
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val EXTRA_PODCAST_ID = "podcast_id"
        private const val MENU_PLAY_NEXT = 1
        private const val MENU_ADD = 2
        private const val MENU_REMOVE = 3
        private const val MENU_DOWNLOAD = 4
        private const val MENU_CANCEL_DOWNLOAD = 5
        private const val MENU_DELETE_DOWNLOAD = 6

        fun start(context: Context, podcastId: Long) {
            context.startActivity(
                Intent(context, PodcastActivity::class.java)
                    .putExtra(EXTRA_PODCAST_ID, podcastId),
            )
        }
    }
}
