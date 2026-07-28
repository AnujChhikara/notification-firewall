package com.anuj.notificationfirewall.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.anuj.notificationfirewall.data.db.dao.NotificationDao
import com.anuj.notificationfirewall.data.db.dao.ProfileDao
import com.anuj.notificationfirewall.data.db.dao.RuleDao

@Database(
    entities = [ProfileEntity::class, RuleEntity::class, NotificationRecordEntity::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class NfDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun ruleDao(): RuleDao
    abstract fun notificationDao(): NotificationDao
}
