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
}
