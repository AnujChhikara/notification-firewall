package com.anuj.notificationfirewall.data.db.dao

import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.anuj.notificationfirewall.ai.ask.SqlValidator
import com.anuj.notificationfirewall.ai.ask.SqlVerdict
import com.anuj.notificationfirewall.ai.ask.UnsafeQueryException
import com.anuj.notificationfirewall.data.db.NfDatabase
import com.anuj.notificationfirewall.data.db.NotificationRecordEntity
import com.anuj.notificationfirewall.domain.wall.NotificationCategory
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The execution-time gate.
 *
 * [SqlValidator] inspects a string; these tests inspect what SQLite itself
 * does with it. Several of them deliberately feed [RawQueryDao.run] statements
 * the validator would have rejected outright — the point is that this gate
 * holds on its own, not that it is reachable in production.
 */
@RunWith(RobolectricTestRunner::class)
class RawQueryDaoTest {

    private lateinit var db: NfDatabase
    private lateinit var dao: RawQueryDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NfDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = RawQueryDao(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed(n: Int) = repeat(n) {
        db.notificationDao().insert(
            NotificationRecordEntity(
                packageName = "com.myntra", appLabel = "Myntra",
                title = "ZEBRAFISHTITLE", text = "ZEBRAFISHBODY",
                timestampEpochMs = 1_700_000_000_000L + it, senderKey = "Myntra",
                contentShape = "shape$it", importanceScore = 1.2f, biasApplied = 0f,
                category = NotificationCategory.PROMOTION, isTimeSensitive = 0.1f,
                isFromHuman = 0.02f, needsAction = 0.05f, jevConfidence = 0.9f,
                decisionSource = WallDecisionSource.JEV, bucket = WallBucket.SILENCE,
                pendingClassification = false, textPurgedAt = null, isRead = false,
            ),
        )
    }

    private suspend fun rowCount() = db.notificationDao().recordsBetween(0, Long.MAX_VALUE).size

    // --- Gate 1: the connection is read-only for the duration of the query ---

    @Test
    fun theReadOnlySessionRefusesAWriteEvenWhenNothingElseInspectsTheStatement() = runTest {
        seed(3)

        // Straight into the session, past the plan and column gates entirely:
        // the only thing that can stop this write is PRAGMA query_only.
        try {
            dao.inReadOnlySession { it.execSQL("DELETE FROM notifications") }
            fail("a write must not be possible inside the read-only session")
        } catch (e: SQLiteException) {
            assertTrue(e.toString(), e.toString().contains("readonly", ignoreCase = true))
        }

        assertEquals(3, rowCount())
    }

    @Test
    fun aWriteStatementLeavesTheDatabaseUntouched() = runTest {
        seed(3)

        try {
            dao.run("DELETE FROM notifications")
            fail("a write statement must not run")
        } catch (e: Exception) {
            // Either gate may fire first; neither may let the write through.
        }

        assertEquals(3, rowCount())
    }

    @Test
    fun theSessionIsUsableForWritesAgainAfterAQuery() = runTest {
        seed(1)
        dao.run("SELECT COUNT(*) AS c FROM notifications LIMIT 1")

        // query_only must be released, or the whole app stops recording
        // notifications after the first question is asked.
        seed(1)
        assertEquals(2, rowCount())
    }

    // --- Gate 2: EXPLAIN QUERY PLAN resolves only allow-listed tables ---

    @Test
    fun rejectsAQueryOverATableOutsideTheAllowList() = runTest {
        // room_master_table exists in every Room database and is not in the
        // Ask schema. The validator would reject this too; the plan gate must
        // reject it independently.
        try {
            dao.run("SELECT COUNT(*) AS c FROM room_master_table LIMIT 1")
            fail("a query over an unlisted table must be rejected")
        } catch (e: UnsafeQueryException) {
            assertTrue(e.message!!, e.message!!.contains("room_master_table"))
        }
    }

    @Test
    fun rejectsAQueryOverAnUnlistedTableReachedThroughAJoin() = runTest {
        seed(1)
        try {
            dao.run(
                "SELECT COUNT(*) AS c FROM notifications " +
                    "JOIN room_master_table ON 1 = 1 LIMIT 1",
            )
            fail("an unlisted table reached through a join must be rejected")
        } catch (e: UnsafeQueryException) {
            assertTrue(e.message!!, e.message!!.contains("room_master_table"))
        }
    }

    @Test
    fun allowsAQueryOverTheAskSchema() = runTest {
        seed(2)
        val result = dao.run("SELECT appLabel, COUNT(*) AS c FROM notifications GROUP BY appLabel LIMIT 10")
        assertEquals(listOf("appLabel", "c"), result.columns)
        assertEquals(listOf(listOf("Myntra", "2")), result.rows)
    }

    // --- Gate 3: the result cursor's own column names ---

    @Test
    fun refusesToReadAnyRowWhenTheCursorExposesAContentColumn() = runTest {
        seed(2)
        try {
            dao.run("SELECT * FROM notifications LIMIT 10", allowContent = false)
            fail("a cursor carrying title/text must not be read")
        } catch (e: UnsafeQueryException) {
            assertTrue(e.message!!, e.message!!.contains("title") || e.message!!.contains("text"))
        }
    }

    @Test
    fun refusesAContentColumnEvenWhenTheProjectionNamesNoTable() = runTest {
        seed(1)
        try {
            dao.run("SELECT n.* FROM notifications n LIMIT 10", allowContent = false)
            fail("a table-qualified star carrying title/text must not be read")
        } catch (e: UnsafeQueryException) {
            assertTrue(e.message!!, e.message!!.contains("title") || e.message!!.contains("text"))
        }
    }

    @Test
    fun contentColumnsAreReturnedWhenTheUserOptedIn() = runTest {
        seed(1)
        val result = dao.run("SELECT title FROM notifications LIMIT 10", allowContent = true)
        assertEquals(listOf("title"), result.columns)
        assertEquals(listOf(listOf("ZEBRAFISHTITLE")), result.rows)
    }

    @Test
    fun aRefusedCursorLeaksNothingIntoTheResult() = runTest {
        seed(2)
        val thrown = try {
            dao.run("SELECT * FROM notifications LIMIT 10", allowContent = false)
            null
        } catch (e: UnsafeQueryException) {
            e
        }
        assertTrue(thrown != null)
        assertFalse(
            "a rejection message must not carry the content it refused",
            thrown.toString().contains("ZEBRAFISHBODY"),
        )
    }

    // --- Gate 4: the row cap is enforced in code, not by the LIMIT text ---

    @Test
    fun readsAtMostTheMaximumNumberOfRowsWhateverTheLimitTextSays() = runTest {
        insertManyFast(SqlValidator.MAX_LIMIT + 120)

        // The validator would reject this LIMIT outright; the cap must hold
        // without it.
        val result = dao.run("SELECT id FROM notifications LIMIT 100000")

        assertEquals(SqlValidator.MAX_LIMIT, result.rows.size)
    }

    @Test
    fun readsAtMostTheMaximumNumberOfRowsWithNoLimitAtAll() = runTest {
        insertManyFast(SqlValidator.MAX_LIMIT + 120)

        val result = dao.run("SELECT id FROM notifications")

        assertEquals(SqlValidator.MAX_LIMIT, result.rows.size)
    }

    // --- The gate must be strict without being useless ---

    @Test
    fun realisticAggregateQueriesPassBothGatesAndRun() = runTest {
        seed(3)
        val queries = listOf(
            "SELECT appLabel, COUNT(*) AS c FROM notifications WHERE timestampEpochMs > 0 " +
                "GROUP BY appLabel HAVING c > 0 ORDER BY c DESC LIMIT 5",
            "SELECT n.appLabel, AVG(n.importanceScore) AS a FROM notifications n " +
                "JOIN sender_bias b ON b.packageName = n.packageName GROUP BY n.appLabel LIMIT 5",
            "SELECT COUNT(*) AS c FROM notifications " +
                "WHERE packageName IN (SELECT packageName FROM overrides) LIMIT 1",
            "SELECT (SELECT COUNT(*) FROM notifications) AS total, COUNT(*) AS silenced " +
                "FROM notifications WHERE bucket = 'SILENCE' LIMIT 1",
            "SELECT appLabel, COUNT(*) AS c FROM notifications " +
                "LEFT JOIN verdict_cache ON verdict_cache.contentShape = notifications.contentShape " +
                "GROUP BY appLabel ORDER BY c DESC LIMIT 5",
            "SELECT category, COUNT(*) AS c FROM notifications " +
                "WHERE decisionSource IN ('JEV', 'CACHE') GROUP BY category ORDER BY c DESC LIMIT 20",
        )
        queries.forEach { sql ->
            assertTrue(sql, SqlValidator.validate(sql, allowContent = false) is SqlVerdict.Allowed)
            dao.run(sql) // must not throw
        }
    }

    /** Bulk seed without paying Room's per-insert transaction cost. */
    private fun insertManyFast(n: Int) {
        db.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO notifications
                (packageName, appLabel, title, text, timestampEpochMs, senderKey,
                 contentShape, importanceScore, biasApplied, category, isTimeSensitive,
                 isFromHuman, needsAction, jevConfidence, decisionSource, bucket,
                 pendingClassification, textPurgedAt, isRead)
            SELECT 'com.myntra', 'Myntra', 'ZEBRAFISHTITLE', 'ZEBRAFISHBODY', i, 'Myntra',
                   'shape' || i, 1.2, 0.0, 'PROMOTION', 0.1, 0.02, 0.05, 0.9,
                   'JEV', 'SILENCE', 0, NULL, 0
            FROM (WITH RECURSIVE c(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM c WHERE i < $n)
                  SELECT i FROM c)
            """.trimIndent(),
        )
    }
}
