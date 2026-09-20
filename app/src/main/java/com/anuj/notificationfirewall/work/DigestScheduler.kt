// work/DigestScheduler.kt
package com.anuj.notificationfirewall.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val MINUTES_PER_DAY = 24 * 60

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
 * Schedules the wake-up [DigestWorker] to run over a given window, summarizing
 * whatever the wall captured/silenced between [windowStartMs] and
 * [windowEndMs]. Uses unique work keyed on [key] with REPLACE, so scheduling
 * again for the same key simply reschedules rather than stacking duplicates.
 *
 * Decoupled from the profile/rule model (Task 11): this no longer knows what a
 * "profile" is, only a labeled window and a delay. Nothing currently calls
 * [schedule] — Task 10 (daily digest) wires a real trigger back up now
 * that the profile-window concept it used to hang off of is gone.
 */
@Singleton
class DigestScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun schedule(key: String, label: String, delayMs: Long, windowStartMs: Long, windowEndMs: Long) {
        val request = OneTimeWorkRequestBuilder<DigestWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    DigestWorker.KEY_LABEL to label,
                    DigestWorker.KEY_WINDOW_START_MS to windowStartMs,
                    DigestWorker.KEY_WINDOW_END_MS to windowEndMs,
                ),
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName(key),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel(key: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(key))
    }

    companion object {
        fun uniqueName(key: String): String = "digest-$key"
    }
}
