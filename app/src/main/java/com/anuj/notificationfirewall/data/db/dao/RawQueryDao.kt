package com.anuj.notificationfirewall.data.db.dao

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.anuj.notificationfirewall.ai.ask.QueryResult
import com.anuj.notificationfirewall.ai.ask.SqlValidator
import com.anuj.notificationfirewall.ai.ask.UnsafeQueryException
import com.anuj.notificationfirewall.data.db.NfDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs a model-authored SELECT against the wall database.
 *
 * [SqlValidator] runs first, at the boundary where model output enters the
 * system, and it is not optional. But it reasons about a *string*, and four
 * rounds of adversarial review on that file established the shape of the
 * problem: each round closed the named bypass and left a sibling open, because
 * only SQLite actually knows what a SQL string means. So this class asks
 * SQLite, at execution time, and treats its answers as authoritative:
 *
 *  1. The statement runs inside a `PRAGMA query_only = ON` session, verified
 *     by reading the pragma back, so a write is impossible whatever the string
 *     says. The session is a transaction that is never marked successful, so
 *     anything that somehow did write would be rolled back as well.
 *  2. `EXPLAIN QUERY PLAN` runs first and every line of the plan must be
 *     attributable to an allow-listed table or to a recognised structural
 *     step. A line naming anything else -- or a line this code cannot parse at
 *     all -- is a rejection. This fails closed deliberately: the *absence* of
 *     a table from a plan proves nothing (SQLite drops LEFT JOIN tables it can
 *     prove unused and folds constant subqueries), so safety is never inferred
 *     from what the plan omits, only from every line it contains being
 *     accounted for.
 *  3. The result cursor's own column names are checked before a single row is
 *     read. This is the authoritative content check: it sees what the query
 *     really returns, which is how `SELECT *` -- in any spelling, including
 *     ones no string parser recognises -- is caught.
 *  4. The row cap is counted in code. The `LIMIT` text is the model's claim;
 *     this is the fact.
 */
class RawQueryDao(private val db: NfDatabase) {

    /**
     * @param allowContent whether the user opted this specific question in to
     *   seeing message content. Defaults to false: a caller that forgets to
     *   pass it gets the private behaviour, not the leaking one.
     */
    suspend fun run(sql: String, allowContent: Boolean = false): QueryResult =
        withContext(Dispatchers.IO) {
            inReadOnlySession { database ->
                assertPlanReachesOnlyKnownTables(database, sql)
                readCapped(database, sql, allowContent)
            }
        }

    /**
     * Runs [block] against a connection on which writes are impossible.
     *
     * The transaction is not a nicety: `PRAGMA query_only` is per-connection,
     * and Android's SQLiteDatabase hands out connections from a pool, so a
     * pragma set outside a transaction may land on a connection the query
     * never uses. A transaction pins one connection to this thread for its
     * whole duration. The pragma is then read back on that same connection —
     * if it did not take, nothing runs.
     *
     * Internal rather than private so a test can drive a write straight into
     * the session, past every other gate, and prove this one holds alone.
     */
    internal fun <T> inReadOnlySession(block: (SupportSQLiteDatabase) -> T): T {
        val database = db.openHelper.writableDatabase
        database.beginTransaction()
        try {
            database.execSQL("PRAGMA query_only = ON")
            if (!isQueryOnly(database)) {
                throw UnsafeQueryException("Could not make the connection read-only; refusing to run the query")
            }
            return block(database)
        } finally {
            // Order matters: the pragma must come off before endTransaction,
            // and endTransaction must run even if that fails, or every later
            // write in the process inherits a read-only connection.
            runCatching { database.execSQL("PRAGMA query_only = OFF") }
            // Never marked successful: the transaction always rolls back.
            database.endTransaction()
        }
    }

    private fun isQueryOnly(database: SupportSQLiteDatabase): Boolean =
        database.query("PRAGMA query_only").use { it.moveToFirst() && it.getInt(0) == 1 }

    private fun assertPlanReachesOnlyKnownTables(database: SupportSQLiteDatabase, sql: String) {
        val plan = try {
            database.query(SimpleSQLiteQuery("EXPLAIN QUERY PLAN $sql")).use { cursor ->
                val detail = cursor.getColumnIndex("detail")
                if (detail < 0) throw UnsafeQueryException("The query plan could not be read")
                val lines = ArrayList<String>()
                while (cursor.moveToNext()) {
                    lines.add(
                        cursor.getString(detail)
                            ?: throw UnsafeQueryException("Unreadable query plan line"),
                    )
                }
                lines
            }
        } catch (e: UnsafeQueryException) {
            throw e
        } catch (e: Exception) {
            throw UnsafeQueryException("The query plan could not be read: ${e.message}")
        }

        if (plan.isEmpty()) throw UnsafeQueryException("The query produced no plan to inspect")
        plan.forEach(::checkPlanLine)
    }

