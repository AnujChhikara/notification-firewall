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

        val thrown = try {
            dao.run("DELETE FROM notifications")
            fail("a write statement must not run")
            null
        } catch (e: Exception) {
            e
        }

        // Named precisely, so this cannot pass because of an unrelated NPE or
        // argument error. Exactly two outcomes are acceptable, and which one
        // fires depends on the SQLite version rather than on anything in this
        // class: on the 3.32.2 Robolectric bundles, EXPLAIN QUERY PLAN returns
        // no rows at all for a DELETE, so the plan gate refuses it first; on a
        // SQLite that does emit a plan for a DELETE, the plan is attributable
        // and the read-only session is what stops it. Both are refusals by a
        // named gate; anything else is a bug.
        val planGateRefused = thrown is UnsafeQueryException &&
            thrown.message!!.contains("no plan to inspect")
        val readOnlyRefused = thrown is SQLiteException &&
            thrown.toString().contains("readonly", ignoreCase = true)
        assertTrue(
            "expected a refusal from the plan gate or the read-only session, got $thrown",
            planGateRefused || readOnlyRefused,
        )
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
    fun refusesSenderKeyWhichIsTheTitleStringVerbatim() = runTest {
        // NotificationMapper assigns `senderKey = title`. Grouping by it is the
        // shape a model reaches for on "who messages me most", and it would ship
        // raw titles -- including ones the retention job already purged from the
        // title column -- straight to the phrasing call.
        seed(2)
        try {
            dao.run("SELECT senderKey, COUNT(*) AS c FROM notifications GROUP BY senderKey LIMIT 20")
            fail("senderKey is the title string and must not be returned")
        } catch (e: UnsafeQueryException) {
            assertTrue(e.message!!, e.message!!.contains("senderKey", ignoreCase = true))
        }
    }

    @Test
    fun refusesContentShapeWhichIsADigestOfTheMessage() = runTest {
        seed(2)
        try {
            dao.run("SELECT contentShape, COUNT(*) AS c FROM notifications GROUP BY contentShape LIMIT 20")
            fail("contentShape is a digest of title and text and must not be returned")
        } catch (e: UnsafeQueryException) {
            assertTrue(e.message!!, e.message!!.contains("contentShape", ignoreCase = true))
        }
    }

    @Test
    fun refusesSenderKeyReachedThroughAnotherTable() = runTest {
        seed(1)
        try {
            dao.run("SELECT senderKey, bias FROM sender_bias LIMIT 20")
            fail("senderKey is content wherever it is stored")
        } catch (e: UnsafeQueryException) {
            assertTrue(e.message!!, e.message!!.contains("senderKey", ignoreCase = true))
        }
    }

    @Test
    fun refusesAnOverrideLabelWhichUsuallyHoldsTheSenderKey() = runTest {
        try {
            dao.run("SELECT label, kind FROM overrides LIMIT 20")
            fail("an override label can hold a raw title and must not be returned")
        } catch (e: UnsafeQueryException) {
            assertTrue(e.message!!, e.message!!.contains("label", ignoreCase = true))
        }
    }

    @Test
    fun appLabelIsMetadataAndStaysAggregable() = runTest {
        seed(2)
        val result = dao.run(
            "SELECT appLabel, COUNT(*) AS c FROM notifications GROUP BY appLabel LIMIT 20",
        )
        assertEquals(listOf(listOf("Myntra", "2")), result.rows)
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
                "LEFT JOIN verdict_cache ON verdict_cache.packageName = notifications.packageName " +
                "GROUP BY appLabel ORDER BY c DESC LIMIT 5",
            "SELECT category, COUNT(*) AS c FROM notifications " +
                "WHERE decisionSource IN ('JEV', 'CACHE') GROUP BY category ORDER BY c DESC LIMIT 20",
            "SELECT x.a FROM (SELECT packageName AS a, COUNT(*) AS n FROM notifications " +
                "GROUP BY packageName) x LIMIT 5",
            // The IN shapes that emit USING INDEX … FOR IN-OPERATOR on a modern
            // SQLite: beside another WHERE term, negated, in the projection,
            // and in HAVING.
            "SELECT COUNT(*) AS c FROM notifications WHERE timestampEpochMs > 0 " +
                "AND packageName IN (SELECT packageName FROM overrides) LIMIT 1",
            "SELECT appLabel, COUNT(*) AS c FROM notifications " +
                "WHERE packageName NOT IN (SELECT packageName FROM overrides) " +
                "GROUP BY appLabel LIMIT 20",
            "SELECT packageName IN (SELECT packageName FROM overrides) AS vip, " +
                "COUNT(*) AS c FROM notifications GROUP BY vip LIMIT 20",
            "SELECT packageName, COUNT(*) AS c FROM notifications GROUP BY packageName " +
                "HAVING packageName IN (SELECT packageName FROM overrides) LIMIT 20",
        )
        queries.forEach { sql ->
            assertTrue(sql, SqlValidator.validate(sql, allowContent = false) is SqlVerdict.Allowed)
            dao.run(sql) // must not throw
        }
    }

    // --- Plan attribution, driven directly with literal plan strings ---------
    //
    // Robolectric 4.13 bundles SQLite 3.32.2, which still prints
    // `SCAN TABLE notifications AS n`. From 3.36 SQLite prints the *alias*
    // only -- `SCAN n` -- which no end-to-end test running on this Robolectric
    // can ever produce. These drive the attribution logic with both spellings
    // so the gate is not hostage to the bundled SQLite version.

    private val joinSql =
        "SELECT n.appLabel, COUNT(*) AS c FROM notifications n " +
            "JOIN sender_bias b ON b.packageName = n.packageName GROUP BY n.appLabel LIMIT 5"

    @Test
    fun modernPlanSpellingNamingOnlyAliasesIsAttributed() {
        // SQLite >= 3.36. This is what a real device emits.
        RawQueryDao.assertPlanIsAttributable(
            joinSql,
            listOf("SCAN n", "SEARCH b USING AUTOMATIC COVERING INDEX (packageName=?)"),
        )
    }

    @Test
    fun legacyPlanSpellingNamingTablesIsAttributed() {
        RawQueryDao.assertPlanIsAttributable(
            joinSql,
            listOf(
                "SCAN TABLE notifications AS n",
                "SEARCH TABLE sender_bias AS b USING AUTOMATIC COVERING INDEX (packageName=?)",
            ),
        )
    }

    @Test
    fun aPlanNameThatIsNeitherTableNorAliasIsRejected() {
        try {
            RawQueryDao.assertPlanIsAttributable(joinSql, listOf("SCAN n", "SCAN android_metadata"))
            fail("a plan line naming an unlisted table must be rejected")
        } catch (e: UnsafeQueryException) {
            assertTrue(e.message!!, e.message!!.contains("android_metadata"))
        }
    }

    @Test
    fun anAliasNotBoundByTheStatementIsRejected() {
        try {
            RawQueryDao.assertPlanIsAttributable(joinSql, listOf("SCAN z"))
            fail("an alias the statement never bound must be rejected")
        } catch (e: UnsafeQueryException) {
            assertTrue(e.message!!, e.message!!.contains("z"))
        }
    }

    @Test
    fun anAliasWearingAnAllowListedTablesNameCannotLaunderAnUnlistedTable() {
        // The shadowing case alias resolution alone would wave through.
        try {
            RawQueryDao.assertPlanIsAttributable(
                "SELECT COUNT(*) AS c FROM android_metadata AS notifications LIMIT 1",
                listOf("SCAN notifications"),
            )
            fail("an unlisted base table must be rejected however it is aliased")
        } catch (e: UnsafeQueryException) {
            assertTrue(e.message!!, e.message!!.contains("android_metadata"))
        }
    }

    @Test
    fun aTableNameHiddenInAStringLiteralIsNotMistakenForARealOne() {
        // Masking literals keeps `'from evil'` from binding a phantom table --
        // and, just as importantly, from being rejected as one.
        val sql = "SELECT COUNT(*) AS c FROM notifications WHERE appLabel = 'from evil' LIMIT 1"
        RawQueryDao.assertPlanIsAttributable(sql, listOf("SCAN notifications"))
    }

    @Test
    fun aStatementNamingNoTableIsRejected() {
        try {
            RawQueryDao.assertPlanIsAttributable("SELECT 1 LIMIT 1", listOf("SCAN CONSTANT ROW"))
            fail("a statement with no table to read must be rejected")
        } catch (e: UnsafeQueryException) {
            assertTrue(e.message!!, e.message!!.contains("no table"))
        }
    }

    @Test
    fun structuralPlanStepsAreAttributedWithoutNamingATable() {
        RawQueryDao.assertPlanIsAttributable(
            joinSql,
            listOf("SCAN n", "SEARCH b USING INDEX x (packageName=?)", "USE TEMP B-TREE FOR GROUP BY"),
        )
    }

    @Test
    fun anUnrecognisedPlanStepIsRejectedRatherThanShruggedAt() {
        try {
            RawQueryDao.assertPlanIsAttributable(joinSql, listOf("SCAN n", "SOMETHING NEW AND ODD"))
            fail("an unaccounted-for plan step must be rejected")
        } catch (e: UnsafeQueryException) {
            assertTrue(e.message!!, e.message!!.contains("SOMETHING NEW AND ODD"))
        }
    }

    // --- Modern plan spellings the bundled SQLite cannot produce -------------

    private val inSubquerySql =
        "SELECT COUNT(*) AS c FROM notifications " +
            "WHERE packageName IN (SELECT packageName FROM overrides) LIMIT 1"

    private val derivedTableSql =
        "SELECT x.a FROM (SELECT packageName AS a, COUNT(*) AS n FROM notifications " +
            "GROUP BY packageName) x LIMIT 5"

    @Test
    fun aCreateBloomFilterStepIsAttributed() {
        // SQLite >= 3.38 emits this for an IN (subquery) plan -- the exact
        // shape realisticAggregateQueriesPassBothGatesAndRun covers, so before
        // this fix that canary would have failed on any modern device.
        RawQueryDao.assertPlanIsAttributable(
            inSubquerySql,
            listOf(
                "SEARCH notifications USING COVERING INDEX index_notifications_packageName (packageName=?)",
                "LIST SUBQUERY 1",
                "SCAN overrides",
                "CREATE BLOOM FILTER",
            ),
        )
    }

    @Test
    fun aBloomFilterOnAnUnlistedTableIsStillRejected() {
        // `CREATE BLOOM FILTER` names no table; `BLOOM FILTER ON t` does, and
        // that one must still resolve.
        try {
            RawQueryDao.assertPlanIsAttributable(
                inSubquerySql,
                listOf("SCAN notifications", "BLOOM FILTER ON android_metadata (id=?)"),
            )
            fail("a bloom filter over an unlisted table must be rejected")
        } catch (e: UnsafeQueryException) {
            assertTrue(e.message!!, e.message!!.contains("android_metadata"))
        }
    }

    @Test
    fun aDerivedTableNamedByItsAliasIsAttributed() {
        // SQLite >= 3.36 names the derived table by its alias, and no
        // `FROM <identifier>` ever binds it.
        RawQueryDao.assertPlanIsAttributable(
            derivedTableSql,
            listOf("CO-ROUTINE x", "SCAN x", "SCAN notifications", "USE TEMP B-TREE FOR GROUP BY"),
        )
    }

    @Test
    fun anUnaliasedDerivedTableIsAttributed() {
        RawQueryDao.assertPlanIsAttributable(
            "SELECT a FROM (SELECT packageName AS a FROM notifications) LIMIT 5",
            listOf("CO-ROUTINE (subquery-1)", "SCAN (subquery-1)", "SCAN notifications"),
        )
    }

    @Test
    fun aDerivedTableAliasCannotStandInForAnUnlistedTable() {
        // The subquery's own FROM is checked; the alias inherits that verdict
        // rather than bypassing it.
        try {
            RawQueryDao.assertPlanIsAttributable(
                "SELECT x.a FROM (SELECT packageName AS a FROM android_metadata) x LIMIT 5",
                listOf("CO-ROUTINE x", "SCAN x"),
            )
            fail("a derived table over an unlisted table must be rejected")
        } catch (e: UnsafeQueryException) {
            assertTrue(e.message!!, e.message!!.contains("android_metadata"))
        }
    }

    @Test
    fun aParenthesisedJoinClauseAfterFromIsRejectedRatherThanBound() {
        // SQLite's `table-or-subquery := ( join-clause )` production, the same
        // one SqlValidator refuses. A group that does not open with SELECT
        // introduces tables of its own and binds no trustworthy alias.
        try {
            RawQueryDao.assertPlanIsAttributable(
                "SELECT c FROM (notifications, android_metadata) LIMIT 1",
                listOf("SCAN notifications"),
            )
            fail("a parenthesised join clause must be rejected")
        } catch (e: UnsafeQueryException) {
            assertTrue(e.message!!, e.message!!.contains("parenthesised join clause"))
        }
    }

    @Test
    fun aWrappedParenthesisedJoinClauseCannotBindAnAlias() {
        // SQLite's `table-or-subquery := ( join-clause )` wearing a subquery's
        // hat: the wrapper's first token is `(`, and the group inside it does
        // start with SELECT -- but the comma at the WRAPPER's own top level is
        // joining a second, unvetted table to it. SQLite reads the real
        // android_metadata and prints `SCAN q`.
        try {
            RawQueryDao.assertPlanIsAttributable(
                "SELECT q.locale AS v FROM ((SELECT 1 AS z), android_metadata q) q LIMIT 1",
                listOf("SCAN q"),
            )
            fail("a parenthesised join clause must not bind an alias, however it is wrapped")
        } catch (e: UnsafeQueryException) {
            assertTrue(e.message!!, e.message!!.contains("parenthesised join clause"))
        }
    }

    @Test
    fun aJoinMarkerLineMustStillNameSomethingKnown() {
        // LEFT-JOIN/RIGHT-JOIN lines name a table, so they resolve like a SCAN
        // rather than being waved through as structural.
        RawQueryDao.assertPlanIsAttributable(joinSql, listOf("SCAN n", "RIGHT-JOIN b"))
        try {
            RawQueryDao.assertPlanIsAttributable(joinSql, listOf("SCAN n", "RIGHT-JOIN android_metadata"))
            fail("a join marker naming an unlisted table must be rejected")
        } catch (e: UnsafeQueryException) {
            assertTrue(e.message!!, e.message!!.contains("android_metadata"))
        }
    }

    @Test
    fun theStructuralPlanSpellingsAModernSqliteEmitsAreAllAttributed() {
        // Audited against the query shapes SqlValidator permits. Each of these
        // introduces no table of its own; anything that does name a table is
        // resolved, not listed here.
        listOf(
            "USE TEMP B-TREE FOR ORDER BY",
            "USE TEMP B-TREE FOR GROUP BY",
            "USE TEMP B-TREE FOR DISTINCT",
            "USE TEMP B-TREE FOR LAST TERM OF ORDER BY",
            "CO-ROUTINE x",
            "CO-ROUTINE (subquery-1)",
            "MATERIALIZE x",
            "MATERIALIZE (subquery-1)",
            "SCALAR SUBQUERY 1",
            "CORRELATED SCALAR SUBQUERY 1",
            "LIST SUBQUERY 1",
            "CORRELATED LIST SUBQUERY 1",
            "CREATE BLOOM FILTER",
            "MULTI-INDEX OR",
            "INDEX 1",
            "LEFT-JOIN",
            "RIGHT-JOIN",
            "USING INDEX index_notifications_packageName FOR IN-OPERATOR",
            "USING INDEX sqlite_autoindex_overrides_1 FOR IN-OPERATOR",
            "REUSE LIST SUBQUERY 1",
            "REUSE SUBQUERY 1",
        ).forEach { line ->
            RawQueryDao.assertPlanIsAttributable(derivedTableSql, listOf("SCAN x", line))
        }
    }

    @Test
    fun theRowidInOperatorLineNamesATableAndMustResolve() {
        // `USING ROWID SEARCH ON TABLE t FOR IN-OPERATOR` (for `WHERE id IN
        // (SELECT id FROM overrides)`) names a real table. Treating it as
        // structural alongside its index-naming sibling would have been the
        // fail-open fix.
        RawQueryDao.assertPlanIsAttributable(
            inSubquerySql,
            listOf("SCAN notifications", "USING ROWID SEARCH ON TABLE overrides FOR IN-OPERATOR"),
        )
        try {
            RawQueryDao.assertPlanIsAttributable(
                inSubquerySql,
                listOf(
                    "SCAN notifications",
                    "USING ROWID SEARCH ON TABLE android_metadata FOR IN-OPERATOR",
                ),
            )
            fail("a rowid IN-operator search over an unlisted table must be rejected")
        } catch (e: UnsafeQueryException) {
            assertTrue(e.message!!, e.message!!.contains("android_metadata"))
        }
    }

    @Test
    fun theScanAndSearchSpellingsAModernSqliteEmitsAreAllAttributed() {
        listOf(
            "SCAN notifications",
            "SCAN notifications AS n",
            "SCAN notifications USING COVERING INDEX index_notifications_packageName",
            "SEARCH notifications USING INTEGER PRIMARY KEY (rowid=?)",
            "SEARCH notifications AS n USING AUTOMATIC PARTIAL COVERING INDEX (packageName=?)",
            "SCAN CONSTANT ROW",
            "SCAN (subquery-1)",
            "SCAN 1",
            "SCAN TABLE notifications AS n",
        ).forEach { line ->
            RawQueryDao.assertPlanIsAttributable(joinSql, listOf(line))
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
