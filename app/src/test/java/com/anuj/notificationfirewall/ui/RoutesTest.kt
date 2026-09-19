package com.anuj.notificationfirewall.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesTest {

    @Test
    fun exactlyFourPrimaryDestinations() {
        assertEquals(4, Routes.primary.size)
    }

    @Test
    fun primaryDestinationsAreTheFourTabs() {
        assertEquals(
            setOf(Routes.WALL, Routes.INBOX, Routes.ASK, Routes.SETTINGS),
            Routes.primary,
        )
    }

    @Test
    fun onboardingIsNotAPrimaryDestination() {
        assertTrue(Routes.ONBOARDING !in Routes.primary)
    }

    @Test
    fun noStillEraRoutesRemain() {
        val fields = Routes::class.java.declaredFields.map { it.name.lowercase() }
        listOf("assessment", "program", "pods", "results", "profiles", "rules").forEach {
            assertTrue("Still-era route '$it' must be gone", it !in fields)
        }
    }
}
