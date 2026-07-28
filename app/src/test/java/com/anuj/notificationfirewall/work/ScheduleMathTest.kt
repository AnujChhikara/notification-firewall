package com.anuj.notificationfirewall.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleMathTest {

    private val zone = ZoneId.of("UTC")
    // 2026-07-28 is a Tuesday.
    private fun at(day: Int, hour: Int, minute: Int) =
        ZonedDateTime.of(2026, 7, day, hour, minute, 0, 0, zone)

    private val allDays = DayOfWeek.values().toSet()

    @Test
    fun `later today on an eligible day`() {
        val now = at(28, 6, 0) // Tue 06:00
        val next = nextOccurrence(now, 7 * 60, allDays) // 07:00
        assertEquals(at(28, 7, 0), next)
    }

    @Test
    fun `already passed today rolls to tomorrow`() {
        val now = at(28, 8, 0) // Tue 08:00, target 07:00 passed
        val next = nextOccurrence(now, 7 * 60, allDays)
        assertEquals(at(29, 7, 0), next) // Wed 07:00
    }

    @Test
    fun `only matches allowed days of week`() {
        val now = at(28, 8, 0) // Tuesday
        // Only Saturday/Sunday allowed → next 07:00 is Saturday Aug 1.
        val next = nextOccurrence(now, 7 * 60, setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
        assertEquals(ZonedDateTime.of(2026, 8, 1, 7, 0, 0, 0, zone), next)
    }

    @Test
    fun `empty day set yields null`() {
        assertNull(nextOccurrence(at(28, 6, 0), 7 * 60, emptySet()))
    }

    @Test
    fun `nextBoundary picks the earliest across boundaries`() {
        val now = at(28, 23, 0) // Tue 23:00
        // start 22:00 (next is Wed 22:00) and end 07:00 (next is Wed 07:00) → 07:00 wins.
        val boundaries = listOf(22 * 60 to allDays, 7 * 60 to allDays)
        assertEquals(at(29, 7, 0), nextBoundary(now, boundaries))
    }

    @Test
    fun `nextBoundary null when no boundaries`() {
        assertNull(nextBoundary(at(28, 6, 0), emptyList()))
    }
}
