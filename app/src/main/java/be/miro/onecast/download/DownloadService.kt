package be.miro.onecast.download

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import be.miro.onecast.OnecastApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/**
 * Keeps the process in the foreground while [EpisodeDownloader] works through its queue, and turns
 * that queue into the live progress notification. The downloads themselves run in the downloader;
 * this service only mirrors them, and stops itself as soon as nothing is queued or running.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collector: Job? = null
    private var lastStartId = 0

    private val downloads: EpisodeDownloader get() = (application as OnecastApp).downloads

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DownloadNotifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_CANCEL_ALL) downloads.cancelAll()
        // Must happen on every start command, even the cancelling one, or the system kills us for
        // starting a foreground service without a notification.
        val notification = DownloadNotifications.buildProgress(this, downloads.tasks.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                DownloadNotifications.PROGRESS_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(DownloadNotifications.PROGRESS_NOTIFICATION_ID, notification)
        }
        // The collector finishes when the queue drains; a fresh download restarts it.
        if (collector?.isActive != true) collector = scope.launch { followProgress() }
        return START_NOT_STICKY
    }

    private suspend fun followProgress() {
        downloads.tasks
            // Failed downloads get their own notification; they don't keep this service alive.
            .map { tasks -> tasks.filter { it.state != DownloadState.FAILED } }
            .conflate()
            .takeWhile { it.isNotEmpty() }
            .collect { pending ->
                NotificationManagerCompat.from(this)
                    .notify(
                        DownloadNotifications.PROGRESS_NOTIFICATION_ID,
                        DownloadNotifications.buildProgress(this, pending),
                    )
                // The byte counter moves several times a second; conflate + this delay keep the
                // notification current without redrawing it on every chunk.
                delay(NOTIFICATION_REFRESH_MS)
            }
        stop()
    }

    /**
     * [stopSelf] with the last start id, so a download queued in the moment between the queue
     * draining and this running keeps the service (and its notification) alive.
     */
    @Suppress("DEPRECATION")
    private fun stop() {
        stopForeground(true)
        stopSelf(lastStartId)
    }

    override fun onDestroy() {
        scope.cancel()
        collector = null
        super.onDestroy()
    }

    companion object {
        private const val ACTION_CANCEL_ALL = "be.miro.onecast.action.CANCEL_ALL_DOWNLOADS"
        private const val NOTIFICATION_REFRESH_MS = 500L

        /**
         * Only ever called while an activity is in the foreground (the user just asked for a
         * download), so the Android 12+ background-start restriction can't bite — but a start that
         * is refused must not take the app down with it.
         */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context.applicationContext,
                    Intent(context.applicationContext, DownloadService::class.java),
                )
            }
        }

        fun cancelAllIntent(context: Context): PendingIntent {
            val intent = Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL_ALL)
            return PendingIntent.getService(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
