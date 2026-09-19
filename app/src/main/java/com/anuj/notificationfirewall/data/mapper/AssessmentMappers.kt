// data/mapper/AssessmentMappers.kt
package com.anuj.notificationfirewall.data.mapper

import com.anuj.notificationfirewall.data.db.AssessmentResultEntity
import com.anuj.notificationfirewall.domain.assessment.AssessmentAnswers
import com.anuj.notificationfirewall.domain.assessment.Cost
import com.anuj.notificationfirewall.domain.assessment.Goal
import com.anuj.notificationfirewall.domain.assessment.OpensBucket
import com.anuj.notificationfirewall.domain.assessment.ProblemProfile
import com.anuj.notificationfirewall.domain.assessment.ProtectedApp
import com.anuj.notificationfirewall.domain.assessment.ScrollPattern
import com.anuj.notificationfirewall.domain.assessment.WorstWindow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object AssessmentMappers {

    private val json = Json { ignoreUnknownKeys = true }

    fun toEntity(profile: ProblemProfile, nowEpochMs: Long): AssessmentResultEntity =
        AssessmentResultEntity(
            pattern = profile.pattern.name,
            worstWindow = profile.worstWindow.name,
            goal = profile.goal.name,
            cost = profile.cost.name,
            protectedAppsJson = encodeApps(profile.protectedApps),
            selfReportedOpensPerDay = profile.selfReportedOpensPerDay,
            sleepStartMinute = profile.sleepStartMinute,
            sleepEndMinute = profile.sleepEndMinute,
            readiness = profile.readiness,
            createdAtEpochMs = nowEpochMs,
        )

    fun toDomain(entity: AssessmentResultEntity): ProblemProfile = ProblemProfile(
        pattern = ScrollPattern.valueOf(entity.pattern),
        worstWindow = WorstWindow.valueOf(entity.worstWindow),
        goal = Goal.valueOf(entity.goal),
        cost = Cost.valueOf(entity.cost),
        protectedApps = decodeApps(entity.protectedAppsJson),
        selfReportedOpensPerDay = entity.selfReportedOpensPerDay,
        sleepStartMinute = entity.sleepStartMinute,
        sleepEndMinute = entity.sleepEndMinute,
        readiness = entity.readiness,
    )

    /** Round-trips the raw answers too, so skipped-question defaults stay visible. */
    fun answersOf(entity: AssessmentResultEntity): AssessmentAnswers = AssessmentAnswers(
        pattern = ScrollPattern.valueOf(entity.pattern),
        worstWindow = WorstWindow.valueOf(entity.worstWindow),
        goal = Goal.valueOf(entity.goal),
        cost = Cost.valueOf(entity.cost),
        protectedApps = decodeApps(entity.protectedAppsJson),
        opensPerDayBucket = OpensBucket.entries.firstOrNull {
            it.midpoint == entity.selfReportedOpensPerDay
        },
        sleepStartMinute = entity.sleepStartMinute,
        sleepEndMinute = entity.sleepEndMinute,
        readiness = entity.readiness,
    )

    private fun encodeApps(apps: List<ProtectedApp>): String = buildJsonArray {
        apps.forEach { app ->
            add(
                buildJsonObject {
                    put("pkg", app.packageName)
                    put("label", app.label)
                },
            )
        }
    }.toString()

    private fun decodeApps(raw: String): List<ProtectedApp> = runCatching {
        json.parseToJsonElement(raw).jsonArray.map { obj ->
            val o = obj.jsonObject
            ProtectedApp(
                packageName = o["pkg"]!!.jsonPrimitive.content,
                label = o["label"]!!.jsonPrimitive.content,
            )
        }
    }.getOrDefault(emptyList())
}
