package com.anuj.notificationfirewall.ai

import com.anuj.notificationfirewall.data.db.NotificationRecordEntity

private const val MODEL = "gpt-4o"

private const val SYSTEM_PROMPT = """
You are a notification digest assistant. The user just left a Do Not Disturb
profile and wants a brief catch-up on what they missed while notifications
were being silenced.

Given a list of notifications (each with the sending app, sender, and title),
write a short, friendly plain-text summary that groups related notifications
and highlights anything that looks important. Keep it to a few sentences.
Respond with plain text only, not JSON.
"""

/**
 * Summarizes the notifications captured during a profile window using an
 * OpenAI chat completion.
 *
 * Fail-safe: an empty list never triggers an HTTP call, and any HTTP error
 * falls back to a plain count-based message instead of throwing.
 */
class OpenAiDigestService(
    private val client: OpenAiClient
) : DigestService {

    override suspend fun summarize(records: List<NotificationRecordEntity>): String {
        if (records.isEmpty()) {
            return "Nothing came through while you were away."
        }

        return try {
            val userContent = records.joinToString("\n") { record ->
                "${record.appLabel} | ${record.senderKey} | ${record.title}"
            }

            client.chat(
                model = MODEL,
                systemPrompt = SYSTEM_PROMPT,
                userContent = userContent,
                jsonMode = false
            )
        } catch (e: Exception) {
            "You missed ${records.size} notifications. (Summary unavailable.)"
        }
    }
}
