package be.miro.onecast.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import be.miro.onecast.R
import be.miro.onecast.ui.Format
import be.miro.onecast.ui.downloads.DownloadsActivity

/**
 * The two download notifications: one ongoing, live-updating progress notification (owned by
 * [DownloadService], which needs it to run in the foreground) and a one-off alert per failed
 * download.
 */
object DownloadNotifications {

    const val PROGRESS_NOTIFICATION_ID = 4201

    private const val FAILURE_ID = 4300
    private const val CHANNEL_PROGRESS = "downloads_progress"
    private const val CHANNEL_ALERTS = "downloads_alerts"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                context.getString(R.string.download_channel_progress),
                // Silent: a progress bar that pinged would be unbearable.
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                context.getString(R.string.download_channel_alerts),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    /** The ongoing progress notification for the current download (plus how many are waiting). */
    fun buildProgress(context: Context, tasks: List<DownloadTask>): Notification {
        val pending = tasks.filter { it.state != DownloadState.FAILED }
        val current = pending.firstOrNull { it.state == DownloadState.RUNNING } ?: pending.firstOrNull()
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(current?.title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.download_preparing))
            .setContentText(progressText(context, current))
            .setContentIntent(openDownloads(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                R.drawable.ic_close,
                context.getString(if (pending.size > 1) R.string.download_cancel_all else R.string.download_cancel),
                DownloadService.cancelAllIntent(context),
            )
        if (pending.size > 1) {
            builder.setSubText(context.getString(R.string.download_queue_count, pending.size))
        }
        val percent = current?.percent
        if (percent != null) {
            builder.setProgress(100, percent, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.foregroundServiceBehavior = NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
        }
        return builder.build()
    }

    /** Tells the user a download was aborted, and why. Its partial file is already gone. */
    fun notifyFailed(context: Context, task: DownloadTask) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(context.getString(R.string.download_failed))
            .setContentText(
                context.getString(
                    R.string.download_failed_detail,
                    task.title.takeIf { it.isNotBlank() } ?: context.getString(R.string.download_this_episode),
                    task.error ?: context.getString(R.string.download_failed),
                ),
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    context.getString(
                        R.string.download_failed_detail,
                        task.title.takeIf { it.isNotBlank() } ?: context.getString(R.string.download_this_episode),
                        task.error ?: context.getString(R.string.download_failed),
                    ),
                ),
            )
            .setContentIntent(openDownloads(context))
            .setAutoCancel(true)
            .build()
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        runCatching { manager.notify(failureTag(task.episodeId), FAILURE_ID, notification) }
    }

    fun clearFailure(context: Context, episodeId: Long) {
        NotificationManagerCompat.from(context).cancel(failureTag(episodeId), FAILURE_ID)
    }

    private fun progressText(context: Context, task: DownloadTask?): String = when {
        task == null -> context.getString(R.string.download_preparing)
        task.state == DownloadState.QUEUED -> context.getString(R.string.download_waiting)
        task.totalBytes > 0 -> context.getString(
            R.string.download_progress_bytes,
            Format.fileSize(task.downloadedBytes),
            Format.fileSize(task.totalBytes),
        )
        else -> Format.fileSize(task.downloadedBytes)
    }

    private fun openDownloads(context: Context): PendingIntent {
        val intent = Intent(context, DownloadsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * One failure notification per episode, told apart by tag rather than by id. Folding the
     * episode id into the int id is what a notification id looks like it wants, but ids are Ints
     * and episode ids are unbounded Longs, so any squeeze into a fixed range collides: two episodes
     * a multiple of the range apart would share a notification, one silently replacing the other
     * and either one's [clearFailure] cancelling the other's. Tags are strings and don't.
     */
    private fun failureTag(episodeId: Long): String = "download_failure_$episodeId"
}
