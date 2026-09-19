package com.anuj.notificationfirewall.ai.ask

import org.junit.Assert.assertTrue
import org.junit.Test

class SqlValidatorTest {

    private fun allowed(sql: String, content: Boolean = false) =
        SqlValidator.validate(sql, allowContent = content) is SqlVerdict.Allowed

    /**
     * Asserts the statement is rejected AND returns the reason, so every
     * attack test can assert it was caught by the guard it claims to be
     * exercising — not merely rejected for some unrelated, coincidental
     * reason (e.g. `SELECT\n*` being caught by the SELECT-prefix check
     * instead of the star-projection guard it was meant to test).
     */
    private fun rejectionReason(sql: String, content: Boolean = false): String {
        val verdict = SqlValidator.validate(sql, allowContent = content)
        check(verdict is SqlVerdict.Rejected) { "Expected Rejected but was $verdict for: $sql" }
        return verdict.reason
    }

    private fun rejectedBecause(sql: String, reasonContains: String, content: Boolean = false) {
        val reason = rejectionReason(sql, content)
        assertTrue(
            "Expected reason containing '$reasonContains' but was '$reason' for: $sql",
            reason.contains(reasonContains, ignoreCase = true),
        )
    }

    // ── The happy path ───────────────────────────────────────────────────────

    @Test
    fun plainAggregateSelectIsAllowed() {
        assertTrue(allowed("SELECT packageName, COUNT(*) FROM notifications GROUP BY packageName LIMIT 20"))
    }

    @Test
    fun joinAcrossKnownTablesIsAllowed() {
        assertTrue(
            allowed(
                "SELECT n.appLabel, b.bias FROM notifications n " +
                    "JOIN sender_bias b ON b.packageName = n.packageName LIMIT 50",
            ),
        )
    }

