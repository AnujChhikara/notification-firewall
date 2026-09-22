package com.anuj.notificationfirewall.ai.ask

import android.util.Log
import com.anuj.notificationfirewall.ai.OpenAiClient
import com.anuj.notificationfirewall.data.db.dao.RawQueryDao
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    data class Answered(
        val text: String,
        val sql: String,
        val result: QueryResult,
        /** Every query the agent ran, oldest first. Empty for the one-shot path. */
        val steps: List<AgentStep> = emptyList(),
    ) : AskOutcome
    data class Refused(val reason: String, val sql: String?) : AskOutcome
    data class Failed(val reason: String) : AskOutcome
}

/** One vetted query the agent ran, with how many rows it returned. */
data class AgentStep(val sql: String, val rowCount: Int)

/** One turn of the agent protocol: run more SQL, or answer from the rows seen. */
internal data class AgentMove(val sql: String? = null, val answer: String? = null) {
    companion object {
        private val JsonLenient = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Null when the reply is neither a move object nor usable at all. */
        fun parse(raw: String): AgentMove? = runCatching {
            val obj = JsonLenient.parseToJsonElement(raw).jsonObject
            AgentMove(
                sql = obj["sql"]?.jsonPrimitive?.contentOrNull,
                answer = obj["answer"]?.jsonPrimitive?.contentOrNull,
            )
        }.getOrNull()?.takeIf { it.sql != null || it.answer != null }
    }
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

    companion object {
        /** How many queries the agent may run before it must answer. */
        const val MAX_AGENT_QUERIES = 3
    }

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

    /**
     * The agentic loop: the model sees the data format up front and chooses up
     * to [MAX_AGENT_QUERIES] queries, each informed by the last one's rows,
     * before answering. Every generated statement passes through the exact
     * same two gates as the one-shot path ([SqlValidator], then [RawQueryDao]),
     * and a refusal or failure at any step ends the run immediately -- a loop
     * must not get more chances to talk its way past a gate than a single
     * question does.
     */
    suspend fun askDeep(question: String, allowContent: Boolean): AskOutcome {
        val steps = mutableListOf<AgentStep>()
        var lastSql = ""
        var lastResult: QueryResult? = null
        val transcript = StringBuilder("Question: $question")
        val now = System.currentTimeMillis()

        repeat(MAX_AGENT_QUERIES) { n ->
            val raw = try {
                openAi.chat(
                    model = model,
                    systemPrompt = AskSchema.agentPrompt(allowContent, now),
                    userContent = transcript.toString(),
                    jsonMode = true,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Agent step failed", e)
                return AskOutcome.Failed("Could not reach the model: ${e.message}")
            }

            val move = AgentMove.parse(raw)
            if (move?.answer != null) {
                if (steps.isEmpty()) {
                    // An answer with no evidence is a guess wearing confidence.
                    return AskOutcome.Failed("The model answered without looking at any data.")
                }
                return AskOutcome.Answered(move.answer.trim(), lastSql, lastResult!!, steps.toList())
            }
            val sql = move?.sql
                ?: return AskOutcome.Failed("The model replied in an unusable format.")

            val checked = when (val verdict = SqlValidator.validate(sql, allowContent)) {
                is SqlVerdict.Rejected -> return AskOutcome.Refused(verdict.reason, sql)
                is SqlVerdict.Allowed -> verdict.sql
            }

            val result = try {
                rawQueryDao.run(checked, allowContent)
            } catch (e: UnsafeQueryException) {
                Log.w(TAG, "Execution gate refused an agent query", e)
                return AskOutcome.Refused(e.message ?: "That query was refused at execution time", checked)
            } catch (e: Exception) {
                Log.w(TAG, "Agent query failed to execute", e)
                return AskOutcome.Failed("That query could not run: ${e.message}")
            }

            steps += AgentStep(checked, result.rows.size)
            lastSql = checked
            lastResult = result
            transcript.append("\n\nAfter query ${n + 1}:\n$checked\nRows:\n${result.toTsv()}")
        }

        // Budget spent without an answer: phrase from the gathered evidence
        // rather than inventing one, using the same phrasing contract.
        val answer = try {
            openAi.chat(
                model = model,
                systemPrompt = AskSchema.PHRASING_PROMPT,
                userContent = "Question: $question\n\nEvidence:$transcript",
                jsonMode = false,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Agent phrasing failed", e)
            return AskOutcome.Failed("Could not phrase the answer: ${e.message}")
        }
        return AskOutcome.Answered(answer.trim(), lastSql, lastResult!!, steps.toList())
    }
}
