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
import com.anuj.notificationfirewall.data.db.dao.ProfileDao
import com.anuj.notificationfirewall.data.mapper.toActiveProfile
import com.anuj.notificationfirewall.ai.DigestService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.ZonedDateTime

private const val TAG = "DigestWorker"
private const val DIGEST_CHANNEL_ID = "nf_digest"
private const val MINUTES_PER_DAY = 24 * 60

/**
 * Runs at a profile's end-of-window (scheduled by [DigestScheduler]): loads the
 * notifications captured/silenced during the window, asks [DigestService] to
 * summarize them, posts a wake-up digest notification, then reschedules itself
 * for the next day so the digest recurs without a persistent alarm.
 */
@HiltWorker
class DigestWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val profileDao: ProfileDao,
    private val notificationDao: NotificationDao,
    private val digestService: DigestService,
    private val digestScheduler: DigestScheduler,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val profileId = inputData.getLong(KEY_PROFILE_ID, -1L)
        val entity = profileDao.profileById(profileId)
        if (entity == null) {
            Log.w(TAG, "Profile $profileId no longer exists; skipping digest")
            return Result.success()
        }

        val now = ZonedDateTime.now()
        val durationMinutes = windowDurationMinutes(entity.startMinuteOfDay, entity.endMinuteOfDay)
        val endMs = now.toInstant().toEpochMilli()
        val startMs = endMs - durationMinutes.toLong() * 60_000L

        val records = notificationDao.recordsBetween(startMs, endMs)
        val summary = digestService.summarize(records)

        postDigest(entity.name, summary)

        // Reschedule for the next occurrence so the digest keeps recurring.
        digestScheduler.scheduleForProfile(entity.toActiveProfile(), now)
        return Result.success()
    }

    private fun postDigest(profileName: String, summary: String) {
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
            .setContentTitle("$profileName digest")
            .setContentText(summary.lineSequence().firstOrNull().orEmpty())
            .setStyle(Notification.BigTextStyle().bigText(summary))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        nm.notify(DIGEST_CHANNEL_ID, DIGEST_NOTIFICATION_ID, notification)
    }

    private fun windowDurationMinutes(start: Int, end: Int): Int =
        if (end >= start) end - start else MINUTES_PER_DAY - start + end

    companion object {
        const val KEY_PROFILE_ID = "profileId"
        private const val DIGEST_NOTIFICATION_ID = 42
    }
}
