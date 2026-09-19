package com.anuj.notificationfirewall.ai.ask

import org.junit.Assert.assertTrue
import org.junit.Test

class SqlValidatorTest {

    private fun allowed(sql: String, content: Boolean = false) =
        SqlValidator.validate(sql, allowContent = content) is SqlVerdict.Allowed

    private fun rejected(sql: String, content: Boolean = false) =
        SqlValidator.validate(sql, allowContent = content) is SqlVerdict.Rejected

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
    fun deleteIsRejected() = assertTrue(rejected("DELETE FROM notifications"))

    @Test
    fun updateIsRejected() = assertTrue(rejected("UPDATE notifications SET isRead = 1 LIMIT 1"))

    @Test
    fun insertIsRejected() =
        assertTrue(rejected("INSERT INTO overrides (kind) VALUES ('BLOCK')"))

    @Test
    fun dropIsRejected() = assertTrue(rejected("DROP TABLE notifications"))

    @Test
    fun alterIsRejected() = assertTrue(rejected("ALTER TABLE notifications ADD COLUMN x TEXT"))

    @Test
    fun createIsRejected() = assertTrue(rejected("CREATE TABLE evil (x TEXT)"))

    // ── Statement smuggling ──────────────────────────────────────────────────

    @Test
    fun trailingStatementIsRejected() {
        assertTrue(rejected("SELECT COUNT(*) FROM notifications LIMIT 1; DROP TABLE notifications"))
    }

    @Test
    fun trailingSemicolonAloneIsAllowed() {
        assertTrue(allowed("SELECT COUNT(*) FROM notifications LIMIT 1;"))
    }

    @Test
    fun commentHidingASecondStatementIsRejected() {
        assertTrue(rejected("SELECT 1 FROM notifications LIMIT 1 -- ; DROP TABLE notifications"))
    }

    @Test
    fun blockCommentIsRejected() {
        assertTrue(rejected("SELECT /* sneaky */ COUNT(*) FROM notifications LIMIT 1"))
    }

    // ── Dangerous verbs ──────────────────────────────────────────────────────

    @Test
    fun pragmaIsRejected() = assertTrue(rejected("PRAGMA table_info(notifications)"))

    @Test
    fun attachIsRejected() =
        assertTrue(rejected("SELECT 1 FROM notifications WHERE 1=1 LIMIT 1 ATTACH DATABASE 'x' AS y"))

    @Test
    fun sqliteMasterIsRejected() {
        assertTrue(rejected("SELECT name FROM sqlite_master LIMIT 10"))
    }

    // ── Table allow-list ─────────────────────────────────────────────────────

    @Test
    fun unknownTableIsRejected() {
        assertTrue(rejected("SELECT * FROM android_metadata LIMIT 5"))
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
        assertTrue(rejected("SELECT packageName FROM notifications"))
    }

    @Test
    fun limitAboveTheCapIsRejected() {
        assertTrue(rejected("SELECT packageName FROM notifications LIMIT 100000"))
    }

    @Test
    fun limitAtTheCapIsAllowed() {
        assertTrue(allowed("SELECT packageName FROM notifications LIMIT ${SqlValidator.MAX_LIMIT}"))
    }

    // ── Content columns ──────────────────────────────────────────────────────

    @Test
    fun selectingTextIsRejectedWhenContentIsNotAllowed() {
        assertTrue(rejected("SELECT text FROM notifications LIMIT 10", content = false))
    }

    @Test
    fun selectingTitleIsRejectedWhenContentIsNotAllowed() {
        assertTrue(rejected("SELECT title FROM notifications LIMIT 10", content = false))
    }

    @Test
    fun selectingStarIsRejectedWhenContentIsNotAllowed() {
        assertTrue(
            "SELECT * would leak title and text",
            rejected("SELECT * FROM notifications LIMIT 10", content = false),
        )
    }

    @Test
    fun contentColumnsAreAllowedWhenTheUserOptsIn() {
        assertTrue(allowed("SELECT title, text FROM notifications LIMIT 10", content = true))
    }

    @Test
    fun filteringOnTextWithoutSelectingItIsStillRejected() {
        assertTrue(
            "a WHERE on text can leak content one row at a time",
            rejected("SELECT COUNT(*) FROM notifications WHERE text LIKE '%salary%' LIMIT 1", content = false),
        )
    }

    // ── Junk ─────────────────────────────────────────────────────────────────

    @Test
    fun emptyStringIsRejected() = assertTrue(rejected(""))

    @Test
    fun proseIsRejected() = assertTrue(rejected("Here is the query you asked for:"))

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
        assertTrue(rejected("SELECT  * FROM notifications LIMIT 10", content = false))
    }

    @Test
    fun selectStarOnNewlineIsRejectedWithoutContent() {
        assertTrue(rejected("SELECT\n* FROM notifications LIMIT 10", content = false))
    }

    @Test
    fun qualifiedStarProjectionIsRejectedWithoutContent() {
        assertTrue(rejected("SELECT n.* FROM notifications n LIMIT 10", content = false))
    }

    @Test
    fun qualifiedStarProjectionAmongOthersIsRejectedWithoutContent() {
        assertTrue(rejected("SELECT n.*, b.bias FROM notifications n JOIN sender_bias b ON b.packageName = n.packageName LIMIT 10", content = false))
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
        assertTrue(rejected("SELECT n.title FROM notifications n LIMIT 10", content = false))
    }

    @Test
    fun titleColumnInWhereClauseIsRejectedWithoutContent() {
        assertTrue(
            rejected("SELECT COUNT(*) FROM notifications WHERE title LIKE '%hi%' LIMIT 1", content = false),
        )
    }

    // ── Comma-join table smuggling ─────────────────────────────────────────────
    // FROM_OR_JOIN only ever captures the identifier immediately after `from`
    // or `join`; an old-style comma join could otherwise sneak a second,
    // unchecked table past the allow-list without ever saying "join".

    @Test
    fun commaJoinToAnUnknownTableIsRejected() {
        assertTrue(
            rejected("SELECT n.packageName FROM notifications n, android_metadata a LIMIT 10"),
        )
    }

    @Test
    fun commaJoinBetweenTwoKnownTablesIsStillRejected() {
        // Comma joins are unsupported outright, not merely allow-list-checked,
        // since only the first table name is ever inspected.
        assertTrue(
            rejected("SELECT n.packageName FROM notifications n, sender_bias b LIMIT 10"),
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
