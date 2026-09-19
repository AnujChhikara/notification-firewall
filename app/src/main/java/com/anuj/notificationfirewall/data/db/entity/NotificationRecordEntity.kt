package com.anuj.notificationfirewall.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.anuj.notificationfirewall.domain.wall.NotificationCategory
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource

/**
 * One notification the wall saw, and what it did about it.
 *
 * Metadata is kept indefinitely; [title] and [text] are nulled by the retention
 * job after the text window elapses, with [textPurgedAt] recording when.
 */
@Entity(
    tableName = "notifications",
    indices = [
        Index("timestampEpochMs"),
        Index("packageName"),
        Index("pendingClassification"),
    ],
)
data class NotificationRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appLabel: String,
    val title: String?,
    val text: String?,
    val timestampEpochMs: Long,
    val senderKey: String?,
    val contentShape: String,
    /** Jev's raw 1–5 importance, before bias. Null when no Jev verdict exists. */
    val importanceScore: Float?,
    /** The bias actually added before the threshold comparison. */
    val biasApplied: Float,
    val category: NotificationCategory?,
    val isTimeSensitive: Float?,
    val isFromHuman: Float?,
    val needsAction: Float?,
    val jevConfidence: Float?,
    val decisionSource: WallDecisionSource,
    val bucket: WallBucket,
    /** True when this was silenced without a verdict and awaits re-classification. */
    val pendingClassification: Boolean,
    val textPurgedAt: Long?,
    val isRead: Boolean,
)
