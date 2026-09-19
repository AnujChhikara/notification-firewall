package com.anuj.notificationfirewall.data.db.dao

import androidx.room.Dao
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.anuj.notificationfirewall.data.db.VerdictCacheEntity

@Dao
interface VerdictCacheDao {
    @Query("SELECT * FROM verdict_cache WHERE contentShape = :shape")
    suspend fun find(shape: String): VerdictCacheEntity?

    @Upsert
    suspend fun upsert(entry: VerdictCacheEntity)

    @Query("UPDATE verdict_cache SET hitCount = hitCount + 1, lastUsedEpochMs = :nowMs WHERE contentShape = :shape")
    suspend fun recordHit(shape: String, nowMs: Long)

    @Query("DELETE FROM verdict_cache WHERE contentShape = :shape")
    suspend fun evict(shape: String)

    @Query("DELETE FROM verdict_cache WHERE lastUsedEpochMs < :cutoffMs")
    suspend fun evictUnusedSince(cutoffMs: Long): Int

    @Query("SELECT COUNT(*) FROM verdict_cache")
    suspend fun count(): Int
}
