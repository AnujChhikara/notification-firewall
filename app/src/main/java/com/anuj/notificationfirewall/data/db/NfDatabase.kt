package com.anuj.notificationfirewall.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.anuj.notificationfirewall.data.db.dao.NotificationDao
import com.anuj.notificationfirewall.data.db.dao.OverrideDao
import com.anuj.notificationfirewall.data.db.dao.SenderBiasDao
import com.anuj.notificationfirewall.data.db.dao.VerdictCacheDao

/** Drops the Still-era assessment table. Notification history is preserved. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS assessment_results")
    }
}

/**
 * Swaps the profile/rule model for the wall model.
 *
 * The notifications table is rebuilt rather than altered because SQLite cannot
 * drop columns below API 34, and the old profile/rule foreign keys are exactly
 * the columns that must go. Existing rows survive with their content and
 * timestamps intact, marked LEGACY since their verdicts came from a scoring
 * model that no longer exists.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE notifications_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                packageName TEXT NOT NULL,
                appLabel TEXT NOT NULL,
                title TEXT,
                text TEXT,
                timestampEpochMs INTEGER NOT NULL,
                senderKey TEXT,
                contentShape TEXT NOT NULL,
                importanceScore REAL,
                biasApplied REAL NOT NULL,
                category TEXT,
                isTimeSensitive REAL,
                isFromHuman REAL,
                needsAction REAL,
                jevConfidence REAL,
                decisionSource TEXT NOT NULL,
                bucket TEXT NOT NULL,
                pendingClassification INTEGER NOT NULL,
                textPurgedAt INTEGER,
                isRead INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO notifications_new
                (id, packageName, appLabel, title, text, timestampEpochMs, senderKey,
                 contentShape, importanceScore, biasApplied, category, isTimeSensitive,
                 isFromHuman, needsAction, jevConfidence, decisionSource, bucket,
                 pendingClassification, textPurgedAt, isRead)
            SELECT id, packageName, appLabel, title, text, timestampEpochMs, senderKey,
                   '', NULL, 0.0, NULL, NULL, NULL, NULL, NULL, 'LEGACY',
                   CASE bucket
                       WHEN 'LET_THROUGH_AS_IS' THEN 'RING'
                       WHEN 'LET_THROUGH_CUSTOM_SOUND' THEN 'RING'
                       WHEN 'CAPTURE' THEN 'SILENCE'
                       ELSE 'SILENCE'
                   END,
                   0, NULL, isRead
            FROM notifications
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE notifications")
        db.execSQL("ALTER TABLE notifications_new RENAME TO notifications")
        db.execSQL("CREATE INDEX index_notifications_timestampEpochMs ON notifications (timestampEpochMs)")
        db.execSQL("CREATE INDEX index_notifications_packageName ON notifications (packageName)")
        db.execSQL("CREATE INDEX index_notifications_pendingClassification ON notifications (pendingClassification)")

        // The profiles and rules tables are deliberately NOT dropped here.
        // ProfileEntity and RuleEntity are still declared on the @Database at
        // version 5, and Room validates the open database against its declared
        // schema — dropping a declared table crashes on open. Task 11 drops both
        // tables in MIGRATION_5_6, in the same version that removes the entities.

        db.execSQL(
            """
            CREATE TABLE verdict_cache (
                contentShape TEXT PRIMARY KEY NOT NULL,
                packageName TEXT NOT NULL,
                senderKey TEXT,
                importance REAL NOT NULL,
                category TEXT NOT NULL,
                isTimeSensitive REAL NOT NULL,
                isFromHuman REAL NOT NULL,
                needsAction REAL NOT NULL,
                confidence REAL NOT NULL,
                hitCount INTEGER NOT NULL,
                createdAtEpochMs INTEGER NOT NULL,
                lastUsedEpochMs INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE sender_bias (
                packageName TEXT NOT NULL,
                senderKey TEXT NOT NULL,
                bias REAL NOT NULL,
                correctionCount INTEGER NOT NULL,
                lastCorrectedEpochMs INTEGER NOT NULL,
                PRIMARY KEY (packageName, senderKey)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE overrides (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                kind TEXT NOT NULL,
                packageName TEXT NOT NULL,
                senderKey TEXT,
                label TEXT NOT NULL,
                source TEXT NOT NULL,
                createdAtEpochMs INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX index_overrides_packageName_senderKey ON overrides (packageName, senderKey)")
    }
}

/** Removes the last traces of the profile/rule model. Wall data is untouched. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS profiles")
        db.execSQL("DROP TABLE IF EXISTS rules")
    }
}

@Database(
    entities = [
        NotificationRecordEntity::class,
        VerdictCacheEntity::class,
        SenderBiasEntity::class,
        OverrideEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class NfDatabase : RoomDatabase() {
    abstract fun notificationDao(): NotificationDao
    abstract fun verdictCacheDao(): VerdictCacheDao
    abstract fun senderBiasDao(): SenderBiasDao
    abstract fun overrideDao(): OverrideDao
}
