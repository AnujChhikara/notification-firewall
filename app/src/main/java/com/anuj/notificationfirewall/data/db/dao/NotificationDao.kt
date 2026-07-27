package com.anuj.notificationfirewall.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.anuj.notificationfirewall.data.db.NotificationRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {
    @Insert
    suspend fun insert(rec: NotificationRecordEntity): Long

    @Query("SELECT * FROM notifications ORDER BY timestampEpochMs DESC")
    fun observeCaptured(): Flow<List<NotificationRecordEntity>>

    @Query("SELECT * FROM notifications WHERE timestampEpochMs BETWEEN :startMs AND :endMs ORDER BY timestampEpochMs")
    suspend fun recordsBetween(startMs: Long, endMs: Long): List<NotificationRecordEntity>

    @Query("UPDATE notifications SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: Long)
}
