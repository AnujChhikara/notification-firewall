package com.anuj.notificationfirewall.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.anuj.notificationfirewall.data.db.ProfileEntity

@Dao
interface ProfileDao {
    @Upsert
    suspend fun upsert(profile: ProfileEntity): Long

    @Query("SELECT * FROM profiles WHERE enabled = 1 ORDER BY `order`")
    suspend fun enabledProfiles(): List<ProfileEntity>
}
