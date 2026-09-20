// work/DigestScheduler.kt
package com.anuj.notificationfirewall.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val MINUTES_PER_DAY = 24 * 60

/** Unique periodic-work name for the daily digest. */
const val DIGEST_UNIQUE_WORK_NAME = "wall-digest"

/**
 * Pure: milliseconds from [nowMinuteOfDay] until the next occurrence of
 * [endMinuteOfDay]. When the end minute is now or already past today, it wraps
 * to tomorrow (a full day out when they are equal), never returning 0.
 */
fun delayUntilNextMillis(nowMinuteOfDay: Int, endMinuteOfDay: Int): Long {
    var deltaMinutes = endMinuteOfDay - nowMinuteOfDay
    if (deltaMinutes <= 0) deltaMinutes += MINUTES_PER_DAY
    return deltaMinutes.toLong() * 60_000L
}

/**
 * Schedules the daily [DigestWorker] as periodic work, firing once every 24h
 * starting at the next occurrence of [WallSettings.digestTimeMinuteOfDay]
 * (see [com.anuj.notificationfirewall.data.prefs.WallSettings]).
 *
 * [ExistingPeriodicWorkPolicy.UPDATE], not `KEEP`: when the user changes the
 * digest time in Settings, [scheduleDaily] is called again with the new
 * minute, and `UPDATE` re-applies the new initial delay to the existing
 * unique work instead of leaving the old time in effect until reinstall --
 * the same reasoning [WallWorkScheduler] already documents for
 * `MaintenanceWorker`'s schedule.
 */
@Singleton
class DigestScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun scheduleDaily(digestMinuteOfDay: Int) {
        val now = LocalTime.now()
        val nowMinuteOfDay = now.hour * 60 + now.minute
        val delay = delayUntilNextMillis(nowMinuteOfDay, digestMinuteOfDay)

        val request = PeriodicWorkRequestBuilder<DigestWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DIGEST_UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(DIGEST_UNIQUE_WORK_NAME)
    }
}
