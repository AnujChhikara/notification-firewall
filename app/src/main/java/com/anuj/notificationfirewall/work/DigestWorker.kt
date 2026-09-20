// work/DigestWorker.kt
package com.anuj.notificationfirewall.work

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.anuj.notificationfirewall.data.db.dao.NotificationDao
import com.anuj.notificationfirewall.ai.DigestService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

private const val TAG = "DigestWorker"
private const val DIGEST_CHANNEL_ID = "nf_digest"

/**
 * Summarizes the notifications captured/silenced over a given window (passed
 * in as input data by whoever calls [DigestScheduler.schedule]) and posts a
 * wake-up digest notification.
 *
 * Decoupled from the profile/rule model (Task 11): it used to look up its
 * window and display name from a profile; now both arrive as plain input
 * data, and it no longer reschedules itself. Task 10 (daily digest)
 * owns deciding when a digest should run and wiring a real caller back up.
 */
@HiltWorker
class DigestWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val notificationDao: NotificationDao,
    private val digestService: DigestService,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val label = inputData.getString(KEY_LABEL)
        val startMs = inputData.getLong(KEY_WINDOW_START_MS, -1L)
        val endMs = inputData.getLong(KEY_WINDOW_END_MS, -1L)
        if (label == null || startMs < 0 || endMs < 0) {
            Log.w(TAG, "Missing digest window input data; skipping")
            return Result.failure()
        }

        val records = notificationDao.recordsBetween(startMs, endMs)
        val summary = digestService.summarize(records)

        postDigest(label, summary)
        return Result.success()
    }

    private fun postDigest(label: String, summary: String) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; digest not shown")
            return
        }
        val nm = appContext.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(DIGEST_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    DIGEST_CHANNEL_ID,
                    "Wake-up digests",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val notification = Notification.Builder(appContext, DIGEST_CHANNEL_ID)
            .setContentTitle("$label digest")
            .setContentText(summary.lineSequence().firstOrNull().orEmpty())
            .setStyle(Notification.BigTextStyle().bigText(summary))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        nm.notify(DIGEST_CHANNEL_ID, DIGEST_NOTIFICATION_ID, notification)
    }

    companion object {
        const val KEY_LABEL = "label"
        const val KEY_WINDOW_START_MS = "windowStartMs"
        const val KEY_WINDOW_END_MS = "windowEndMs"
        private const val DIGEST_NOTIFICATION_ID = 42
    }
}
