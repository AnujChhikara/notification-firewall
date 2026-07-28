package com.anuj.notificationfirewall.work

import org.junit.Assert.assertEquals
import org.junit.Test

/** Headless tests for the pure "minutes until next end-of-window" math. */
class DigestSchedulerTest {

    @Test
    fun `end later today yields positive same-day delay`() {
        // now 06:00 (360), end 07:00 (420) -> 60 min
        assertEquals(60L * 60_000L, delayUntilNextMillis(nowMinuteOfDay = 360, endMinuteOfDay = 420))
    }

    @Test
    fun `end already passed today wraps to tomorrow`() {
        // now 08:00 (480), end 07:00 (420) -> 23h into tomorrow = 1380 min
        assertEquals(1380L * 60_000L, delayUntilNextMillis(nowMinuteOfDay = 480, endMinuteOfDay = 420))
    }

    @Test
    fun `end exactly now wraps a full day rather than firing immediately`() {
        // now == end -> next occurrence is 24h out, not 0
        assertEquals(1440L * 60_000L, delayUntilNextMillis(nowMinuteOfDay = 420, endMinuteOfDay = 420))
    }

    @Test
    fun `end one minute from now`() {
        assertEquals(60_000L, delayUntilNextMillis(nowMinuteOfDay = 419, endMinuteOfDay = 420))
    }
}
