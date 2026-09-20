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
     * TODO: this takes a write lock (`beginTransaction` is BEGIN EXCLUSIVE),
     * so a question briefly blocks the ingest pipeline. A genuinely read-only
     * *connection* would be better than a read-only *session*, but Room does
     * not expose one; revisit if Room ever does.
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

        assertPlanIsAttributable(sql, plan)
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
            columns.firstOrNull { isContentColumn(it) }?.let {
                throw UnsafeQueryException(
                    "The result would expose the '$it' column, which derives from notification content",
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

    internal companion object {

        val KNOWN_TABLES = setOf("notifications", "verdict_cache", "sender_bias", "overrides")

        /**
         * Content is defined by provenance, not by column name — see
         * SqlValidator's CONTENT_COLUMNS for the full argument. `senderKey` IS
         * the title string (`NotificationMapper` assigns `senderKey = title`
         * verbatim), and `contentShape` is an unsalted digest of title + text
         * from which nothing lexical was removed. `overrides.label` holds
         * `row.sender ?: row.appLabel` for a swipe-created override, and
         * `row.sender` is `senderKey`, so it too can hold a raw title. All
         * four are content and sit behind the same per-question opt-in.
         * `appLabel` is a different column and is not matched.
         *
         * Stored lowercase; [isContentColumn] lowercases before comparing.
         */
        val CONTENT_COLUMNS = setOf("title", "text", "senderkey", "contentshape", "label")

        private val WHITESPACE = Regex("""\s+""")
        private val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")

        // `SCAN TABLE t` is the pre-3.24 spelling; `SCAN t` the modern one.
        private val SCAN_LINE =
            Regex("""^(?:SCAN|SEARCH)\s+(?:TABLE\s+)?(.+)$""", RegexOption.IGNORE_CASE)
        private val BLOOM_LINE = Regex("""^BLOOM FILTER ON\s+(.+)$""", RegexOption.IGNORE_CASE)

        /** `FROM t`, `JOIN t alias`, `FROM t AS alias`. */
        private val FROM_OR_JOIN = Regex(
            """\b(?:from|join)\s+([a-z_][a-z0-9_]*)(?:\s+(?:as\s+)?([a-z_][a-z0-9_]*))?""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Words that may follow a table name without being an alias. Without
         * this, `FROM notifications WHERE …` would bind "where" as an alias of
         * notifications, and a plan line reading a real table called `where`
         * would then be waved through.
         */
        private val NOT_AN_ALIAS = setOf(
            "where", "group", "having", "order", "limit", "offset", "join", "on", "using",
            "inner", "left", "right", "full", "outer", "cross", "natural", "union",
            "intersect", "except", "window", "returning",
        )

        private val STRING_LITERAL = Regex("""'[^']*'""")

        /** Plan steps that introduce no table of their own. */
        private val STRUCTURAL_PLAN_LINES = listOf(
            Regex("""^USE TEMP B-TREE FOR .+$""", RegexOption.IGNORE_CASE),
            Regex(
                """^(?:CORRELATED\s+)?(?:CO-ROUTINE|MATERIALIZE|SUBQUERY|SCALAR SUBQUERY|LIST SUBQUERY)\b.*$""",
                RegexOption.IGNORE_CASE,
            ),
            Regex("""^MULTI-INDEX OR$""", RegexOption.IGNORE_CASE),
            Regex("""^INDEX \d+$""", RegexOption.IGNORE_CASE),
            Regex("""^(?:LEFT-JOIN|RIGHT-JOIN|BLOCKED BY .+)$""", RegexOption.IGNORE_CASE),
        )

        fun isContentColumn(name: String): Boolean {
            val bare = name.substringAfterLast('.').trim().trim('"', '`', '[', ']').lowercase()
            return bare in CONTENT_COLUMNS
        }

        /**
         * Every `EXPLAIN QUERY PLAN` line must be attributable, either to an
         * allow-listed table or to a structural step that reads no table.
         *
         * Alias resolution is the fiddly part and it is not optional: SQLite
         * from 3.36 prints the *alias* in a plan, not the table — `SCAN n`,
         * where an older SQLite printed `SCAN TABLE notifications AS n`. A
         * gate that only recognises table names refuses nearly every joined
         * query on a modern Android release. That fails closed rather than
         * open, but a feature that refuses to work is still broken, and the
         * SQLite bundled with Robolectric (3.32.2) cannot produce the modern
         * spelling, so no end-to-end test would ever have shown it. Hence both
         * the resolution below and the literal-plan-string tests that drive
         * this function directly.
         *
         * Resolution is only ever allowed to make the check stricter or equal,
         * never looser, because [tablesAndAliases] refuses outright if any
         * FROM/JOIN identifier is not itself allow-listed. That closes the
         * shadowing case — `FROM android_metadata AS notifications`, an alias
         * wearing an allow-listed table's name — which alias resolution alone
         * would otherwise wave straight through.
         *
         * Internal so a test can drive it with literal plan strings from a
         * SQLite newer than the one Robolectric bundles.
         */
        fun assertPlanIsAttributable(sql: String, planLines: List<String>) {
            if (planLines.isEmpty()) {
                throw UnsafeQueryException("The query produced no plan to inspect")
            }
            val resolvable = tablesAndAliases(sql)
            planLines.forEach { checkPlanLine(it, resolvable) }
        }

        /**
         * Every name the statement itself says may legitimately appear in a
         * plan: each allow-listed base table, plus any alias bound to one.
         *
         * Throws if the statement names no table at all, or names one outside
         * the Ask schema — both of which make its aliases untrustworthy, and
         * trustworthy aliases are the only reason this map is permitted to
         * relax the table check at all. String literals are blanked first so
         * `WHERE appLabel = 'from evil'` cannot inject a phantom table.
         */
        fun tablesAndAliases(sql: String): Set<String> {
            val masked = STRING_LITERAL.replace(sql) { " ".repeat(it.value.length) }
            val names = mutableSetOf<String>()
            for (match in FROM_OR_JOIN.findAll(masked)) {
                val table = match.groupValues[1].lowercase()
                if (table !in KNOWN_TABLES) {
                    throw UnsafeQueryException(
                        "The statement names a table outside the Ask schema: ${match.groupValues[1]}",
                    )
                }
                names += table
                val alias = match.groupValues[2].lowercase()
                if (alias.isNotEmpty() && alias !in NOT_AN_ALIAS) names += alias
            }
            if (names.isEmpty()) throw UnsafeQueryException("The statement names no table to read")
            return names
        }

        /**
         * One `EXPLAIN QUERY PLAN` detail line, accounted for or rejected.
         *
         * A line either names something SQLite will read — which must resolve
         * to an allow-listed table — or is one of a small set of structural
         * steps that introduce no table of their own (sorting, a co-routine, a
         * subquery whose own lines are checked separately). Anything else falls
         * through to a rejection rather than being waved past as "probably
         * harmless".
         */
        private fun checkPlanLine(detail: String, resolvable: Set<String>) {
            val line = detail.trim()

            val target = SCAN_LINE.find(line)?.groupValues?.get(1)
                ?: BLOOM_LINE.find(line)?.groupValues?.get(1)
            if (target != null) {
                val name = planTargetName(target, line)
                if (name != null && name.lowercase() !in resolvable) {
                    throw UnsafeQueryException(
                        "The query plan reaches a table outside the Ask schema: $name",
                    )
                }
                return
            }

            if (STRUCTURAL_PLAN_LINES.any { it.matches(line) }) return

            throw UnsafeQueryException("Unrecognised query plan step, refusing to run: $line")
        }

        /**
         * The table or alias a SCAN/SEARCH line reads, or null when the line
         * reads something that is not a table at all — a constant row, or a
         * subquery (referenced by its number or as `SUBQUERY n`) whose own plan
         * lines are checked in their own right.
         */
        private fun planTargetName(rawTarget: String, line: String): String? {
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
    }
}
