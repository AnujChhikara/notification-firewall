package com.anuj.notificationfirewall.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.anuj.notificationfirewall.data.db.VerdictCacheEntity

@Dao
interface VerdictCacheDao {
    @Query(
        "SELECT * FROM verdict_cache WHERE packageName = :packageName AND senderKey = :senderKey " +
            "AND contentShape = :shape",
    )
    suspend fun find(packageName: String, senderKey: String, shape: String): VerdictCacheEntity?

    @Upsert
    suspend fun upsert(entry: VerdictCacheEntity)

    @Query(
        "UPDATE verdict_cache SET hitCount = hitCount + 1, lastUsedEpochMs = :nowMs " +
            "WHERE packageName = :packageName AND senderKey = :senderKey AND contentShape = :shape",
    )
    suspend fun recordHit(packageName: String, senderKey: String, shape: String, nowMs: Long)

    @Query(
        "DELETE FROM verdict_cache WHERE packageName = :packageName AND senderKey = :senderKey " +
            "AND contentShape = :shape",
    )
    suspend fun evict(packageName: String, senderKey: String, shape: String)

    @Query("DELETE FROM verdict_cache WHERE lastUsedEpochMs < :cutoffMs")
    suspend fun evictUnusedSince(cutoffMs: Long): Int

    @Query("SELECT COUNT(*) FROM verdict_cache")
    suspend fun count(): Int

    /** Wipes every cached verdict. Used by "Empty cache" — safe, self-healing:
     *  a fresh Jev call simply repopulates whatever is asked for again. */
    @Query("DELETE FROM verdict_cache")
    suspend fun clearAll()
}
