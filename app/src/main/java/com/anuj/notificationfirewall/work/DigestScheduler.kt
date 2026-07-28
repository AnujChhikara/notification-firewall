// work/DigestScheduler.kt
package com.anuj.notificationfirewall.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.anuj.notificationfirewall.domain.profile.ActiveProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val MINUTES_PER_DAY = 24 * 60

/**
 * Pure: milliseconds from [nowMinuteOfDay] until the next occurrence of
 * [endMinuteOfDay]. When the end minute is now or already past today, it wraps
 * to tomorrow (a full day out when they are equal), never returning 0 — a
 * profile that just ended should digest at its *next* end, not instantly.
 */
fun delayUntilNextMillis(nowMinuteOfDay: Int, endMinuteOfDay: Int): Long {
    var deltaMinutes = endMinuteOfDay - nowMinuteOfDay
    if (deltaMinutes <= 0) deltaMinutes += MINUTES_PER_DAY
    return deltaMinutes.toLong() * 60_000L
}

/**
 * Schedules the wake-up [DigestWorker] to run at a profile's end-of-window. Uses
 * unique work keyed on the profile id with REPLACE, so re-enabling or editing a
 * profile simply reschedules rather than stacking duplicate digests.
 */
@Singleton
class DigestScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun scheduleForProfile(profile: ActiveProfile, now: ZonedDateTime = ZonedDateTime.now()) {
        if (!profile.enabled) {
            cancelForProfile(profile.id)
            return
        }
        val nowMinute = now.hour * 60 + now.minute
        val delayMs = delayUntilNextMillis(nowMinute, profile.endMinute)

        val request = OneTimeWorkRequestBuilder<DigestWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(DigestWorker.KEY_PROFILE_ID to profile.id))
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName(profile.id),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancelForProfile(profileId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(profileId))
    }

    companion object {
        fun uniqueName(profileId: Long): String = "digest-profile-$profileId"
    }
}
