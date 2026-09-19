package com.anuj.notificationfirewall.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.anuj.notificationfirewall.data.db.OverrideEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OverrideDao {
    /**
     * Every override that could apply to this notification: app-wide entries
     * (senderKey IS NULL) and entries for this exact sender.
     */
    @Query(
        "SELECT * FROM overrides WHERE packageName = :pkg AND (senderKey IS NULL OR senderKey = :sender)",
    )
    suspend fun matching(pkg: String, sender: String?): List<OverrideEntity>

    @Query("SELECT * FROM overrides ORDER BY createdAtEpochMs DESC")
    fun observeAll(): Flow<List<OverrideEntity>>

    @Insert
    suspend fun insert(entry: OverrideEntity): Long

    @Delete
    suspend fun delete(entry: OverrideEntity)
}
