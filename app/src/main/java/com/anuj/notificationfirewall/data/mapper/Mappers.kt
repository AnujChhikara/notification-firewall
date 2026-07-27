// data/mapper/Mappers.kt
package com.anuj.notificationfirewall.data.mapper

import com.anuj.notificationfirewall.data.db.ProfileEntity
import com.anuj.notificationfirewall.data.db.RuleEntity
import com.anuj.notificationfirewall.domain.profile.ActiveProfile
import com.anuj.notificationfirewall.domain.rules.Rule
import java.time.DayOfWeek

fun ProfileEntity.toActiveProfile(): ActiveProfile = ActiveProfile(
    id = id,
    name = name,
    order = order,
    aiEnabled = aiEnabled,
    defaultAction = defaultAction,
    startMinute = startMinuteOfDay,
    endMinute = endMinuteOfDay,
    days = daysOfWeek.map { DayOfWeek.of(it) }.toSet(),
    enabled = enabled,
)

fun RuleEntity.toRule(): Rule = Rule(
    id = id,
    order = order,
    conditions = ConditionJson.decode(conditionsJson),
    action = action,
)

fun daysToInts(days: Set<DayOfWeek>): Set<Int> = days.map { it.value }.toSet()
