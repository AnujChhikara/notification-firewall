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
 * How soon to retry finishing an expired window when [ArmingController.arm]
 * could not complete it (typically BLOCKED_NO_LISTENER: a cold-started
 * process has not rebound its notification listener yet). Short, because the
 * common case resolves in seconds; [NfListenerService.onListenerConnected]
 * also triggers an immediate retry the moment the listener is back, so this
 * alarm is mostly a backstop for whichever fires first.
 */
private const val FAST_RETRY_DELAY_MS = 30_000L

/**
 * Once an expiry has been stuck unresolved for this long, back off to a
 * slower cadence rather than waking the device every 30s indefinitely --
 * e.g. the user revoked notification access for good and nothing will ever
 * un-stick it on its own.
 */
private const val SLOW_RETRY_AFTER_MS = 10 * 60_000L
private const val SLOW_RETRY_DELAY_MS = 15 * 60_000L

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
 * Re-arming (from [cancel], the expiry path, and the boot-restore path)
 * always goes back through [ArmingController.arm], never by poking the filter
 * directly, so the exact call-safe policy (calls + repeat callers always ring)
 * is restored through the one place that owns it. Per Ruling P9, "render what
 * arm() actually returned" is taken literally here: [finishExpiry] only clears
 * the stored deadline once `arm()` reports ARMED. The most likely reason it
 * would report anything else at expiry time is that the alarm woke a
 * previously-killed process, whose notification listener has not rebound yet
 * -- `arm()` correctly, and deliberately, refuses in that state
 * (BLOCKED_NO_LISTENER) rather than putting the phone into DND with nothing
 * bound to re-post anything. Treating that refusal as "close enough" and
 * erasing the deadline anyway would stand the wall open indefinitely with no
 * alarm left to retry: the exact failure this feature exists to prevent, just
 * moved to the exit instead of the entry. [finishExpiry] instead schedules a
 * retry, and [reconcile] gives the listener's own reconnection (and
 * [com.anuj.notificationfirewall.work.MaintenanceWorker]'s periodic run) a
 * faster or backstop path to the same outcome. [reconcile] also re-schedules
 * a still-*live* window's alarm unconditionally: Android silently cancels an
 * app's pending alarms on force-stop, on package replacement (a Play
 * auto-update), and when the SCHEDULE_EXACT_ALARM grant is revoked, and
 * without this call the only things that ever schedule an alarm for a live
 * window are [start] and [restoreAfterBoot] -- neither of which runs again
 * before the deadline. That is byte-for-byte the same failure as an
 * unresolved expiry, just entered through a different door.
 *
 * `dndSetByApp` bookkeeping: [openFilter] clears it (without touching the
 * saved original-policy fields) whenever it was true, precisely so that a
 * later `arm()` is not a no-op. [DndController.apply]'s "already own it"
 * guard is keyed on `dndSetByApp`; leaving it at `true` across a break-glass
 * window would make the eventual re-arm silently do nothing — filter stuck
 * open forever even though `arm()` claims to have run. It is cleared *before*
 * the filter write, not after: the write triggers a system broadcast that
 * [DndChangeReceiver] can react to on another thread ([restoreAfterBoot] runs
 * on a background dispatcher), and if it observed `dndSetByApp` still `true`
 * with the filter already `ALL`, it would clear `hasSavedDndPolicy` too --
 * undoing the very fix ([DndController.saveCurrentPolicy]'s guard) that keeps
 * a break-glass round trip from corrupting the user's real saved policy. See
 * the task-9 report for the full trace.
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
        if (!openFilter(nm)) {
            // The write didn't stick (or threw): do not commit a deadline
            // and countdown over a phone that is still actually filtering.
            Log.w(TAG, "Could not open the interruption filter; break-glass not started")
            return
        }
        val until = clock() + durationMs
        securePrefs.breakGlassUntilMs = until
        schedule(until)
        Log.i(TAG, "Break-glass active until $until")
    }

    /**
     * User-initiated early close: try to re-arm right away. Routed through
     * the same [finishExpiry] result-check as a natural expiry -- if `arm()`
     * refuses (the listener dropped, or policy access was revoked, in the
     * gap between the hero rendering and the tap), this must not clear the
     * deadline and cancel the alarm anyway: that would strand the wall open
     * with nothing left to finish the job, the same failure Critical 1
     * described. The pending alarm is cancelled unconditionally first (the
     * user asked to close *now*, not wait for the original deadline), and
     * the deadline is marked as already-due so [finishExpiry] either clears
     * it (success) or schedules a fresh retry from now (refusal).
     */
    fun cancel() {
        alarmManager()?.cancel(pendingIntent())
        val requestedAt = clock()
        securePrefs.breakGlassUntilMs = requestedAt
        finishExpiry(requestedAt)
    }

    /**
     * Expiry timestamp while a window is live, or null.
     *
     * Also treats the window as over -- and cleans up the stored deadline
     * and the pending alarm -- the moment the live interruption filter is no
     * longer [NotificationManager.INTERRUPTION_FILTER_ALL], even if the
     * timestamp has not yet passed. Two different things can close the
     * filter without going through this class: the Quick Settings tile's
     * onClick arms/disarms [ArmingController] directly and knows nothing
     * about break-glass, and the user can simply turn DND on themselves
     * mid-window. Reading `arming.state()` here would miss the second case
     * (a user-owned DND reads as DISARMED, since `dndSetByApp` is false), so
     * this checks the live filter directly instead -- the same live-derived
     * standard [ArmingController.state] itself is built on. Leaving a stale
     * countdown running over a phone that is actually filtering again is the
     * mirror image of the lie this whole feature exists to prevent.
     *
     * Named `activeUntilMs` (read, not `checkAndClose...`) to match the
     * brief's pinned interface; the side effect is unusual for a getter and
     * is documented here deliberately for that reason.
     */
    fun activeUntilMs(): Long? {
        val until = securePrefs.breakGlassUntilMs
        if (until == 0L || until <= clock()) return null
        val liveFilter = notificationManager()?.currentInterruptionFilter
        if (liveFilter != null && liveFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
            securePrefs.breakGlassUntilMs = 0L
            alarmManager()?.cancel(pendingIntent())
            return null
        }
        return until
    }

    /** Called by [BreakGlassReceiver] when the scheduled (or retry) alarm fires. */
    fun handleExpiryAlarm() {
        val until = securePrefs.breakGlassUntilMs
        if (until == 0L) return // already resolved by a race with another trigger
        finishExpiry(until)
    }

    /**
     * Called from [NfListenerService.onListenerConnected] (fast path: the
     * listener reconnecting is the most common reason an expiry could not
     * complete) and from [com.anuj.notificationfirewall.work.MaintenanceWorker]'s
     * periodic 15-minute run (backstop: covers a listener that never
     * reconnects on its own, or an alarm silently cancelled while the
     * listener was already connected). No-op if there is no window recorded
     * at all.
     *
     * Two things to reconcile, not just one:
     * - An expired-but-unresolved window is finished the same way
     *   [handleExpiryAlarm] would.
     * - A still-live window's alarm is re-scheduled unconditionally.
     *   Force-stop, package replacement (a Play auto-update), and
     *   SCHEDULE_EXACT_ALARM revocation all silently cancel an app's pending
     *   alarms; [schedule] targets the same [PendingIntent] (`FLAG_UPDATE_CURRENT`)
     *   every time, so re-scheduling an alarm that is still there is a cheap,
     *   idempotent no-op, while re-scheduling one that was cancelled out from
     *   under the window is the only thing standing between that and a
     *   deadline that passes with nothing left to close it.
     */
    fun reconcile() {
        val until = securePrefs.breakGlassUntilMs
        if (until == 0L) return
        if (until <= clock()) {
            finishExpiry(until)
        } else {
            schedule(until)
        }
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
            finishExpiry(until)
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
     * The one place that actually closes an expired window. Only clears the
     * stored deadline once [ArmingController.arm] genuinely reports ARMED;
     * see the class doc for why a refusal must not be treated as success.
     */
    private fun finishExpiry(originalUntil: Long) {
        val result = arming.arm()
        if (result == WallState.ARMED) {
            securePrefs.breakGlassUntilMs = 0L
            alarmManager()?.cancel(pendingIntent())
        } else {
            Log.w(TAG, "Could not re-arm at expiry ($result); retrying")
            val delay = if (clock() - originalUntil < SLOW_RETRY_AFTER_MS) {
                FAST_RETRY_DELAY_MS
            } else {
                SLOW_RETRY_DELAY_MS
            }
            schedule(clock() + delay)
        }
    }

    /**
     * Sets the interruption filter directly. See the class doc for why this
     * does not go through [ArmingController.disarm] / [DndController], and
     * for why `dndSetByApp` is released before, not after, the filter write.
     * Returns whether the filter actually reads ALL afterwards.
     */
    private fun openFilter(nm: NotificationManager): Boolean {
        val previousDndSetByApp = securePrefs.dndSetByApp
        securePrefs.dndSetByApp = false
        val applied = runCatching {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        }.onFailure { Log.w(TAG, "Could not open the interruption filter", it) }.isSuccess
        val opened = applied && nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
        if (!opened) {
            // The write didn't take (the filter may still be the app's own
            // PRIORITY policy): restore ownership rather than leaving it
            // disclaimed, or the app's own toggle/disarm can no longer turn
            // that DND off and the user is stuck using the system shade.
            // The saved original-policy fields were never touched either way.
            securePrefs.dndSetByApp = previousDndSetByApp
        }
        return opened
    }

    private fun schedule(until: Long) {
        val alarms = alarmManager() ?: return
        val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarms.canScheduleExactAlarms()

        runCatching {
            if (canBeExact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, pendingIntent())
            } else {
                // Without the exact-alarm grant the re-arm may drift, but it
                // must still be allowed to fire during Doze: plain set() can
                // be deferred for hours, not the minutes a comment here used
                // to claim. setAndAllowWhileIdle() is the idle-safe
                // equivalent of the inexact API.
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, pendingIntent())
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
