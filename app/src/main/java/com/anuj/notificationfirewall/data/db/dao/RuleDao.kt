package com.anuj.notificationfirewall.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.anuj.notificationfirewall.data.db.RuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RuleDao {
    @Upsert
    suspend fun upsert(rule: RuleEntity): Long

    @Query("SELECT * FROM rules WHERE profileId = :profileId ORDER BY `order`")
    suspend fun rulesForProfile(profileId: Long): List<RuleEntity>

    @Query("SELECT * FROM rules WHERE profileId = :profileId ORDER BY `order`")
    fun observeRulesForProfile(profileId: Long): Flow<List<RuleEntity>>

    @Query("SELECT COALESCE(MAX(`order`), -1) + 1 FROM rules WHERE profileId = :profileId")
    suspend fun nextOrder(profileId: Long): Int

    @Delete
    suspend fun delete(rule: RuleEntity)
}
