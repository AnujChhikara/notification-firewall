package com.anuj.notificationfirewall.data.db

import androidx.room.Entity
import com.anuj.notificationfirewall.domain.wall.NotificationCategory

/**
 * A reusable Jev verdict, keyed by the (package, senderKey, contentShape)
 * triple per design spec §3.3.
 *
 * Only machine-sender, high-confidence verdicts are ever written here — see
 * VerdictCache for the two rules that govern admission.
 *
 * [senderKey] is a non-null empty-string sentinel for "no sender" rather than
 * a nullable column: Room only allows a nullable column in a composite
 * `@PrimaryKey` if the whole key is one auto-generated column, which this
 * isn't. The null/empty normalization happens at the [com.anuj.
 * notificationfirewall.domain.wall.VerdictCache] boundary, not here, so this
 * entity's shape stays a direct reflection of the table.
 */
@Entity(tableName = "verdict_cache", primaryKeys = ["packageName", "senderKey", "contentShape"])
data class VerdictCacheEntity(
    val packageName: String,
    val senderKey: String,
    val contentShape: String,
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