    /**
     * One `EXPLAIN QUERY PLAN` detail line, accounted for or rejected.
     *
     * A line either names a base table SQLite will read — which must be
     * allow-listed — or is one of a small set of structural steps that
     * introduce no table of their own (sorting, a co-routine, a subquery
     * whose own lines are checked separately). Anything else falls through
     * to a rejection rather than being waved past as "probably harmless".
     */
    private fun checkPlanLine(detail: String) {
        val line = detail.trim()

        val target = SCAN_LINE.find(line)?.groupValues?.get(1)
            ?: BLOOM_LINE.find(line)?.groupValues?.get(1)
        if (target != null) {
            val table = planTargetTable(target, line)
            if (table != null && table.lowercase() !in KNOWN_TABLES) {
                throw UnsafeQueryException("The query plan reaches a table outside the Ask schema: $table")
            }
            return
        }

        if (STRUCTURAL_PLAN_LINES.any { it.matches(line) }) return

        throw UnsafeQueryException("Unrecognised query plan step, refusing to run: $line")
    }

    /**
     * The base table a SCAN/SEARCH line reads, or null when the line reads
     * something that is not a table at all — a constant row, or a subquery
     * (referenced by its number or as `SUBQUERY n`) whose own plan lines are
     * checked in their own right.
     */
    private fun planTargetTable(rawTarget: String, line: String): String? {
        var text = rawTarget.trim()
        for (marker in listOf(" USING ", " VIRTUAL TABLE ")) {
            val at = text.indexOf(marker, ignoreCase = true)
            if (at >= 0) text = text.substring(0, at)
        }
        val head = text.trim().split(WHITESPACE).firstOrNull { it.isNotEmpty() }
            ?: throw UnsafeQueryException("Unrecognised query plan step, refusing to run: $line")

        if (head.equals("CONSTANT", ignoreCase = true)) return null
        if (head.equals("SUBQUERY", ignoreCase = true)) return null
        if (head.toIntOrNull() != null) return null
        if (!IDENTIFIER.matches(head)) {
            throw UnsafeQueryException("Unrecognised query plan step, refusing to run: $line")
        }
        return head
    }

    private fun readCapped(
        database: SupportSQLiteDatabase,
        sql: String,
        allowContent: Boolean,
    ): QueryResult = database.query(SimpleSQLiteQuery(sql)).use { cursor ->
        // Column names come from the prepared statement, not from stepping it:
        // this decision is made before any row is materialised.
        val columns = cursor.columnNames.toList()
        if (!allowContent) {
            columns.firstOrNull(::isContentColumn)?.let {
                throw UnsafeQueryException(
                    "The result would expose the '$it' column, which holds notification content",
                )
            }
        }

        val rows = ArrayList<List<String>>()
        // The cap is counted here rather than trusted from the LIMIT text.
        while (rows.size < SqlValidator.MAX_LIMIT && cursor.moveToNext()) {
            rows.add(columns.indices.map { i -> cursor.getString(i) ?: "—" })
        }
        QueryResult(columns, rows)
    }

    private fun isContentColumn(name: String): Boolean {
        val bare = name.substringAfterLast('.').trim().trim('"', '`', '[', ']').lowercase()
        return bare in CONTENT_COLUMNS
    }

    private companion object {
        val KNOWN_TABLES = setOf("notifications", "verdict_cache", "sender_bias", "overrides")
        val CONTENT_COLUMNS = setOf("title", "text")

        val WHITESPACE = Regex("""\s+""")
        val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

        // `SCAN TABLE t` is the pre-3.24 spelling; `SCAN t` the modern one.
        val SCAN_LINE = Regex("""^(?:SCAN|SEARCH)\s+(?:TABLE\s+)?(.+)$""", RegexOption.IGNORE_CASE)
        val BLOOM_LINE = Regex("""^BLOOM FILTER ON\s+(.+)$""", RegexOption.IGNORE_CASE)

        /** Plan steps that introduce no table of their own. */
        val STRUCTURAL_PLAN_LINES = listOf(
            Regex("""^USE TEMP B-TREE FOR .+$""", RegexOption.IGNORE_CASE),
            Regex(
                """^(?:CORRELATED\s+)?(?:CO-ROUTINE|MATERIALIZE|SUBQUERY|SCALAR SUBQUERY|LIST SUBQUERY)\b.*$""",
                RegexOption.IGNORE_CASE,
            ),
            Regex("""^MULTI-INDEX OR$""", RegexOption.IGNORE_CASE),
            Regex("""^INDEX \d+$""", RegexOption.IGNORE_CASE),
            Regex("""^(?:LEFT-JOIN|RIGHT-JOIN|BLOCKED BY .+)$""", RegexOption.IGNORE_CASE),
        )
    }
}
