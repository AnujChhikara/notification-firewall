// domain/profile/ProfileManagerTest.kt
package com.anuj.notificationfirewall.domain.profile
import com.anuj.notificationfirewall.domain.model.BucketAction
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ProfileManagerTest {
    private val pm = ProfileManager()
    private fun sleep() = ActiveProfile(
        1, "Sleep", 0, true, BucketAction.CAPTURE,
        startMinute = 22 * 60, endMinute = 7 * 60, days = DayOfWeek.values().toSet(), enabled = true
    )
    private fun at(h: Int, m: Int, day: DayOfWeek = DayOfWeek.MONDAY) =
        ZonedDateTime.of(2026, 7, 27, h, m, 0, 0, ZoneId.of("UTC"))
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(day))

    @Test fun active_before_midnight() {
        assertNotNull(pm.activeProfile(listOf(sleep()), at(23, 30)))
    }
    @Test fun active_after_midnight() {
        assertNotNull(pm.activeProfile(listOf(sleep()), at(3, 0)))
    }
    @Test fun inactive_midday() {
        assertNull(pm.activeProfile(listOf(sleep()), at(13, 0)))
    }
    @Test fun disabled_profile_never_active() {
        assertNull(pm.activeProfile(listOf(sleep().copy(enabled = false)), at(23, 30)))
    }
    @Test fun lowest_order_wins_when_overlapping() {
        val a = sleep().copy(id = 1, order = 5)
        val b = sleep().copy(id = 2, order = 1)
        assertEquals(2L, pm.activeProfile(listOf(a, b), at(23, 30))!!.id)
    }
}
