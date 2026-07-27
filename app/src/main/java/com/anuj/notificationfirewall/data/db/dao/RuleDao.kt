package com.anuj.notificationfirewall.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.anuj.notificationfirewall.data.db.RuleEntity

@Dao
interface RuleDao {
    @Upsert
    suspend fun upsert(rule: RuleEntity): Long

    @Query("SELECT * FROM rules WHERE profileId = :profileId ORDER BY `order`")
    suspend fun rulesForProfile(profileId: Long): List<RuleEntity>
}
