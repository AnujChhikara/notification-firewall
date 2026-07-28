package com.anuj.notificationfirewall.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.anuj.notificationfirewall.data.db.ProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Upsert
    suspend fun upsert(profile: ProfileEntity): Long

    @Query("SELECT * FROM profiles WHERE enabled = 1 ORDER BY `order`")
    suspend fun enabledProfiles(): List<ProfileEntity>

    @Query("SELECT * FROM profiles ORDER BY `order`")
    fun observeProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun profileById(id: Long): ProfileEntity?

    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun count(): Int
}
