package com.anuj.notificationfirewall.service

import android.app.NotificationManager
import android.app.NotificationManager.Policy
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Calls must ring while the wall is armed. This is the invariant the whole
 * design rests on — a wall that swallows phone calls gets uninstalled. These
 * assertions exist so a future refactor of the policy code cannot quietly
 * break it.
 */
@RunWith(RobolectricTestRunner::class)
class DndControllerCallSafetyTest {

    private lateinit var context: Context
    private lateinit var nm: NotificationManager
    private lateinit var controller: DndController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        nm = context.getSystemService(NotificationManager::class.java)
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        controller = DndController(
            context,
            SecurePrefs(context.getSharedPreferences("test-dnd", Context.MODE_PRIVATE)),
        )
    }

    @Test
    fun armedPolicyAllowsCallsFromAnyone() {
        controller.apply(wantDnd = true)

        val policy = nm.notificationPolicy
        assertTrue(
            "PRIORITY_CATEGORY_CALLS must be set",
            policy.priorityCategories and Policy.PRIORITY_CATEGORY_CALLS != 0,
        )
        assertTrue(
            "PRIORITY_CATEGORY_REPEAT_CALLERS must be set",
            policy.priorityCategories and Policy.PRIORITY_CATEGORY_REPEAT_CALLERS != 0,
        )
        assertEquals(Policy.PRIORITY_SENDERS_ANY, policy.priorityCallSenders)
    }

    @Test
    fun armedFilterIsPriorityNeverNone() {
        controller.apply(wantDnd = true)

        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            nm.currentInterruptionFilter,
        )
        assertNotEquals(
            NotificationManager.INTERRUPTION_FILTER_NONE,
            nm.currentInterruptionFilter,
        )
        assertNotEquals(
            NotificationManager.INTERRUPTION_FILTER_ALARMS,
            nm.currentInterruptionFilter,
        )
    }

    @Test
    fun disarmRestoresTheUsersOriginalPolicyAndFilter() {
        val original = Policy(
            Policy.PRIORITY_CATEGORY_MESSAGES,
            Policy.PRIORITY_SENDERS_STARRED,
            Policy.PRIORITY_SENDERS_STARRED,
        )
        nm.notificationPolicy = original

        controller.apply(wantDnd = true)
        controller.apply(wantDnd = false)

        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, nm.currentInterruptionFilter)
        assertEquals(original.priorityCategories, nm.notificationPolicy.priorityCategories)
        assertEquals(original.priorityCallSenders, nm.notificationPolicy.priorityCallSenders)
    }

    @Test
    fun disarmDoesNotTouchDndTheUserTurnedOnThemselves() {
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)

        controller.apply(wantDnd = false)

        assertEquals(
            "DND the app did not enable must be left alone",
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            nm.currentInterruptionFilter,
        )
    }
}
