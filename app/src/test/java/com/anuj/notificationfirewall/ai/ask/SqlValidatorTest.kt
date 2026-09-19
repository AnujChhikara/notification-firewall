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

    // ── Critical A (re-review fix): star-projection class still had siblings ──
    // A DISTINCT/ALL quantifier before the star, whitespace inside a
    // qualified star's dot, and a subquery embedded in the projection (which
    // truncated the old regex-based projection extraction before the real,
    // later star was ever seen) all escaped the Critical-1 fix.

    @Test
    fun distinctStarIsRejectedWithoutContent() {
        rejectedBecause("SELECT DISTINCT * FROM notifications LIMIT 5", "SELECT *", content = false)
    }

    @Test
    fun allStarIsRejectedWithoutContent() {
        rejectedBecause("SELECT ALL * FROM notifications LIMIT 5", "SELECT *", content = false)
    }

    @Test
    fun distinctQualifiedStarIsRejectedWithoutContent() {
        rejectedBecause("SELECT DISTINCT n.* FROM notifications n LIMIT 5", "SELECT *", content = false)
    }

    @Test
    fun spaceBeforeTheDotInAQualifiedStarIsStillRejectedWithoutContent() {
        rejectedBecause("SELECT n .* FROM notifications n LIMIT 5", "SELECT *", content = false)
    }

    @Test
    fun starAfterASubqueryInTheProjectionIsRejectedWithoutContent() {
        // The subquery's own FROM must not be mistaken for the top-level FROM
        // that ends the projection -- if it is, the projection is truncated
        // before the real, trailing "*" is ever seen.
        rejectedBecause(
            "SELECT (SELECT COUNT(*) FROM notifications) AS c, * FROM notifications LIMIT 5",
            "SELECT *",
            content = false,
        )
    }

    @Test
    fun distinctWithoutAStarIsStillAllowed() {
        // The DISTINCT/ALL-stripping fix must not turn into a false rejection
        // of a legitimate, star-free DISTINCT query.
        assertTrue(allowed("SELECT DISTINCT packageName FROM notifications LIMIT 5", content = false))
    }

    @Test
    fun aggregateSubqueryInTheProjectionWithoutATrailingStarIsAllowed() {
        // Sibling check: a subquery in the projection is fine on its own; the
        // rejection above is specifically about the star that follows it.
        assertTrue(
            allowed(
                "SELECT (SELECT COUNT(*) FROM notifications) AS c, packageName FROM notifications LIMIT 5",
                content = false,
            ),
        )
    }

    // ── Critical B (re-review fix): comma-join guard reopened by depth==0 ─────
    // A parenthesised subquery *inside the projection* (before the real,
    // top-level FROM) has its own internal FROM. The old comma scanner always
    // located the *first* "from" anywhere in the statement as its start point
    // -- landing inside that subquery's parentheses -- so its local depth
    // count went negative on the subquery's closing paren and the comma
    // check that follows (guarded by depth == 0) could never fire again for
    // the rest of the statement.

    @Test
    fun commaJoinAfterASubqueryInTheProjectionIsRejected() {
        rejectedBecause(
            "SELECT (SELECT 1 FROM notifications) AS x, locale FROM notifications, android_metadata LIMIT 5",
            "Comma joins",
        )
    }

    @Test
    fun joinAfterASubqueryInTheProjectionIsStillAllowed() {
        // Sibling: the same subquery-in-projection shape, but a proper JOIN
        // instead of a comma join, must not be caught by the fix above.
        assertTrue(
            allowed(
                "SELECT (SELECT COUNT(*) FROM notifications) AS c, b.bias FROM notifications n " +
                    "JOIN sender_bias b ON b.packageName = n.packageName LIMIT 5",
            ),
        )
    }

    @Test
    fun noCommaJoinAfterASubqueryInTheProjectionIsAllowed() {
        // Sibling: a subquery in the projection with a single, ordinary table
        // and no comma at all must not be wrongly flagged either.
        assertTrue(
            allowed("SELECT (SELECT COUNT(*) FROM notifications) AS c, locale FROM notifications LIMIT 5"),
        )
    }

    // ── Found during self-review, siblings of Critical B: a comma join can
    // hide inside *any* FROM in the statement, not just the outermost one --
    // including one nested in a WHERE-clause subquery that never touches the
    // top-level table clause at all. Scoping the comma check to only "the"
    // top-level FROM (as the first fix for Critical B did) would still miss
    // these; the fix generalises to every FROM occurrence independently.

    @Test
    fun commaJoinInsideAWhereClauseSubqueryIsRejected() {
        rejectedBecause(
            "SELECT packageName FROM notifications WHERE packageName IN " +
                "(SELECT x FROM overrides, android_metadata) LIMIT 5",
            "Comma joins",
        )
    }

    @Test
    fun properJoinInsideAWhereClauseSubqueryIsStillAllowed() {
        assertTrue(
            allowed(
                "SELECT packageName FROM notifications WHERE packageName IN " +
                    "(SELECT o.packageName FROM overrides o JOIN sender_bias b ON b.packageName = o.packageName) LIMIT 5",
            ),
        )
    }

    @Test
    fun doublyNestedSubqueryInTheProjectionWithATrailingStarIsStillRejected() {
        rejectedBecause(
            "SELECT (SELECT (SELECT COUNT(*) FROM notifications)) AS c, * FROM notifications LIMIT 5",
            "SELECT *",
            content = false,
        )
    }

    @Test
    fun tabsAndDistinctCombinedBeforeTheStarAreStillRejectedWithoutContent() {
        rejectedBecause("SELECT\tDISTINCT\t*\tFROM notifications LIMIT 5", "SELECT *", content = false)
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

    // ── Round-3 review: compound statements (Critical A, still open) ──────────
    // `findTopLevelProjection` only ever inspects the FIRST branch of a
    // compound SELECT. A star in a later UNION/UNION ALL/INTERSECT/EXCEPT
    // branch was never checked at all -- confirmed against real SQLite to
    // return `title`/`text`. Rather than teach the parser about multiple
    // projections, the whole class is rejected outright.

    @Test
    fun unionAllWithALeakingStarInTheSecondBranchIsRejected() {
        // The exact reviewer PoC: 20 literals match the arity of
        // `notifications`, so the UNION ALL is structurally valid, and the
        // real leak is the `*` in the second branch.
        rejectedBecause(
            "SELECT 1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20 FROM notifications " +
                "UNION ALL SELECT * FROM notifications LIMIT 5",
            "Compound statements",
            content = false,
        )
    }

    @Test
    fun unionWithoutAllIsRejected() {
        rejectedBecause(
            "SELECT packageName FROM notifications UNION SELECT packageName FROM overrides LIMIT 5",
            "Compound statements",
        )
    }

    @Test
    fun intersectIsRejected() {
        rejectedBecause(
            "SELECT packageName FROM notifications INTERSECT SELECT packageName FROM overrides LIMIT 5",
            "Compound statements",
        )
    }

    @Test
    fun exceptIsRejected() {
        rejectedBecause(
            "SELECT packageName FROM notifications EXCEPT SELECT packageName FROM overrides LIMIT 5",
            "Compound statements",
        )
    }

    @Test
    fun unionAllWithNoLeftHandFromAtAllIsStillRejected() {
        // The left-hand branch doesn't even need a FROM for the compound
        // form to be dangerous -- the leak is entirely in the right branch.
        rejectedBecause(
            "SELECT 1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20 " +
                "UNION ALL SELECT * FROM notifications LIMIT 5",
            "Compound statements",
            content = false,
        )
    }

    // ── Round-3 review: parenthesised join clauses (Critical B, still open) ───
    // SQLite's grammar has `table-or-subquery := ( join-clause )`, and `,` is
    // itself a join operator, so `JOIN (a, b)` names a second table that
    // neither the comma-join scan (which only looks inside a FROM keyword's
    // own clause) nor FROM_OR_JOIN (which requires a bare identifier right
    // after from/join, not a paren) ever sees. Confirmed against real SQLite
    // to return data from the unvetted table. Fix: a `(` directly after
    // from/join is only permitted when it opens an ordinary subquery (see
    // the round-4 block at the end of this file for the refinement).

    @Test
    fun parenthesisedJoinClauseWithAnUnvettedTableIsRejected() {
        rejectedBecause(
            "SELECT locale FROM notifications JOIN (notifications, android_metadata) LIMIT 5",
            "parenthesised join clause",
        )
    }

    @Test
    fun parenthesisedJoinClauseReachingSqliteSchemaIsRejected() {
        // This exact reviewer PoC is caught earlier, by the new
        // sqlite_schema forbidden-keyword entry -- verifying both defenses.
        rejectedBecause(
            "SELECT sql FROM notifications JOIN (notifications, sqlite_schema) LIMIT 5",
            "sqlite_schema",
        )
    }

    @Test
    fun sqliteSchemaAloneIsRejectedAsAForbiddenKeyword() {
        rejectedBecause("SELECT name FROM sqlite_schema LIMIT 5", "Forbidden keyword: sqlite_schema")
    }

    @Test
    fun aliasedParenthesisedJoinClauseIsRejected() {
        rejectedBecause(
            "SELECT locale FROM notifications JOIN (notifications, android_metadata) x LIMIT 5",
            "parenthesised join clause",
        )
    }

    @Test
    fun doublyParenthesisedJoinClauseIsRejected() {
        rejectedBecause(
            "SELECT locale FROM notifications JOIN ((notifications, android_metadata)) LIMIT 5",
            "parenthesised join clause",
        )
    }

    @Test
    fun parenthesisedJoinClauseDirectlyAfterFromIsRejected() {
        rejectedBecause(
            "SELECT locale FROM (notifications, android_metadata) LIMIT 5",
            "parenthesised join clause",
        )
    }

    @Test
    fun ordinarySubqueryAfterFromIsStillAllowed() {
        assertTrue(allowed("SELECT c FROM (SELECT COUNT(*) AS c FROM notifications) LIMIT 5"))
    }

    @Test
    fun doublyWrappedSubqueryAfterFromIsStillAllowed() {
        // Sibling: the doubly-parenthesised *legitimate* form -- a subquery
        // wrapped in redundant extra parens -- must not be caught by the fix
        // for the doubly-parenthesised *attack* form above.
        assertTrue(allowed("SELECT c FROM ((SELECT COUNT(*) AS c FROM notifications)) LIMIT 5"))
    }

    @Test
    fun aliasedSubqueryAfterJoinIsStillAllowed() {
        assertTrue(
            allowed(
                "SELECT n.appLabel, b.bias FROM notifications n " +
                    "JOIN (SELECT packageName, bias FROM sender_bias) b ON b.packageName = n.packageName LIMIT 5",
            ),
        )
    }


    // ── Round 4: a subquery used as a decoy inside a parenthesised join clause ──
    // `( join-clause )` is a table-or-subquery, and `(SELECT 1)` is itself a
    // valid table-or-subquery, so `( (SELECT 1) , unvetted )` is a
    // parenthesised join clause whose *first* token is a paren opening a
    // subquery. The previous guard skipped nested `(` unconditionally before
    // looking for `select`, so it found the inner group's `select` and waved
    // the whole thing through; the comma sat at depth 1 relative to the outer
    // FROM, so the comma-join scan never saw it either, and no bare identifier
    // followed from/join, so the table allow-list never saw the table.
    // Confirmed ALLOWED against the compiled validator and executed against
    // real SQLite, returning the unvetted table's rows.
    //
    // Fix: a wrapping group (one whose first token is another `(`) introduces
    // no table of its own, so it must contain no comma at its own top level.
    // Only the innermost, `select`-opening group may contain commas.

    @Test
    fun subqueryDecoyInAParenthesisedJoinClauseIsRejected() {
        rejectedBecause(
            "SELECT locale FROM notifications JOIN ((SELECT 1), android_metadata) LIMIT 5",
            "parenthesised join clause",
        )
    }

    @Test
    fun subqueryDecoyReachingRoomMasterTableIsRejected() {
        rejectedBecause(
            "SELECT identity_hash FROM notifications JOIN ((SELECT 1), room_master_table) LIMIT 5",
            "parenthesised join clause",
        )
    }

    @Test
    fun subqueryDecoyReachingAnArbitraryTableIsRejected() {
        rejectedBecause(
            "SELECT k FROM notifications JOIN ((SELECT 1), secrets) LIMIT 5",
            "parenthesised join clause",
        )
    }

    @Test
    fun aliasedSubqueryDecoyWithAnAliasedUnvettedTableIsRejected() {
        rejectedBecause(
            "SELECT n.appLabel, m.locale FROM notifications n " +
                "JOIN ((SELECT 1 AS k), android_metadata m) LIMIT 5",
            "parenthesised join clause",
        )
    }

    @Test
    fun subqueryDecoyWithNoSpaceAnywhereIsRejected() {
        rejectedBecause(
            "SELECT locale FROM notifications JOIN((SELECT 1),android_metadata) LIMIT 5",
            "parenthesised join clause",
        )
    }

    @Test
    fun subqueryDecoyWithANewlineBeforeTheCommaIsRejected() {
        rejectedBecause(
            "SELECT locale FROM notifications JOIN ( (SELECT 1)\n, android_metadata ) LIMIT 5",
            "parenthesised join clause",
        )
    }

    @Test
    fun subqueryDecoyWithTabsAroundTheCommaIsRejected() {
        rejectedBecause(
            "SELECT locale FROM notifications JOIN (\t(SELECT 1),\tandroid_metadata) LIMIT 5",
            "parenthesised join clause",
        )
    }

    @Test
    fun subqueryDecoyWithAliasesOnBothSidesIsRejected() {
        rejectedBecause(
            "SELECT locale FROM notifications JOIN ((SELECT 1) AS z, android_metadata AS m) LIMIT 5",
            "parenthesised join clause",
        )
    }

    @Test
    fun subqueryDecoyWithTheUnvettedTableItselfParenthesisedIsRejected() {
        rejectedBecause(
            "SELECT locale FROM notifications JOIN ((SELECT 1), (android_metadata)) LIMIT 5",
            "parenthesised join clause",
        )
    }

    @Test
    fun subqueryDecoyWithTheUnvettedTableFirstIsRejected() {
        rejectedBecause(
            "SELECT locale FROM notifications JOIN (android_metadata, (SELECT 1)) LIMIT 5",
            "parenthesised join clause",
        )
    }

    @Test
    fun triplyNestedSubqueryDecoyIsRejected() {
        rejectedBecause(
            "SELECT locale FROM notifications JOIN (((SELECT 1), android_metadata)) LIMIT 5",
            "parenthesised join clause",
        )
    }

    @Test
    fun quadruplyNestedSubqueryDecoyIsRejected() {
        rejectedBecause(
            "SELECT locale FROM notifications JOIN ((((SELECT 1), android_metadata))) LIMIT 5",
            "parenthesised join clause",
        )
    }

    @Test
    fun subqueryDecoyAfterCrossJoinIsRejected() {
        rejectedBecause(
            "SELECT locale FROM notifications CROSS JOIN ((SELECT 1), android_metadata) LIMIT 5",
            "parenthesised join clause",
        )
    }

    @Test
    fun subqueryDecoyDirectlyAfterFromIsRejected() {
        rejectedBecause(
            "SELECT locale FROM ((SELECT 1), android_metadata) LIMIT 5",
            "parenthesised join clause",
        )
    }

    @Test
    fun subqueryDecoyWithAFullClauseTailIsRejected() {
        rejectedBecause(
            "SELECT m.locale FROM notifications n JOIN ((SELECT 1 AS k), android_metadata m) " +
                "ON 1=1 WHERE n.id > 0 GROUP BY m.locale HAVING COUNT(*) > 0 LIMIT 5",
            "parenthesised join clause",
        )
    }

    @Test
    fun subqueryDecoyNestedInsideAWhereClauseSubqueryIsRejected() {
        rejectedBecause(
            "SELECT packageName FROM notifications WHERE id IN " +
                "(SELECT 1 FROM notifications JOIN ((SELECT 1), android_metadata)) LIMIT 5",
            "parenthesised join clause",
        )
    }

    @Test
    fun subqueryDecoyJoiningThreeItemsIsRejected() {
        rejectedBecause(
            "SELECT locale FROM notifications JOIN ((SELECT 1), android_metadata, secrets) LIMIT 5",
            "parenthesised join clause",
        )
    }

    @Test
    fun subqueryDecoyWhoseFirstGroupSelectsFromAKnownTableIsRejected() {
        rejectedBecause(
            "SELECT locale FROM notifications JOIN ((SELECT 1 FROM notifications), android_metadata) LIMIT 5",
            "parenthesised join clause",
        )
    }

    @Test
    fun twoParenthesisedSubqueriesJoinedByACommaAreRejected() {
        // Both sides are subqueries, so nothing unvetted is reached here --
        // but it is still a parenthesised join clause, and the guard must not
        // depend on recognising which side is the dangerous one.
        rejectedBecause(
            "SELECT locale FROM notifications JOIN ((SELECT 1) , (SELECT 2)) LIMIT 5",
            "parenthesised join clause",
        )
    }

    @Test
    fun aWrappedBareTableIsRejected() {
        rejectedBecause(
            "SELECT locale FROM notifications JOIN ((android_metadata)) LIMIT 5",
            "parenthesised join clause",
        )
    }

    // ── No overcorrection: legitimate parenthesised subqueries still pass ────

    @Test
    fun triplyWrappedSubqueryAfterFromIsStillAllowed() {
        assertTrue(allowed("SELECT c FROM (((SELECT COUNT(*) AS c FROM notifications))) LIMIT 5"))
    }

    @Test
    fun aWrappedSubqueryWhoseOwnProjectionHasCommasIsStillAllowed() {
        // The commas here belong to the innermost group's own projection, not
        // to any join -- the no-comma rule applies only to wrapping groups.
        assertTrue(
            allowed("SELECT x FROM ((SELECT packageName AS x, appLabel AS y FROM notifications)) LIMIT 5"),
        )
    }

    @Test
    fun aWrappedSubqueryContainingANestedScalarSubqueryIsStillAllowed() {
        assertTrue(
            allowed(
                "SELECT a FROM ((SELECT packageName AS a, " +
                    "(SELECT COUNT(*) FROM overrides) AS b FROM notifications)) LIMIT 5",
            ),
        )
    }

}
