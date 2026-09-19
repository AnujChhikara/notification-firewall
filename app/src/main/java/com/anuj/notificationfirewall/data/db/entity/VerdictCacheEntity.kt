package com.anuj.notificationfirewall.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.anuj.notificationfirewall.domain.wall.NotificationCategory

/**
 * A reusable Jev verdict, keyed by content shape.
 *
 * Only machine-sender, high-confidence verdicts are ever written here — see
 * VerdictCache for the two rules that govern admission.
 */
@Entity(tableName = "verdict_cache")
data class VerdictCacheEntity(
    @PrimaryKey val contentShape: String,
    val packageName: String,
    val senderKey: String?,
    val importance: Float,
    val category: NotificationCategory,
    val isTimeSensitive: Float,
    val isFromHuman: Float,
    val needsAction: Float,
    val confidence: Float,
    val hitCount: Int,
    val createdAtEpochMs: Long,
    val lastUsedEpochMs: Long,
)