    @Test
    fun lowercaseSelectIsAllowed() {
        assertTrue(allowed("select count(*) from notifications limit 1"))
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    @Test
    fun deleteIsRejected() = rejectedBecause("DELETE FROM notifications", "SELECT statements")

    @Test
    fun updateIsRejected() = rejectedBecause("UPDATE notifications SET isRead = 1 LIMIT 1", "SELECT statements")

    @Test
    fun insertIsRejected() =
        rejectedBecause("INSERT INTO overrides (kind) VALUES ('BLOCK')", "SELECT statements")

    @Test
    fun dropIsRejected() = rejectedBecause("DROP TABLE notifications", "SELECT statements")

    @Test
    fun alterIsRejected() = rejectedBecause("ALTER TABLE notifications ADD COLUMN x TEXT", "SELECT statements")

    @Test
    fun createIsRejected() = rejectedBecause("CREATE TABLE evil (x TEXT)", "SELECT statements")

    // ── Statement smuggling ──────────────────────────────────────────────────

    @Test
    fun trailingStatementIsRejected() {
        rejectedBecause("SELECT COUNT(*) FROM notifications LIMIT 1; DROP TABLE notifications", "single statement")
    }

    @Test
    fun trailingSemicolonAloneIsAllowed() {
        assertTrue(allowed("SELECT COUNT(*) FROM notifications LIMIT 1;"))
    }

    @Test
    fun commentHidingASecondStatementIsRejected() {
        rejectedBecause("SELECT 1 FROM notifications LIMIT 1 -- ; DROP TABLE notifications", "Comments")
    }

    @Test
    fun blockCommentIsRejected() {
        rejectedBecause("SELECT /* sneaky */ COUNT(*) FROM notifications LIMIT 1", "Comments")
    }

    // ── Dangerous verbs ──────────────────────────────────────────────────────

    @Test
    fun pragmaIsRejected() = rejectedBecause("PRAGMA table_info(notifications)", "SELECT statements")

    @Test
    fun attachIsRejected() =
        rejectedBecause(
            "SELECT 1 FROM notifications WHERE 1=1 LIMIT 1 ATTACH DATABASE 'x' AS y",
            "Forbidden keyword: attach",
        )

    @Test
    fun sqliteMasterIsRejected() {
        rejectedBecause("SELECT name FROM sqlite_master LIMIT 10", "Forbidden keyword: sqlite_master")
    }

    // ── Table allow-list ─────────────────────────────────────────────────────

    @Test
    fun unknownTableIsRejected() {
        rejectedBecause("SELECT COUNT(*) FROM android_metadata LIMIT 5", "Unknown table")
    }

    @Test
    fun allFourWallTablesAreKnown() {
        listOf("notifications", "verdict_cache", "sender_bias", "overrides").forEach { table ->
            assertTrue("$table must be queryable", allowed("SELECT COUNT(*) FROM $table LIMIT 1"))
        }
    }

    // ── Limits ───────────────────────────────────────────────────────────────

    @Test
    fun missingLimitIsRejected() {
        rejectedBecause("SELECT packageName FROM notifications", "LIMIT")
    }

    @Test
    fun limitAboveTheCapIsRejected() {
        rejectedBecause("SELECT packageName FROM notifications LIMIT 100000", "at most")
    }

    @Test
    fun limitAtTheCapIsAllowed() {
        assertTrue(allowed("SELECT packageName FROM notifications LIMIT ${SqlValidator.MAX_LIMIT}"))
    }

    // ── Content columns ──────────────────────────────────────────────────────

    @Test
    fun selectingTextIsRejectedWhenContentIsNotAllowed() {
        rejectedBecause("SELECT text FROM notifications LIMIT 10", "Column 'text'", content = false)
    }

    @Test
    fun selectingTitleIsRejectedWhenContentIsNotAllowed() {
        rejectedBecause("SELECT title FROM notifications LIMIT 10", "Column 'title'", content = false)
    }

    @Test
    fun selectingStarIsRejectedWhenContentIsNotAllowed() {
        rejectedBecause("SELECT * FROM notifications LIMIT 10", "SELECT *", content = false)
    }

    @Test
    fun contentColumnsAreAllowedWhenTheUserOptsIn() {
        assertTrue(allowed("SELECT title, text FROM notifications LIMIT 10", content = true))
    }

    @Test
    fun filteringOnTextWithoutSelectingItIsStillRejected() {
        rejectedBecause(
            "SELECT COUNT(*) FROM notifications WHERE text LIKE '%salary%' LIMIT 1",
            "Column 'text'",
            content = false,
        )
    }

    // ── Junk ─────────────────────────────────────────────────────────────────

    @Test
    fun emptyStringIsRejected() = rejectedBecause("", "Empty statement")

    @Test
    fun proseIsRejected() = rejectedBecause("Here is the query you asked for:", "SELECT statements")

    @Test
    fun markdownFencedSqlIsUnwrappedAndAllowed() {
        assertTrue(allowed("```sql\nSELECT COUNT(*) FROM notifications LIMIT 1\n```"))
    }

    // ── P7: COUNT(*) must not be confused with a content-leaking SELECT * ─────

    @Test
    fun countStarIsAllowedWithoutContent() {
        assertTrue(allowed("SELECT COUNT(*) FROM notifications LIMIT 1", content = false))
    }

    @Test
    fun countStarLowercaseIsAllowedWithoutContent() {
        assertTrue(allowed("select count(*) from notifications limit 1", content = false))
    }

    @Test
    fun countStarWithSpaceInsideParensIsAllowed() {
        assertTrue(allowed("SELECT COUNT( * ) FROM notifications LIMIT 1", content = false))
    }

    @Test
    fun countStarAlongsideGroupByIsAllowed() {
        assertTrue(
            allowed(
                "SELECT packageName, COUNT(*) FROM notifications GROUP BY packageName LIMIT 20",
                content = false,
            ),
        )
    }

    // ── P7: naive `SELECT *` detection must not have gaps ─────────────────────

    @Test
    fun selectWithDoubleSpaceThenStarIsRejectedWithoutContent() {
        rejectedBecause("SELECT  * FROM notifications LIMIT 10", "SELECT *", content = false)
    }

    @Test
    fun selectStarOnNewlineIsRejectedWithoutContent() {
        rejectedBecause("SELECT\n* FROM notifications LIMIT 10", "SELECT *", content = false)
    }

    @Test
    fun qualifiedStarProjectionIsRejectedWithoutContent() {
        rejectedBecause("SELECT n.* FROM notifications n LIMIT 10", "SELECT *", content = false)
    }

    @Test
    fun qualifiedStarProjectionAmongOthersIsRejectedWithoutContent() {
        rejectedBecause(
            "SELECT n.*, b.bias FROM notifications n JOIN sender_bias b ON b.packageName = n.packageName LIMIT 10",
            "SELECT *",
            content = false,
        )
    }

    // ── Critical 1 (review fix): star projection with no space before FROM ────
    // PROJECTION previously required `\s+` before `from`; with none present it
    // failed to match at all, the projection silently fell back to "", and the
    // star check on that empty string was a no-op that let the whole row's
    // content columns through.

    @Test
    fun starWithNoSpaceBeforeFromIsRejectedWithoutContent() {
        rejectedBecause("SELECT *FROM notifications LIMIT 5", "SELECT *", content = false)
    }

    @Test
    fun qualifiedStarWithNoSpaceBeforeFromIsRejectedWithoutContent() {
        rejectedBecause("SELECT n.*FROM notifications n LIMIT 5", "SELECT *", content = false)
    }

    @Test
    fun noWhitespaceAtAllAroundSelectStarAndFromIsRejectedWithoutContent() {
        rejectedBecause("SELECT*FROM notifications LIMIT 5", "SELECT *", content = false)
    }

    @Test
    fun tabsAroundTheStarProjectionAreStillRejectedWithoutContent() {
        rejectedBecause("SELECT\t*\tFROM notifications LIMIT 5", "SELECT *", content = false)
    }

    // ── P7: bare-substring traps for the content-column guard ─────────────────

    @Test
    fun columnNamedTitlesIsNotConfusedWithTitle() {
        // "titles" contains "title" as a substring but is a distinct identifier.
        assertTrue(allowed("SELECT titles FROM notifications LIMIT 10", content = false))
    }

    @Test
    fun stringLiteralContainingTitleIsNotConfusedWithTheColumn() {
        assertTrue(
            allowed("SELECT packageName FROM notifications WHERE packageName = 'com.title.app' LIMIT 10", content = false),
        )
    }

    @Test
    fun unquotedIdentifierContainingTextAsASubstringIsNotConfusedWithTheColumn() {
        // "context" contains "text" as a substring but is a distinct word.
        assertTrue(allowed("SELECT context FROM notifications LIMIT 10", content = false))
    }

    @Test
    fun stringLiteralContainingTextIsNotConfusedWithTheColumn() {
        assertTrue(
            allowed("SELECT packageName FROM notifications WHERE packageName = 'context.app' LIMIT 10", content = false),
        )
    }

    @Test
    fun qualifiedTitleColumnReferenceIsRejectedWithoutContent() {
        rejectedBecause("SELECT n.title FROM notifications n LIMIT 10", "Column 'title'", content = false)
    }

    @Test
    fun titleColumnInWhereClauseIsRejectedWithoutContent() {
        rejectedBecause(
            "SELECT COUNT(*) FROM notifications WHERE title LIKE '%hi%' LIMIT 1",
            "Column 'title'",
            content = false,
        )
    }

    // ── Critical 2 (review fix): mandatory LIMIT satisfiable by a literal ─────
    // The final LIMIT search previously ran on `lower`, the one check that
    // skipped literal masking, so a WHERE clause containing the word "limit"
    // inside a quoted string satisfied the "a LIMIT is present" requirement
    // without any real LIMIT clause bounding the result set.

    @Test
    fun theWordLimitInsideAStringLiteralDoesNotSatisfyTheLimitRequirement() {
        rejectedBecause(
            "SELECT packageName FROM notifications WHERE packageName = 'x limit 1'",
            "LIMIT",
        )
    }

    // ── Critical 3 (review fix): the cap must bind the real, sole LIMIT ───────

    @Test
    fun aSecondLimitInASubqueryIsRejectedRatherThanAuthorisingAnUnboundedOuterLimit() {
        // Two "limit" occurrences in the statement -- the validator must not
        // pick the first (small) one and ignore the real, unbounded one.
        rejectedBecause(
            "SELECT (SELECT packageName FROM notifications LIMIT 1) AS p FROM notifications LIMIT 100000",
            "one LIMIT",
        )
    }

    @Test
    fun offsetCountFormLimitIsRejected() {
        // SQLite's "LIMIT offset, count" form: the number that actually bounds
        // the result is the *second* one (100000), not the first (1) that a
        // naive "first digits after limit" capture would read.
        rejectedBecause("SELECT packageName FROM notifications LIMIT 1, 100000", "LIMIT")
    }

    @Test
    fun arithmeticExpressionInLimitIsRejected() {
        rejectedBecause("SELECT packageName FROM notifications LIMIT 5*1000", "LIMIT")
    }

    @Test
    fun limitFollowedByOffsetIsStillAllowed() {
        assertTrue(allowed("SELECT packageName FROM notifications LIMIT 10 OFFSET 5"))
    }

    @Test
    fun twoGenuineLimitClausesAreRejectedRegardlessOfWhichIsSmaller() {
        // Sibling of the subquery case: two top-level-looking LIMITs, neither
        // one in a subquery. "Pick the first" would allow this at 1; "pick
        // the last" would allow it at 100000. Neither is safe, so the rule is
        // simply: exactly one.
        rejectedBecause("SELECT packageName FROM notifications LIMIT 1 LIMIT 100000", "one LIMIT")
    }

    @Test
    fun aStringLiteralMentioningOffsetDoesNotSatisfyOrConfuseTheLimitClause() {
        // Sibling of Critical 2: masking must protect every LIMIT-adjacent
        // check, not just the "is there a LIMIT at all" one.
        assertTrue(
            allowed("SELECT packageName FROM notifications WHERE packageName = 'x offset 1' LIMIT 10"),
        )
    }

    // ── Critical 4 (review fix): quoted identifiers defeat the comma guard ────
    // A double-quoted identifier can contain a "(" that drives the comma-join
    // scanner's paren-depth counter to 1, hiding a top-level comma -- and
    // thus an unvetted second table -- from it. Rather than also masking
    // ", ` and [...], the whole class of quoted/bracketed identifiers is
    // rejected outright: this schema never needs one.

    @Test
    fun doubleQuotedIdentifierHidingACommaJoinIsRejected() {
        rejectedBecause(
            "SELECT packageName, locale FROM notifications AS \"(\" , android_metadata LIMIT 5",
            "Quoted or bracketed",
        )
    }

    @Test
    fun backtickQuotedIdentifierIsRejected() {
        rejectedBecause("SELECT `title` FROM notifications LIMIT 5", "Quoted or bracketed")
    }

    @Test
    fun squareBracketQuotedIdentifierIsRejected() {
        rejectedBecause("SELECT [title] FROM notifications LIMIT 5", "Quoted or bracketed")
    }

    // ── Important 6 (review fix): SELECT spanning multiple lines ──────────────

    @Test
    fun selectFollowedByNewlineBeforeTheFirstColumnIsAllowed() {
        assertTrue(allowed("SELECT\npackageName FROM notifications LIMIT 10"))
    }

    // ── Important 7 (review fix): unbalanced parentheses ───────────────────────

    @Test
    fun unbalancedClosingParenIsRejected() {
        rejectedBecause("SELECT packageName FROM notifications) LIMIT 10", "Unbalanced")
    }

    @Test
    fun unbalancedOpeningParenIsRejected() {
        rejectedBecause("SELECT COUNT(* FROM notifications LIMIT 10", "Unbalanced")
    }

    // ── Comma-join table smuggling ─────────────────────────────────────────────
    // FROM_OR_JOIN only ever captures the identifier immediately after `from`
    // or `join`; an old-style comma join could otherwise sneak a second,
    // unchecked table past the allow-list without ever saying "join".

    @Test
    fun commaJoinToAnUnknownTableIsRejected() {
        rejectedBecause(
            "SELECT n.packageName FROM notifications n, android_metadata a LIMIT 10",
            "Comma joins",
        )
    }

    @Test
    fun commaJoinBetweenTwoKnownTablesIsStillRejected() {
        // Comma joins are unsupported outright, not merely allow-list-checked,
        // since only the first table name is ever inspected.
        rejectedBecause(
            "SELECT n.packageName FROM notifications n, sender_bias b LIMIT 10",
            "Comma joins",
        )
    }

    @Test
    fun commaInsideAnInListWithinAJoinConditionIsNotMistakenForACommaJoin() {
        assertTrue(
            allowed(
                "SELECT n.packageName FROM notifications n JOIN sender_bias b " +
                    "ON b.packageName IN (n.packageName, n.appLabel) LIMIT 10",
            ),
        )
    }

    @Test
    fun commaInGroupByAfterASingleFromTableIsStillAllowed() {
        assertTrue(
            allowed("SELECT packageName, appLabel, COUNT(*) FROM notifications GROUP BY packageName, appLabel LIMIT 10"),
        )
    }
}
