// service/HealthEvaluator.kt
package com.anuj.notificationfirewall.service

/** Inputs the health check reasons about — all already-resolved booleans. */
data class HealthFlags(
    val notificationAccess: Boolean,
    val listenerConnected: Boolean,
    val postNotifications: Boolean,
    val needsDndAccess: Boolean,
    val dndAccess: Boolean,
    val batteryExempt: Boolean,
    val exactAlarms: Boolean,
)

enum class HealthLevel { HEALTHY, DEGRADED, BROKEN }

data class HealthState(val level: HealthLevel, val reason: String?)

/**
 * Pure decision: given the current permission/connection flags, is the firewall
 * healthy, merely degraded (works but weaker), or broken (can't do its core job)?
 * Only BROKEN drives the "tap to fix" notification; DEGRADED is a Home banner.
 */
object HealthEvaluator {
    fun evaluate(f: HealthFlags): HealthState {
        // Broken = the firewall cannot see/act on notifications at all.
        if (!f.notificationAccess) {
            return HealthState(HealthLevel.BROKEN, "Notification access is turned off")
        }
        if (!f.listenerConnected) {
            return HealthState(HealthLevel.BROKEN, "The firewall isn't connected")
        }

        // Degraded = works, but something limits it.
        val issues = buildList {
            if (!f.postNotifications) add("can't post notifications")
            if (f.needsDndAccess && !f.dndAccess) add("needs Do Not Disturb access")
            if (!f.exactAlarms) add("exact alarms are off (timing may drift)")
            if (!f.batteryExempt) add("not exempt from battery optimization")
        }
        return if (issues.isEmpty()) {
            HealthState(HealthLevel.HEALTHY, null)
        } else {
            HealthState(HealthLevel.DEGRADED, issues.joinToString(" · "))
        }
    }
}
