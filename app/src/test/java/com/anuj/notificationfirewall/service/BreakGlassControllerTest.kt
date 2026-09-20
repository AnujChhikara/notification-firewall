package com.anuj.notificationfirewall.service

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

private const val NOW = 1_700_000_000_000L

@RunWith(RobolectricTestRunner::class)
class BreakGlassControllerTest {

    private lateinit var context: Context
    private lateinit var nm: NotificationManager
    private lateinit var prefs: SecurePrefs
    private lateinit var arming: ArmingController
    private var now = NOW
    private lateinit var breakGlass: BreakGlassController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        nm = context.getSystemService(NotificationManager::class.java)
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        prefs = SecurePrefs(context.getSharedPreferences("test-bg", Context.MODE_PRIVATE))
        prefs.listenerConnected = true
        arming = ArmingController(context, DndController(context, prefs), prefs)
        breakGlass = BreakGlassController(context, arming, prefs) { now }
    }

    @Test
    fun startDisarmsTheWall() {
        arming.arm()
        breakGlass.start()

        assertEquals(WallState.DISARMED, arming.state())
    }

    @Test
    fun startRecordsAnExpiryOneHourOut() {
        breakGlass.start()
        assertEquals(NOW + BreakGlassController.DEFAULT_DURATION_MS, breakGlass.activeUntilMs())
    }

    @Test
    fun activeUntilIsNullOnceTheWindowHasPassed() {
        breakGlass.start()
        now = NOW + BreakGlassController.DEFAULT_DURATION_MS + 1

        assertNull(breakGlass.activeUntilMs())
    }

    @Test
    fun cancelClearsTheWindowAndReArms() {
        arming.arm()
        breakGlass.start()
        breakGlass.cancel()

        assertNull(breakGlass.activeUntilMs())
        assertEquals(WallState.ARMED, arming.state())
    }

    @Test
    fun anExactAlarmIsScheduled() {
        breakGlass.start()

        val alarms = shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
        assertEquals(
            "re-arming must not depend on the app still running",
            1,
            alarms.scheduledAlarms.size,
        )
        val alarm = alarms.scheduledAlarms.single()
        assertEquals(android.app.AlarmManager.RTC_WAKEUP, alarm.type)
        assertEquals(NOW + BreakGlassController.DEFAULT_DURATION_MS, alarm.triggerAtTime)
        assertEquals(
            "the alarm must target BreakGlassReceiver, not merely exist",
            BreakGlassReceiver::class.java.name,
            shadowOf(alarm.operation).savedIntent.component?.className,
        )
    }

    @Test
    fun restoreAfterBootReArmsImmediatelyIfTheWindowExpiredWhilePoweredOff() {
        arming.arm()
        breakGlass.start()
        now = NOW + BreakGlassController.DEFAULT_DURATION_MS + 60_000

        breakGlass.restoreAfterBoot()

        assertEquals(WallState.ARMED, arming.state())
        assertNull(breakGlass.activeUntilMs())
    }

    @Test
    fun restoreAfterBootReschedulesAStillLiveWindow() {
        breakGlass.start()
        now = NOW + 60_000

        breakGlass.restoreAfterBoot()

        assertEquals(NOW + BreakGlassController.DEFAULT_DURATION_MS, breakGlass.activeUntilMs())
        assertEquals(WallState.DISARMED, arming.state())
    }

    @Test
    fun customDurationIsHonoured() {
        breakGlass.start(durationMs = 15 * 60_000L)
        assertEquals(NOW + 15 * 60_000L, breakGlass.activeUntilMs())
    }

    // --- Ruling P9: disarm() is a no-op unless dndSetByApp is already true,
    // so start() must set the filter itself rather than call arming.disarm().

    @Test
    fun startOpensTheFilterEvenWhenDndWasNotSetByTheApp() {
        // The user turned DND on by hand: dndSetByApp is false, so
        // arming.disarm() -> dndController.apply(wantDnd = false) would be a
        // no-op and leave the phone silently blocking notifications while the
        // wall claims everything is getting through.
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        assertEquals(false, prefs.dndSetByApp)

        breakGlass.start()

        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, nm.currentInterruptionFilter)
    }

    @Test
    fun activeUntilMsReturnsNullAndCleansUpIfSomethingElseArmedTheWallDirectly() {
        // Simulates the Quick Settings tile: it calls ArmingController.arm()
        // directly, with no idea break-glass exists. start() cleared
        // dndSetByApp, so this is not a no-op -- the wall really does end up
        // armed again before the alarm fires.
        breakGlass.start()
        arming.arm()

        assertNull(breakGlass.activeUntilMs())
        assertEquals(WallState.ARMED, arming.state())

        val alarms = shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
        assertTrue(
            "the dangling alarm must be cancelled, not left to fire uselessly",
            alarms.scheduledAlarms.isEmpty(),
        )
    }

    @Test
    fun breakGlassRoundTripDoesNotCorruptTheOriginallySavedDndPolicy() {
        val original = NotificationManager.Policy(
            NotificationManager.Policy.PRIORITY_CATEGORY_MESSAGES,
            NotificationManager.Policy.PRIORITY_SENDERS_STARRED,
            NotificationManager.Policy.PRIORITY_SENDERS_STARRED,
        )
        nm.notificationPolicy = original

        arming.arm()
        breakGlass.start()
        breakGlass.cancel() // re-arms through ArmingController.arm()
        arming.disarm() // a genuine disarm afterwards must restore the ORIGINAL policy

        assertEquals(original.priorityCategories, nm.notificationPolicy.priorityCategories)
        assertEquals(original.priorityCallSenders, nm.notificationPolicy.priorityCallSenders)
    }

    // --- CRITICAL 1: the alarm wakes a cold-started process in the normal
    // case, and NfApplication.onCreate leaves listenerConnected == false
    // until the listener rebinds. arm() correctly refuses in that window --
    // the deadline must survive the refusal, or nothing is left to retry.

    @Test
    fun expiryDoesNotEraseTheDeadlineWhenTheListenerIsNotConnectedYet() {
        arming.arm()
        breakGlass.start()
        now = NOW + BreakGlassController.DEFAULT_DURATION_MS + 1
        prefs.listenerConnected = false // the realistic cold-start state

        breakGlass.handleExpiryAlarm()

        assertEquals(
            "arm() refused (BLOCKED_NO_LISTENER); erasing the deadline here would strand the wall open forever",
            true,
            prefs.breakGlassUntilMs != 0L,
        )
        assertEquals(WallState.BLOCKED_NO_LISTENER, arming.state())
    }

    @Test
    fun expirySchedulesARetryAlarmWhenTheListenerIsNotConnectedYet() {
        breakGlass.start()
        now = NOW + BreakGlassController.DEFAULT_DURATION_MS + 1
        prefs.listenerConnected = false

        breakGlass.handleExpiryAlarm()

        val alarms = shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
        assertTrue(
            "a refused re-arm must leave a fresh alarm behind, or the window never closes",
            alarms.scheduledAlarms.isNotEmpty(),
        )
        assertTrue(
            "the retry must be scheduled in the future, not immediately",
            alarms.scheduledAlarms.single().triggerAtTime > now,
        )
    }

    @Test
    fun restoreAfterBootRetriesInsteadOfErasingTheDeadlineWhenTheListenerIsNotConnectedYet() {
        // BootReceiver runs in a freshly-started process: listenerConnected
        // is guaranteed false until NfListenerService rebinds.
        arming.arm()
        breakGlass.start()
        now = NOW + BreakGlassController.DEFAULT_DURATION_MS + 60_000
        prefs.listenerConnected = false

        breakGlass.restoreAfterBoot()

        assertEquals(WallState.BLOCKED_NO_LISTENER, arming.state())
        assertEquals(
            "the deadline must not be erased while arm() is refusing",
            true,
            prefs.breakGlassUntilMs != 0L,
        )
        val alarms = shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
        assertTrue(
            "a retry must be scheduled so the window can still close later",
            alarms.scheduledAlarms.isNotEmpty(),
        )
    }

    @Test
    fun reconcileFinishesAnExpiredWindowOnceTheListenerReconnects() {
        arming.arm()
        breakGlass.start()
        now = NOW + BreakGlassController.DEFAULT_DURATION_MS + 1
        prefs.listenerConnected = false
        breakGlass.handleExpiryAlarm() // the alarm fires first, into a cold process; it retries

        assertEquals(WallState.BLOCKED_NO_LISTENER, arming.state())

        // NfListenerService.onListenerConnected fires moments later.
        prefs.listenerConnected = true
        breakGlass.reconcile()

        assertEquals(WallState.ARMED, arming.state())
        assertNull(breakGlass.activeUntilMs())
    }

    @Test
    fun reconcileIsANoOpWhenNothingIsPending() {
        // No break-glass window was ever opened -- must not spuriously arm.
        breakGlass.reconcile()
        assertEquals(WallState.DISARMED, arming.state())
    }

    // --- CRITICAL: a still-live window whose alarm is silently cancelled by
    // the platform (force-stop, package replacement/auto-update, or
    // SCHEDULE_EXACT_ALARM revocation) must be rescheduled, not left to pass
    // with nothing scheduled -- the same failure as an unresolved expiry,
    // through a different door.

    @Test
    fun reconcileReschedulesAStillLiveWindowWhoseAlarmWasCancelled() {
        breakGlass.start()
        val alarms = shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
        val cancelledOperation = alarms.scheduledAlarms.single().operation
        // Simulates force-stop / package replacement / exact-alarm revocation,
        // all of which silently cancel an app's pending alarms.
        context.getSystemService(android.app.AlarmManager::class.java).cancel(cancelledOperation)
        assertEquals(0, alarms.scheduledAlarms.size)

        breakGlass.reconcile()

        assertEquals(1, alarms.scheduledAlarms.size)
        val alarm = alarms.scheduledAlarms.single()
        assertEquals(NOW + BreakGlassController.DEFAULT_DURATION_MS, alarm.triggerAtTime)
        assertEquals(
            BreakGlassReceiver::class.java.name,
            shadowOf(alarm.operation).savedIntent.component?.className,
        )
    }

    @Test
    fun reconcileDoesNotTouchAWindowThatIsNotYetDue() {
        // Must not prematurely close or otherwise disturb a live window
        // whose alarm is still intact.
        breakGlass.start()
        val until = breakGlass.activeUntilMs()

        breakGlass.reconcile()

        assertEquals(until, breakGlass.activeUntilMs())
    }

    // --- IMPORTANT: cancel() must not strand the wall open either if arm()
    // refuses (listener dropped, or policy access revoked, in the gap
    // between the hero rendering and the tap).

    @Test
    fun cancelSchedulesARetryInsteadOfStrandingTheWallOpenIfArmFails() {
        arming.arm()
        breakGlass.start()
        prefs.listenerConnected = false

        breakGlass.cancel()

        assertEquals(WallState.BLOCKED_NO_LISTENER, arming.state())
        assertNull(breakGlass.activeUntilMs())
        val alarms = shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
        assertTrue(
            "a refused re-arm on cancel must still leave something scheduled to finish the job",
            alarms.scheduledAlarms.isNotEmpty(),
        )
    }

    // --- IMPORTANT: activeUntilMs() must catch the user turning DND on
    // themselves mid-window too, not just another caller arming the wall --
    // otherwise the countdown lies in the mirror-image direction from P9.

    @Test
    fun activeUntilMsReturnsNullIfTheUserTurnsOnDndThemselvesMidWindow() {
        breakGlass.start()
        // The user opens the system shade and turns DND on themselves.
        // dndSetByApp stays false, so arming.state() would read DISARMED --
        // activeUntilMs() must not trust that and must check the live filter.
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)

        assertEquals(WallState.DISARMED, arming.state())
        assertNull(
            "the countdown must not keep claiming break-glass once the filter is no longer ALL",
            breakGlass.activeUntilMs(),
        )

        val alarms = shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
        assertTrue("the dangling alarm must be cancelled", alarms.scheduledAlarms.isEmpty())
    }

    // --- IMPORTANT: the inexact fallback must still be Doze-safe.

    @Test
    fun fallsBackToAllowWhileIdleWhenExactAlarmsAreDenied() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        try {
            breakGlass.start()

            val alarms = shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
            val alarm = alarms.scheduledAlarms.single()
            assertTrue(
                "a denied exact-alarm grant must still fall back to an idle-safe alarm, not a plain set()",
                alarm.isAllowWhileIdle,
            )
        } finally {
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
        }
    }
}
