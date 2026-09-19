package com.anuj.notificationfirewall.data.db

import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [MigrationTestHelper] requires instrumentation to construct (it casts the
 * application context to [android.app.Instrumentation]), which is not
 * available under Robolectric here. Instead we build the version-3 schema by
 * hand on an in-memory [SupportSQLiteDatabase], run [MIGRATION_3_4] directly,
 * and assert the same post-conditions the helper-based test would have.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private fun openInMemoryDb(): SupportSQLiteDatabase {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(
            org.robolectric.RuntimeEnvironment.getApplication(),
        )
            .name(null) // in-memory
            .callback(
                object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                },
            )
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
    }

    @Test
    fun migrate3To4_dropsAssessmentTable_andKeepsNotifications() {
        val db = openInMemoryDb()

        // Version-3 schema, trimmed to the tables this migration touches.
        db.execSQL(
            """
            CREATE TABLE notifications (
              id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              packageName TEXT NOT NULL,
              appLabel TEXT NOT NULL,
              title TEXT NOT NULL,
              text TEXT NOT NULL,
              timestampEpochMs INTEGER NOT NULL,
              senderKey TEXT,
              activeProfileId INTEGER,
              matchedRuleId INTEGER,
              decisionSource TEXT NOT NULL,
              bucket TEXT NOT NULL,
              aiUrgent INTEGER,
              aiReason TEXT,
              isRead INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE assessment_results (
              id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              pattern TEXT NOT NULL,
              worstWindow TEXT NOT NULL,
              goal TEXT NOT NULL,
              cost TEXT NOT NULL,
              protectedAppsJson TEXT NOT NULL,
              selfReportedOpensPerDay INTEGER NOT NULL,
              sleepStartMinute INTEGER,
              sleepEndMinute INTEGER,
              readiness INTEGER NOT NULL,
              createdAtEpochMs INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            INSERT INTO notifications
              (packageName, appLabel, title, text, timestampEpochMs, senderKey,
               activeProfileId, matchedRuleId, decisionSource, bucket,
               aiUrgent, aiReason, isRead)
            VALUES
              ('com.myntra', 'Myntra', 'FLAT 70% OFF', 'Shop now', 1700000000000, 'Myntra',
               NULL, NULL, 'DEFAULT', 'SILENCE', NULL, NULL, 0)
            """.trimIndent(),
        )

        MIGRATION_3_4.migrate(db)

        db.query("SELECT COUNT(*) FROM notifications").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='assessment_results'",
        ).use { c ->
            assertTrue("assessment_results should be gone", c.count == 0)
        }

        db.close()
    }

    /**
     * Version-4 schema: the three tables that exist at that version, exactly
     * as Room emits them (see app/schemas/.../4.json). MIGRATION_4_5 rebuilds
     * `notifications` and leaves `profiles`/`rules` untouched.
     */
    private fun createV4Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE profiles (
              id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              name TEXT NOT NULL,
              enabled INTEGER NOT NULL,
              startMinuteOfDay INTEGER NOT NULL,
              endMinuteOfDay INTEGER NOT NULL,
              daysOfWeek TEXT NOT NULL,
              `order` INTEGER NOT NULL,
              aiEnabled INTEGER NOT NULL,
              defaultAction TEXT NOT NULL,
              autoDnd INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE rules (
              id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              profileId INTEGER NOT NULL,
              `order` INTEGER NOT NULL,
              conditionsJson TEXT NOT NULL,
              action TEXT NOT NULL,
              soundConfigJson TEXT
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE notifications (
              id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              packageName TEXT NOT NULL,
              appLabel TEXT NOT NULL,
              title TEXT NOT NULL,
              text TEXT NOT NULL,
              timestampEpochMs INTEGER NOT NULL,
              senderKey TEXT,
              activeProfileId INTEGER,
              matchedRuleId INTEGER,
              decisionSource TEXT NOT NULL,
              bucket TEXT NOT NULL,
              aiUrgent INTEGER,
              aiReason TEXT,
              isRead INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    @Test
    fun migrate4To5_preservesNotifications_asLegacyRows() {
        val db = openInMemoryDb()
        createV4Schema(db)

        db.execSQL(
            """
            INSERT INTO notifications
              (packageName, appLabel, title, text, timestampEpochMs, senderKey,
               activeProfileId, matchedRuleId, decisionSource, bucket,
               aiUrgent, aiReason, isRead)
            VALUES
              ('com.myntra', 'Myntra', 'FLAT 70% OFF', 'Shop now', 1700000000000, 'Myntra',
               1, NULL, 'AI', 'SILENCE', 0, 'marketing', 0),
              ('com.whatsapp', 'WhatsApp', 'Mom', 'Call me', 1700000001000, 'Mom',
               1, 2, 'RULE', 'LET_THROUGH_CUSTOM_SOUND', NULL, NULL, 1)
            """.trimIndent(),
        )

        MIGRATION_4_5.migrate(db)

        db.query("SELECT decisionSource, bucket, pendingClassification FROM notifications ORDER BY timestampEpochMs")
            .use { c ->
                assertEquals(2, c.count)
                c.moveToFirst()
                assertEquals("LEGACY", c.getString(0))
                assertEquals("SILENCE", c.getString(1))
                assertEquals(0, c.getInt(2))
                c.moveToNext()
                assertEquals("LEGACY", c.getString(0))
                assertEquals("RING", c.getString(1))
            }

        db.close()
    }

    @Test
    fun migrate4To5_createsWallTables_andLeavesProfileTablesForTask11() {
        val db = openInMemoryDb()
        createV4Schema(db)

        MIGRATION_4_5.migrate(db)

        fun tableExists(name: String): Boolean =
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$name'")
                .use { it.count > 0 }

        // Still declared entities at v5, so they must still exist or Room's
        // schema validation fails on open. Task 11 drops them.
        assertTrue(tableExists("profiles"))
        assertTrue(tableExists("rules"))
        assertTrue(tableExists("verdict_cache"))
        assertTrue(tableExists("sender_bias"))
        assertTrue(tableExists("overrides"))
        assertFalse(tableExists("notifications_new"))

        db.close()
    }

    @Test
    fun migrate5To6_leavesWallTablesIntact() {
        val db = openInMemoryDb()
        createV4Schema(db)
        MIGRATION_4_5.migrate(db)

        db.execSQL(
            """
            INSERT INTO verdict_cache
              (contentShape, packageName, senderKey, importance, category,
               isTimeSensitive, isFromHuman, needsAction, confidence,
               hitCount, createdAtEpochMs, lastUsedEpochMs)
            VALUES ('s1', 'com.myntra', 'Myntra', 1.4, 'PROMOTION',
                    0.1, 0.03, 0.05, 0.9, 3, 1700000000000, 1700000000000)
            """.trimIndent(),
        )

        MIGRATION_5_6.migrate(db)

        fun tableExists(name: String): Boolean =
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$name'")
                .use { it.count > 0 }

        db.query("SELECT COUNT(*) FROM verdict_cache").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        assertFalse(tableExists("profiles"))
        assertFalse(tableExists("rules"))

        db.close()
    }

    /**
     * Version-6 `verdict_cache` schema, exactly as MIGRATION_4_5 creates it
     * (contentShape-only primary key, nullable senderKey).
     */
    private fun createV6VerdictCacheTable(db: SupportSQLiteDatabase) {
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
    }

    @Test
    fun migrate6To7_widensTheCacheKey_byDroppingAndRecreatingTheTable() {
        val db = openInMemoryDb()
        createV6VerdictCacheTable(db)

        // Two different apps that happen to share a contentShape under the
        // old key -- exactly the collision Finding 2 exists to fix. Under the
        // old contentShape-only PRIMARY KEY, inserting the second row would
        // NOT even be possible without first deleting/overwriting the first,
        // demonstrating the old schema could not represent this at all.
        db.execSQL(
            """
            INSERT INTO verdict_cache
              (contentShape, packageName, senderKey, importance, category,
               isTimeSensitive, isFromHuman, needsAction, confidence,
               hitCount, createdAtEpochMs, lastUsedEpochMs)
            VALUES ('s1', 'com.myntra', 'Myntra', 1.4, 'PROMOTION',
                    0.1, 0.03, 0.05, 0.9, 3, 1700000000000, 1700000000000)
            """.trimIndent(),
        )

        MIGRATION_6_7.migrate(db)

        // Pure cache: the migration drops and recreates rather than copying,
        // so the old row is gone and every entry rebuilds itself from Jev on
        // the next miss.
        db.query("SELECT COUNT(*) FROM verdict_cache").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }

        // The new composite key now allows two different apps to hold
        // independent verdicts for the identical contentShape -- impossible
        // under the old schema.
        db.execSQL(
            """
            INSERT INTO verdict_cache
              (packageName, senderKey, contentShape, importance, category,
               isTimeSensitive, isFromHuman, needsAction, confidence,
               hitCount, createdAtEpochMs, lastUsedEpochMs)
            VALUES
              ('com.appA', 'SenderA', 'shared-shape', 1.0, 'PROMOTION',
               0.1, 0.03, 0.05, 0.9, 0, 1700000000000, 1700000000000),
              ('com.appB', 'SenderB', 'shared-shape', 4.5, 'PROMOTION',
               0.1, 0.03, 0.05, 0.9, 0, 1700000000000, 1700000000000)
            """.trimIndent(),
        )

        db.query("SELECT COUNT(*) FROM verdict_cache WHERE contentShape = 'shared-shape'").use { c ->
            c.moveToFirst()
            assertEquals(2, c.getInt(0))
        }

        db.close()
    }
}
