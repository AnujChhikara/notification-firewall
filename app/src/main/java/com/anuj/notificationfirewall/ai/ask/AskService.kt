package com.anuj.notificationfirewall.ai.ask

import android.util.Log
import com.anuj.notificationfirewall.ai.OpenAiClient
import com.anuj.notificationfirewall.data.db.dao.RawQueryDao

private const val TAG = "AskService"

data class QueryResult(val columns: List<String>, val rows: List<List<String>>) {
    val isEmpty: Boolean get() = rows.isEmpty()

    /** Compact rendering for the phrasing call — the only data that leaves the device. */
    fun toTsv(): String = buildString {
        appendLine(columns.joinToString("\t"))
        rows.forEach { appendLine(it.joinToString("\t")) }
    }
}

/**
 * Thrown by the execution-time gate when SQLite's own account of a statement —
 * its query plan, or the columns its cursor would return — is not something we
 * are willing to run. Distinct from a plain [Exception] so the caller can tell
 * "refused on principle" from "the query blew up", and never carries a value
 * read out of the database.
 */
class UnsafeQueryException(message: String) : RuntimeException(message)

sealed interface AskOutcome {
    data class Answered(val text: String, val sql: String, val result: QueryResult) : AskOutcome
    data class Refused(val reason: String, val sql: String?) : AskOutcome
    data class Failed(val reason: String) : AskOutcome
}

/**
 * Answers questions about the user's notification history without their
 * notifications leaving the device.
 *
 * Two model calls, and what each one sees matters:
 *   1. Generate — sees the table schema and the question. No data.
 *   2. Phrase   — sees the question and the aggregate rows the device computed.
 *
 * Between them sit two gates, both real. [SqlValidator] vets the string; then
 * [RawQueryDao] makes SQLite itself vet the statement at execution time. A
 * refusal from either stops the flow entirely rather than falling back to
 * something looser: if the generated statement is not something we are willing
 * to run, there is nothing to phrase, and in particular the second model call
 * is never made.
 */
class AskService(
    private val openAi: OpenAiClient,
    private val rawQueryDao: RawQueryDao,
    private val model: String,
) {

    suspend fun ask(question: String, allowContent: Boolean): AskOutcome {
        val raw = try {
            openAi.chat(
                model = model,
                systemPrompt = AskSchema.systemPrompt(allowContent, System.currentTimeMillis()),
                userContent = question,
                jsonMode = false,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Query generation failed", e)
            return AskOutcome.Failed("Could not reach the model: ${e.message}")
        }

        val sql = when (val verdict = SqlValidator.validate(raw, allowContent)) {
            is SqlVerdict.Rejected -> return AskOutcome.Refused(verdict.reason, raw)
            is SqlVerdict.Allowed -> verdict.sql
        }

        val result = try {
            rawQueryDao.run(sql, allowContent)
        } catch (e: UnsafeQueryException) {
            // The string passed the validator and SQLite still disagreed. That
            // is a refusal, not a failure, and the user is told which.
            Log.w(TAG, "Execution gate refused a validated query", e)
            return AskOutcome.Refused(e.message ?: "That query was refused at execution time", sql)
        } catch (e: Exception) {
            Log.w(TAG, "Validated query failed to execute", e)
            return AskOutcome.Failed("That query could not run: ${e.message}")
        }

        val answer = try {
            openAi.chat(
                model = model,
                systemPrompt = AskSchema.PHRASING_PROMPT,
                userContent = "Question: $question\n\nRows:\n${result.toTsv()}",
                jsonMode = false,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Phrasing failed", e)
            return AskOutcome.Failed("Could not phrase the answer: ${e.message}")
        }

        return AskOutcome.Answered(answer.trim(), sql, result)
    }
}
