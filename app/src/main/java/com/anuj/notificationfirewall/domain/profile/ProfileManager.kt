// domain/profile/ProfileManager.kt
package com.anuj.notificationfirewall.domain.profile
import com.anuj.notificationfirewall.domain.model.BucketAction
import java.time.DayOfWeek
import java.time.ZonedDateTime

data class ActiveProfile(
    val id: Long, val name: String, val order: Int, val aiEnabled: Boolean,
    val defaultAction: BucketAction, val startMinute: Int, val endMinute: Int,
    val days: Set<DayOfWeek>, val enabled: Boolean,
)
class ProfileManager {
    fun activeProfile(profiles: List<ActiveProfile>, at: ZonedDateTime): ActiveProfile? {
        val minute = at.hour * 60 + at.minute
        return profiles.filter { it.enabled && at.dayOfWeek in it.days && inWindow(minute, it) }
            .minByOrNull { it.order }
    }
    private fun inWindow(minute: Int, p: ActiveProfile): Boolean =
        if (p.startMinute <= p.endMinute) minute in p.startMinute until p.endMinute
        else minute >= p.startMinute || minute < p.endMinute
}
