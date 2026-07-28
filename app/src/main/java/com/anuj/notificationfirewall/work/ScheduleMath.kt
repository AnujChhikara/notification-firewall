// work/ScheduleMath.kt
package com.anuj.notificationfirewall.work

import java.time.DayOfWeek
import java.time.ZonedDateTime

/**
 * Next occurrence of [minuteOfDay] on one of [days], strictly after [now].
 * Returns null when [days] is empty. Pure — unit-tested.
 */
fun nextOccurrence(now: ZonedDateTime, minuteOfDay: Int, days: Set<DayOfWeek>): ZonedDateTime? {
    if (days.isEmpty()) return null
    val hour = minuteOfDay / 60
    val minute = minuteOfDay % 60
    var candidate = now.toLocalDate().atTime(hour, minute).atZone(now.zone)
    // A week's lookahead is always enough to find the next eligible day.
    repeat(8) {
        if (candidate.dayOfWeek in days && candidate.isAfter(now)) return candidate
        candidate = candidate.plusDays(1)
    }
    return null
}

/**
 * The earliest upcoming boundary across all profile windows — each boundary is a
 * (minuteOfDay, days) pair (a start or an end). Null when there are none.
 */
fun nextBoundary(now: ZonedDateTime, boundaries: List<Pair<Int, Set<DayOfWeek>>>): ZonedDateTime? =
    boundaries.mapNotNull { (minute, days) -> nextOccurrence(now, minute, days) }.minOrNull()
