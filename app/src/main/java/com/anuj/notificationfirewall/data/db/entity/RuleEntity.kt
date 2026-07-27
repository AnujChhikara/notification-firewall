package com.anuj.notificationfirewall.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.anuj.notificationfirewall.domain.model.BucketAction

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val order: Int,
    val conditionsJson: String,
    val action: BucketAction,
    val soundConfigJson: String?
)
