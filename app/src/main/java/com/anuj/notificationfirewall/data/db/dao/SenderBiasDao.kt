package com.anuj.notificationfirewall.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.anuj.notificationfirewall.data.db.SenderBiasEntity

@Dao
interface SenderBiasDao {
    @Query("SELECT * FROM sender_bias WHERE packageName = :pkg AND senderKey = :sender")
    suspend fun find(pkg: String, sender: String): SenderBiasEntity?

    @Upsert
    suspend fun upsert(entry: SenderBiasEntity)

    @Query("DELETE FROM sender_bias WHERE packageName = :pkg AND senderKey = :sender")
    suspend fun clear(pkg: String, sender: String)
}
