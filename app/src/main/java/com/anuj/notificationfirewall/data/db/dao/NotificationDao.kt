package com.anuj.notificationfirewall.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.anuj.notificationfirewall.data.db.NotificationRecordEntity
import com.anuj.notificationfirewall.domain.wall.NotificationCategory
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {
    @Insert
    suspend fun insert(rec: NotificationRecordEntity): Long

    @Query("SELECT * FROM notifications ORDER BY timestampEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<NotificationRecordEntity>>

    @Query("SELECT * FROM notifications WHERE timestampEpochMs BETWEEN :startMs AND :endMs ORDER BY timestampEpochMs")
    suspend fun recordsBetween(startMs: Long, endMs: Long): List<NotificationRecordEntity>

    @Query("SELECT * FROM notifications WHERE pendingClassification = 1 ORDER BY timestampEpochMs LIMIT :limit")
    suspend fun pending(limit: Int): List<NotificationRecordEntity>

    @Query(
        """
        UPDATE notifications
        SET importanceScore = :importance, category = :category,
            isTimeSensitive = :timeSensitive, isFromHuman = :fromHuman,
            needsAction = :needsAction, jevConfidence = :confidence,
            bucket = :bucket, decisionSource = :source, pendingClassification = 0
        WHERE id = :id
        """,
    )
    suspend fun applyVerdict(
        id: Long,
        importance: Float,
        category: NotificationCategory,
        timeSensitive: Float,
        fromHuman: Float,
        needsAction: Float,
        confidence: Float,
        bucket: WallBucket,
        source: WallDecisionSource,
    )

    @Query("UPDATE notifications SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: Long)

    @Query(
        "UPDATE notifications SET title = NULL, text = NULL, textPurgedAt = :nowMs " +
            "WHERE timestampEpochMs < :cutoffMs AND textPurgedAt IS NULL",
    )
    suspend fun purgeTextBefore(cutoffMs: Long, nowMs: Long): Int
}
