package be.miro.onecast.ui.queue

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import be.miro.onecast.R
import be.miro.onecast.appSettings
import be.miro.onecast.data.Episode
import be.miro.onecast.databinding.SheetQueueBinding
import be.miro.onecast.podcastRepository
import be.miro.onecast.ui.AmoledTheme
import be.miro.onecast.ui.MediaActivity
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

/**
 * The "Up Next" queue — the episodes that autoplay after the current one — as a bottom sheet, so
 * it slides over whatever screen the user was on instead of taking them to a separate one.
 */
class QueueSheet : BottomSheetDialogFragment() {

    private var _binding: SheetQueueBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: QueueAdapter

    private val host: MediaActivity get() = requireActivity() as MediaActivity

    override fun getTheme(): Int = R.style.Onecast_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        // Inflate against the activity's context, not the dialog's: the dialog theme is layered on
        // top of the activity theme and would win over the One UI day/night colours and the
        // optional Expressive accent overlay.
        _binding = SheetQueueBinding.inflate(inflater.cloneInContext(requireActivity()), container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (AmoledTheme.isActive(requireContext())) {
            view.background?.mutate()?.setTint(Color.BLACK)
            dialog?.window?.navigationBarColor = Color.BLACK
        }

        adapter = QueueAdapter(onPlay = ::play, onRemove = ::remove)
        binding.queueList.layoutManager = LinearLayoutManager(requireContext())
        binding.queueList.adapter = adapter
        binding.queueClear.setOnClickListener { confirmClear() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                requireContext().podcastRepository.observeQueue().collect { queue ->
                    adapter.submitList(queue)
                    val empty = queue.isEmpty()
                    binding.queueList.visibility = if (empty) View.GONE else View.VISIBLE
                    binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
                    binding.queueClear.visibility = if (empty) View.GONE else View.VISIBLE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun play(episode: Episode) {
        val activity = host
        // Scoped to the activity, not this fragment: the sheet closes right away.
        activity.lifecycleScope.launch {
            val repository = activity.podcastRepository
            val podcast = repository.getPodcast(episode.podcastId)
            val startAt = if (episode.isPlayed) 0L else episode.positionMs
            activity.playerConnection.loadEpisode(episode, podcast, startAt)
            repository.onEpisodeStarted(episode.id, activity.appSettings.autoQueueNewer)
        }
        dismiss()
    }

    private fun remove(episode: Episode) {
        val context = requireContext().applicationContext
        host.lifecycleScope.launch { context.podcastRepository.removeFromQueue(episode.id) }
    }

    private fun confirmClear() {
        val activity = host
        AlertDialog.Builder(activity)
            .setTitle(R.string.queue_clear)
            .setMessage(R.string.queue_clear_message)
            .setPositiveButton(R.string.queue_clear) { _, _ ->
                activity.lifecycleScope.launch { activity.podcastRepository.clearQueue() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val TAG = "up_next_sheet"

        /** Slide the queue up over [activity]. No-op if it's already showing. */
        fun show(activity: AppCompatActivity) {
            val fm = activity.supportFragmentManager
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            QueueSheet().show(fm, TAG)
        }
    }
}
