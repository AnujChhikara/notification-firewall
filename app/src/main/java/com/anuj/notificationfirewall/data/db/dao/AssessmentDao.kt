package com.anuj.notificationfirewall.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.anuj.notificationfirewall.data.db.AssessmentResultEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AssessmentDao {

    @Insert
    suspend fun insert(result: AssessmentResultEntity): Long

    @Query("SELECT * FROM assessment_results ORDER BY createdAtEpochMs DESC LIMIT 1")
    fun observeLatest(): Flow<AssessmentResultEntity?>

    @Query("SELECT * FROM assessment_results ORDER BY createdAtEpochMs DESC LIMIT 1")
    suspend fun latest(): AssessmentResultEntity?
}
