package com.anuj.notificationfirewall.service

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ArmingControllerTest {

    private lateinit var context: Context
    private lateinit var nm: NotificationManager
    private lateinit var prefs: SecurePrefs
    private lateinit var arming: ArmingController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        nm = context.getSystemService(NotificationManager::class.java)
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        prefs = SecurePrefs(context.getSharedPreferences("test-arm", Context.MODE_PRIVATE))
        prefs.listenerConnected = true
        arming = ArmingController(context, DndController(context, prefs), prefs)
    }

    @Test
    fun startsDisarmed() {
        assertEquals(WallState.DISARMED, arming.state())
        assertFalse(arming.isArmed())
    }

    @Test
    fun armTurnsOnDndAndReportsArmed() {
        assertEquals(WallState.ARMED, arming.arm())
        assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, nm.currentInterruptionFilter)
        assertTrue(arming.isArmed())
    }

    @Test
    fun disarmTurnsOffDndAndReportsDisarmed() {
        arming.arm()
        assertEquals(WallState.DISARMED, arming.disarm())
        assertFalse(arming.isArmed())
    }

    @Test
    fun externalDndOffDisarmsTheWall() {
        arming.arm()

        // The user flips DND off from the system shade.
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)

        assertEquals(
            "armed state must follow the system, not a stored flag",
            WallState.DISARMED,
            arming.state(),
        )
        assertFalse(arming.isArmed())
    }

    @Test
    fun externalDndOffDoesNotReArmItself() {
        arming.arm()
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)

        arming.onSystemDndChanged()
        arming.state()

        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, nm.currentInterruptionFilter)
        assertEquals(WallState.DISARMED, arming.state())
    }

    @Test
    fun externalDndOffClearsOwnershipSoALaterDisarmDoesNotClobberUserDnd() {
        arming.arm()
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        arming.onSystemDndChanged()

        assertFalse("the app no longer owns DND", prefs.dndSetByApp)
    }

    @Test
    fun missingPolicyAccessIsReportedAsBlocked() {
        shadowOf(nm).setNotificationPolicyAccessGranted(false)
        assertEquals(WallState.BLOCKED_NO_POLICY_ACCESS, arming.state())
    }

    @Test
    fun missingListenerIsReportedAsBlocked() {
        prefs.listenerConnected = false
        assertEquals(WallState.BLOCKED_NO_LISTENER, arming.state())
    }

    @Test
    fun armIsRefusedWithoutPolicyAccess() {
        shadowOf(nm).setNotificationPolicyAccessGranted(false)
        assertEquals(WallState.BLOCKED_NO_POLICY_ACCESS, arming.arm())
    }

    @Test
    fun armIsRefusedWithoutAConnectedListener() {
        // Arming with the listener down would put the phone into system DND
        // while state() reports BLOCKED_NO_LISTENER, suppressing the user's
        // notifications with nothing to re-post them. arm() must refuse
        // before ever touching DND.
        prefs.listenerConnected = false

        assertEquals(WallState.BLOCKED_NO_LISTENER, arming.arm())
        assertEquals(
            "a refused arm must never change the system interruption filter",
            NotificationManager.INTERRUPTION_FILTER_ALL,
            nm.currentInterruptionFilter,
        )
    }

    @Test
    fun userOwnedDndDoesNotCountAsArmed() {
        // DND on, but the app never turned it on.
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        assertFalse(arming.isArmed())
        assertEquals(WallState.DISARMED, arming.state())
    }

    @Test
    fun armStartsKeepAliveService() {
        arming.arm()

        val started = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedService
        assertEquals(
            "arm() must start KeepAliveService or aggressive OEMs can kill the listener",
            KeepAliveService::class.java.name,
            started?.component?.className,
        )
    }

    @Test
    fun disarmStopsKeepAliveService() {
        arming.arm()
        arming.disarm()

        val stopped = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStoppedService
        assertEquals(KeepAliveService::class.java.name, stopped?.component?.className)
    }
}
