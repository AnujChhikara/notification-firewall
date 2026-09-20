package com.anuj.notificationfirewall.ai.ask

/**
 * The only thing about the user's data that is ever sent to OpenAI ahead of a
 * question: the shape of the tables, never their contents.
 *
 * The DDL below is a description written for a model, not the schema Room
 * emits -- but it is checked against the real entities, because a model told
 * the wrong column list writes SQL that the validator passes and SQLite then
 * rejects, which the user experiences as the feature being broken. In
 * particular `verdict_cache` really is keyed on the
 * `(packageName, senderKey, contentShape)` triple (MIGRATION_6_7), and
 * `decisionSource` really does include `EXPIRED` -- the value
 * `NotificationDao.clearPending` writes when text was purged before a verdict
 * could ever be obtained, which is why an EXPIRED row's verdict columns are
 * NULL.
 */
internal object AskSchema {

    val DDL = """
        CREATE TABLE notifications (
          id INTEGER PRIMARY KEY,
          packageName TEXT NOT NULL,     -- e.g. 'com.myntra'
          appLabel TEXT NOT NULL,        -- e.g. 'Myntra'
          title TEXT,                    -- CONTENT: usually the sender's name
          text TEXT,                     -- CONTENT: the message body
          timestampEpochMs INTEGER NOT NULL,
          senderKey TEXT,                -- sender or conversation, may be NULL
          contentShape TEXT NOT NULL,
          importanceScore REAL,          -- 1.0 (noise) .. 5.0 (critical)
          biasApplied REAL NOT NULL,     -- learned nudge, -0.75 .. 0.75
          category TEXT,                 -- PROMOTION|PERSONAL_MESSAGE|TRANSACTIONAL|
                                         -- WORK|SOCIAL|NEWS|SYSTEM|DELIVERY|OTHER
          isTimeSensitive REAL,          -- 0..1
          isFromHuman REAL,              -- 0..1; high means a person wrote it
          needsAction REAL,              -- 0..1
          jevConfidence REAL,            -- 0..1
          decisionSource TEXT NOT NULL,  -- OTP|VIP|BLOCK|CACHE|JEV|PENDING|LEGACY|EXPIRED
                                         -- LEGACY: judged by an older app version.
                                         -- PENDING: not judged yet.
                                         -- EXPIRED: text purged before any verdict, so
                                         -- importanceScore/category/isFromHuman/
                                         -- needsAction/isTimeSensitive/jevConfidence
                                         -- are NULL. NULLs are skipped by aggregates;
                                         -- do not COALESCE them to a number.
          bucket TEXT NOT NULL,          -- RING|SILENCE|DROP
          pendingClassification INTEGER NOT NULL,
          textPurgedAt INTEGER,          -- non-NULL once title/text were purged
          isRead INTEGER NOT NULL
        );
        CREATE TABLE verdict_cache (
          packageName TEXT NOT NULL, senderKey TEXT NOT NULL, contentShape TEXT NOT NULL,
          importance REAL NOT NULL, category TEXT NOT NULL,
          isTimeSensitive REAL NOT NULL, isFromHuman REAL NOT NULL, needsAction REAL NOT NULL,
          confidence REAL NOT NULL, hitCount INTEGER NOT NULL,
          createdAtEpochMs INTEGER NOT NULL, lastUsedEpochMs INTEGER NOT NULL,
          PRIMARY KEY (packageName, senderKey, contentShape)
        );
        CREATE TABLE sender_bias (
          packageName TEXT NOT NULL, senderKey TEXT NOT NULL, bias REAL NOT NULL,
          correctionCount INTEGER NOT NULL, lastCorrectedEpochMs INTEGER NOT NULL,
          PRIMARY KEY (packageName, senderKey)
        );
        CREATE TABLE overrides (
          id INTEGER PRIMARY KEY, kind TEXT NOT NULL, packageName TEXT NOT NULL,
          senderKey TEXT, label TEXT NOT NULL, source TEXT NOT NULL,
          createdAtEpochMs INTEGER NOT NULL
        );
    """.trimIndent()

    fun systemPrompt(allowContent: Boolean, nowEpochMs: Long): String = """
        You translate a question about a personal notification history into ONE SQLite SELECT.

        Schema:
        $DDL

        Rules:
        - Reply with the SQL statement and nothing else. No prose, no markdown fence.
        - Exactly one statement. SELECT only. No semicolons, no comments.
        - Always include a LIMIT of at most ${SqlValidator.MAX_LIMIT}.
        - Timestamps are epoch milliseconds. Now is $nowEpochMs.
        - Prefer aggregates (COUNT, AVG, GROUP BY) over returning raw rows.
        ${if (allowContent) {
            "- You MAY select or filter on the title and text columns for this question."
        } else {
            "- You must NOT reference the title or text columns anywhere, including WHERE clauses."
        }}
    """.trimIndent()

    const val PHRASING_PROMPT =
        "You are given a question and the result rows of a query over the user's own " +
            "notification history. Answer the question directly in one or two sentences, " +
            "citing the numbers. Do not mention SQL. If the rows are empty, say so plainly."
}
