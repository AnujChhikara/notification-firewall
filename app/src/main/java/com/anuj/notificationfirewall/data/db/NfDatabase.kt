package com.anuj.notificationfirewall.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.anuj.notificationfirewall.data.db.dao.NotificationDao
import com.anuj.notificationfirewall.data.db.dao.ProfileDao
import com.anuj.notificationfirewall.data.db.dao.RuleDao

/** Drops the Still-era assessment table. Notification history is preserved. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS assessment_results")
    }
}

@Database(
    entities = [
        ProfileEntity::class,
        RuleEntity::class,
        NotificationRecordEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class NfDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun ruleDao(): RuleDao
    abstract fun notificationDao(): NotificationDao
}
