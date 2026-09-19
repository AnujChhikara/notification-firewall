package com.anuj.notificationfirewall.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "assessment_results")
data class AssessmentResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pattern: String,
    val worstWindow: String,
    val goal: String,
    val cost: String,
    val protectedAppsJson: String,
    val selfReportedOpensPerDay: Int,
    val sleepStartMinute: Int?,
    val sleepEndMinute: Int?,
    val readiness: Int,
    val createdAtEpochMs: Long,
)
