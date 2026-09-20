package com.anuj.notificationfirewall.ui.onboarding

import com.anuj.notificationfirewall.ui.permissions.PermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingStepsTest {

    private fun status(
        listener: Boolean = true,
        dnd: Boolean = true,
        post: Boolean = true,
        battery: Boolean = true,
    ) = PermissionStatus(
        notificationAccess = listener,
        dndAccess = dnd,
        contacts = true,
        postNotifications = post,
        batteryExempt = battery,
        exactAlarms = true,
        hasApiKey = true,
    )

    @Test
    fun notificationAccessComesFirst() {
        assertEquals(
            OnboardingStep.NOTIFICATION_ACCESS,
            OnboardingSteps.next(status(listener = false, dnd = false), hasJevKey = false),
        )
    }

    @Test
    fun dndAccessComesAfterNotificationAccess() {
        assertEquals(
            OnboardingStep.DND_ACCESS,
            OnboardingSteps.next(status(dnd = false), hasJevKey = false),
        )
    }

    @Test
    fun postNotificationsComesBeforeTheKey() {
        assertEquals(
            OnboardingStep.POST_NOTIFICATIONS,
            OnboardingSteps.next(status(post = false), hasJevKey = false),
        )
    }

    @Test
    fun theJevKeyIsRequiredBeforeFinishing() {
        assertEquals(OnboardingStep.JEV_KEY, OnboardingSteps.next(status(), hasJevKey = false))
    }

    @Test
    fun batteryExemptionIsTheLastAsk() {
        assertEquals(
            OnboardingStep.BATTERY,
            OnboardingSteps.next(status(battery = false), hasJevKey = true),
        )
    }

    @Test
    fun everythingGrantedIsDone() {
        assertEquals(OnboardingStep.DONE, OnboardingSteps.next(status(), hasJevKey = true))
    }
}
