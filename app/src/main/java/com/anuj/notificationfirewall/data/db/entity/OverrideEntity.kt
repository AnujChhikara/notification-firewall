package com.anuj.notificationfirewall.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.anuj.notificationfirewall.domain.wall.OverrideKind
import com.anuj.notificationfirewall.domain.wall.OverrideSource

/**
 * A hard user decision that bypasses Jev entirely.
 *
 * A null [senderKey] means the override applies to the whole app.
 */
@Entity(
    tableName = "overrides",
    indices = [Index(value = ["packageName", "senderKey"])],
)
data class OverrideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: OverrideKind,
    val packageName: String,
    val senderKey: String?,
    val label: String,
    val source: OverrideSource,
    val createdAtEpochMs: Long,
)
