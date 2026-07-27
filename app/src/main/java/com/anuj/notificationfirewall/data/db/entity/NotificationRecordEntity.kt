package com.anuj.notificationfirewall.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.anuj.notificationfirewall.domain.model.BucketAction
import com.anuj.notificationfirewall.domain.model.DecisionSource

@Entity(tableName = "notifications")
data class NotificationRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val timestampEpochMs: Long,
    val senderKey: String?,
    val activeProfileId: Long?,
    val matchedRuleId: Long?,
    val decisionSource: DecisionSource,
    val bucket: BucketAction,
    val aiUrgent: Boolean?,
    val aiReason: String?,
    val isRead: Boolean
)
