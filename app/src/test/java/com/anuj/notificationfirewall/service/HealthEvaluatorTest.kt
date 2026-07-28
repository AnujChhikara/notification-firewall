package com.anuj.notificationfirewall.service

import org.junit.Assert.assertEquals
import org.junit.Test

class HealthEvaluatorTest {

    private fun flags(
        notificationAccess: Boolean = true,
        listenerConnected: Boolean = true,
        postNotifications: Boolean = true,
        needsDndAccess: Boolean = false,
        dndAccess: Boolean = true,
        batteryExempt: Boolean = true,
        exactAlarms: Boolean = true,
    ) = HealthFlags(
        notificationAccess, listenerConnected, postNotifications,
        needsDndAccess, dndAccess, batteryExempt, exactAlarms,
    )

    @Test
    fun `everything granted is healthy`() {
        assertEquals(HealthLevel.HEALTHY, HealthEvaluator.evaluate(flags()).level)
    }

    @Test
    fun `no notification access is broken`() {
        assertEquals(HealthLevel.BROKEN, HealthEvaluator.evaluate(flags(notificationAccess = false)).level)
    }

    @Test
    fun `listener disconnected is broken`() {
        assertEquals(HealthLevel.BROKEN, HealthEvaluator.evaluate(flags(listenerConnected = false)).level)
    }

    @Test
    fun `missing battery exemption is only degraded`() {
        val s = HealthEvaluator.evaluate(flags(batteryExempt = false))
        assertEquals(HealthLevel.DEGRADED, s.level)
    }

    @Test
    fun `needs DND access but missing is degraded`() {
        assertEquals(
            HealthLevel.DEGRADED,
            HealthEvaluator.evaluate(flags(needsDndAccess = true, dndAccess = false)).level,
        )
    }

    @Test
    fun `does not need DND access so missing is fine`() {
        assertEquals(
            HealthLevel.HEALTHY,
            HealthEvaluator.evaluate(flags(needsDndAccess = false, dndAccess = false)).level,
        )
    }

    @Test
    fun `broken takes precedence over degraded`() {
        val s = HealthEvaluator.evaluate(flags(notificationAccess = false, batteryExempt = false))
        assertEquals(HealthLevel.BROKEN, s.level)
    }
}
