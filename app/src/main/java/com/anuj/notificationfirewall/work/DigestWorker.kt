// work/DigestWorker.kt
package com.anuj.notificationfirewall.work

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.anuj.notificationfirewall.ai.DigestBuilder
import com.anuj.notificationfirewall.ai.DigestService
import com.anuj.notificationfirewall.data.db.dao.NotificationDao
import com.anuj.notificationfirewall.service.NfChannels
import com.anuj.notificationfirewall.ui.MainActivity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate
import java.time.ZoneId

private const val TAG = "DigestWorker"
private const val DIGEST_NOTIFICATION_ID = 42

/**
 * Runs once a day (scheduled by [DigestScheduler]) and posts the daily
 * digest: how many notifications rang, were silenced, or were dropped since
 * this time yesterday, the app that silenced the most, and up to three
 * silenced items worth a second look.
 *
 * The window is computed here from the clock on every run, not passed in as
 * input data: this worker is scheduled periodically, so "yesterday" is a
 * different 24h span each time it fires. [NotificationDao.recordsBetween]
 * is half-open (>= start AND < end), matching [NotificationDao.countsForDay],
 * so a record landing exactly at midnight belongs to exactly one digest.
 *
 * Privacy: [DigestBuilder.summarise] is pure and entirely on-device. Its
 * output, [com.anuj.notificationfirewall.ai.DigestData], is split two ways
 * here -- the aggregate counts and the offender's app label go to
 * [digestService] (which may call OpenAI for nicer prose; see its KDoc for
 * exactly what it sends), while `worthALook` -- built from sender names and
 * titles, content per Task 6's provenance rule -- is appended to the
 * notification body directly, by this worker, and is never passed to
 * [digestService].
 */
@HiltWorker
class DigestWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val notificationDao: NotificationDao,
    private val digestService: DigestService,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val startMs = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = today.atStartOfDay(zone).toInstant().toEpochMilli()

        val records = notificationDao.recordsBetween(startMs, endMs)
        val data = DigestBuilder.summarise(records)
        val headline = digestService.summarise(data)

        postDigest(headline, data.worthALook)
        return Result.success()
    }

    /**
     * Degrades honestly when POST_NOTIFICATIONS is denied (required on API
     * 33+): logs and returns instead of crashing or silently vanishing --
     * there is nothing to retry until the user grants the permission, so
     * [doWork] still reports success.
     */
    private fun postDigest(headline: String, worthALook: List<String>) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; digest not shown")
            return
        }
        val nm = appContext.getSystemService(NotificationManager::class.java) ?: return
        NfChannels.ensureDigest(appContext)

        val body = buildString {
            append(headline)
            if (worthALook.isNotEmpty()) {
                append("\n\nWorth a look:\n")
                append(worthALook.joinToString("\n") { "- $it" })
            }
        }

        val openInbox = PendingIntent.getActivity(
            appContext,
            DIGEST_NOTIFICATION_ID,
            Intent(appContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_OPEN_INBOX, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(appContext, NfChannels.DIGEST)
            .setContentTitle("Your daily digest")
            .setContentText(headline)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openInbox)
            .setAutoCancel(true)
            .build()
        nm.notify(NfChannels.DIGEST, DIGEST_NOTIFICATION_ID, notification)
    }
}
