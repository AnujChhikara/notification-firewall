// work/ProfileScheduler.kt
package com.anuj.notificationfirewall.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.anuj.notificationfirewall.data.db.dao.ProfileDao
import com.anuj.notificationfirewall.service.ProfileBoundaryReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.DayOfWeek
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ProfileScheduler"
private const val BOUNDARY_REQUEST_CODE = 1001

/**
 * Schedules a single exact alarm at the *next* profile-window boundary (start or
 * end, across all enabled profiles). When it fires, [ProfileBoundaryReceiver]
 * reconciles state and re-arms the next one. Exact alarms give precise DND/
 * keep-alive timing and — unlike inexact alarms — are a context from which the
 * keep-alive foreground service may be started.
 */
@Singleton
class ProfileScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileDao: ProfileDao,
) {
    suspend fun rescheduleAll() {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = boundaryPendingIntent()

        val boundaries = profileDao.enabledProfiles().flatMap { p ->
            val days = p.daysOfWeek.map { DayOfWeek.of(it) }.toSet()
            listOf(p.startMinuteOfDay to days, p.endMinuteOfDay to days)
        }
        val next = nextBoundary(ZonedDateTime.now(), boundaries)
        if (next == null) {
            am.cancel(pending)
            return
        }

        val triggerAtMs = next.toInstant().toEpochMilli()
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pending)
            } else {
                // Without exact-alarm permission, fall back to inexact (may drift a
                // few minutes); the 15-min maintenance worker is the safety net.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pending)
            }
            Log.i(TAG, "Next boundary alarm at $next (exact=$canExact)")
        } catch (se: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pending)
        }
    }

    private fun boundaryPendingIntent(): PendingIntent {
        val intent = Intent(context, ProfileBoundaryReceiver::class.java)
            .setAction(ProfileBoundaryReceiver.ACTION_BOUNDARY)
        return PendingIntent.getBroadcast(
            context, BOUNDARY_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
