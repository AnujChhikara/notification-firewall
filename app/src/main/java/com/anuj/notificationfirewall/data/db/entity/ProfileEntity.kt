package com.anuj.notificationfirewall.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.anuj.notificationfirewall.domain.model.BucketAction

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val daysOfWeek: Set<Int>,
    val order: Int,
    val aiEnabled: Boolean,
    val defaultAction: BucketAction,
    // When true, the app turns on system Do Not Disturb while this profile is
    // active so the OS silences all original notifications at arrival; only the
    // profile's "let-through-custom-sound" rules re-post on a DND-bypass channel
    // and actually ring.
    val autoDnd: Boolean = false
)
