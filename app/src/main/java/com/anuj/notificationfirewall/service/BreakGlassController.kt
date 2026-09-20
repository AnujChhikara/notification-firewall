package com.anuj.notificationfirewall.service

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.anuj.notificationfirewall.data.prefs.SecurePrefs

private const val TAG = "BreakGlass"
private const val REQUEST_CODE = 7701

/**
 * "I'm expecting something important and I don't trust the wall right now."
 *
 * Opens the interruption filter completely for a fixed window and schedules an
 * exact alarm to put it back. The alarm is re-registered on boot, because the
 * failure that actually costs the user is not a wall that stays up too long —
 * it is a wall that quietly stays down for three days after a restart.
 *
 * [start] sets [NotificationManager.INTERRUPTION_FILTER_ALL] directly instead
 * of calling [ArmingController.disarm]. `disarm()` routes through
 * [DndController.apply], which is a no-op unless [SecurePrefs.dndSetByApp] is
 * already true — so if the user had turned DND on by hand rather than through
 * the app, disarm() would do nothing at all: the filter would stay wherever it
 * was while the Wall screen cheerfully counted down "everything is getting
 * through". Spec §7.4 says to set the filter; setting it here, unconditionally,
 * is what actually keeps that promise.
 *
 * Re-arming (from [cancel], the boot-restore path, and [BreakGlassReceiver])
 * always goes back through [ArmingController.arm], never by poking the filter
 * directly, so the exact call-safe policy (calls + repeat callers always ring)
 * is restored through the one place that owns it.
 *
 * `dndSetByApp` bookkeeping: [start] clears it (without touching the saved
 * original-policy fields) whenever it was true, precisely so that a later
 * `arm()` is not a no-op. [DndController.apply]'s "already own it" guard is
 * keyed on `dndSetByApp`; leaving it at `true` across a break-glass window
 * would make the eventual re-arm silently do nothing — filter stuck open
 * forever even though `arm()` claims to have run. See the task-9 report for
 * the full trace of why this is safe and does not corrupt the saved policy.
 */
class BreakGlassController(
    private val context: Context,
    private val arming: ArmingController,
    private val securePrefs: SecurePrefs,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun start(durationMs: Long = DEFAULT_DURATION_MS) {
        val nm = notificationManager()
        if (nm == null || !nm.isNotificationPolicyAccessGranted) {
            // Without DND access there is nothing to open: recording a
            // window and showing a countdown here would be exactly the lie
            // this feature exists to prevent.
            Log.w(TAG, "Cannot start break-glass without notification policy access")
            return
        }
        val until = clock() + durationMs
        securePrefs.breakGlassUntilMs = until
        openFilter(nm)
        schedule(until)
        Log.i(TAG, "Break-glass active until $until")
    }

    /** User-initiated early close: clear the window and re-arm right away. */
    fun cancel() {
        securePrefs.breakGlassUntilMs = 0L
        alarmManager()?.cancel(pendingIntent())
        arming.arm()
    }

    /**
     * Expiry timestamp while a window is live, or null.
     *
     * Also treats the window as over -- and cleans up the stored deadline
     * and the pending alarm -- the moment the wall reports genuinely ARMED,
     * even if the timestamp has not yet passed. [start] clears
     * `dndSetByApp` precisely so that any other caller of
     * [ArmingController.arm] (the Quick Settings tile's onClick, for
     * instance, which arms/disarms directly and knows nothing about
     * break-glass) is not a no-op either -- which means the wall can end up
     * genuinely armed again before the alarm fires. Without this check the
     * Wall screen would keep showing "everything is getting through" long
     * after the wall was actually filtering again: the one lie this whole
     * feature exists to prevent, just from a different door.
     */
    fun activeUntilMs(): Long? {
        val until = securePrefs.breakGlassUntilMs
        if (until == 0L || until <= clock()) return null
        if (arming.state() == WallState.ARMED) {
            securePrefs.breakGlassUntilMs = 0L
            alarmManager()?.cancel(pendingIntent())
            return null
        }
        return until
    }

    /**
     * Called from BootReceiver. An exact alarm does not survive a reboot, so
     * without this the wall would stay open indefinitely after a restart that
     * happens during a break-glass window.
     */
    fun restoreAfterBoot() {
        val until = securePrefs.breakGlassUntilMs
        if (until == 0L) return

        if (until <= clock()) {
            securePrefs.breakGlassUntilMs = 0L
            arming.arm()
        } else {
            // The window is still open. The interruption filter does not
            // survive a reboot either, so re-open it and reschedule the
            // alarm the reboot just discarded.
            val nm = notificationManager()
            if (nm != null && nm.isNotificationPolicyAccessGranted) openFilter(nm)
            schedule(until)
        }
    }

    /**
     * Sets the interruption filter directly. See the class doc for why this
     * does not go through [ArmingController.disarm] / [DndController].
     */
    private fun openFilter(nm: NotificationManager) {
        runCatching {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        }.onFailure { Log.w(TAG, "Could not open the interruption filter", it) }
        // Release ownership so a later arm() (cancel / expiry / boot-restore)
        // actually re-applies the call-safe policy instead of no-op'ing
        // because DndController still believes it already owns DND. The saved
        // original-policy fields are left untouched -- they still hold the
        // real pre-arm policy and must survive the round trip.
        securePrefs.dndSetByApp = false
    }

    private fun schedule(until: Long) {
        val alarms = alarmManager() ?: return
        val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarms.canScheduleExactAlarms()

        runCatching {
            if (canBeExact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, pendingIntent())
            } else {
                // Without the exact-alarm grant the re-arm may drift by
                // minutes. Late is acceptable; never is not.
                alarms.set(AlarmManager.RTC_WAKEUP, until, pendingIntent())
            }
        }.onFailure { Log.w(TAG, "Could not schedule the re-arm alarm", it) }
    }

    private fun notificationManager(): NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    private fun alarmManager(): AlarmManager? =
        context.getSystemService(AlarmManager::class.java)

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, BreakGlassReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val DEFAULT_DURATION_MS = 3_600_000L
    }
}
