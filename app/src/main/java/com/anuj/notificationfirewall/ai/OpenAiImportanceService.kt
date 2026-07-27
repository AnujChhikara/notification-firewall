package com.anuj.notificationfirewall.ai

import com.anuj.notificationfirewall.domain.model.IncomingNotification
import com.anuj.notificationfirewall.domain.model.Verdict
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val MODEL = "gpt-4o-mini"

private const val SYSTEM_PROMPT = """
You are a notification triage assistant. The user has enabled a Do Not Disturb
profile and is trying to decide whether a specific notification is important
enough to break through and wake them up, or whether it can safely wait.

Given a profile name and details about a notification (the sending app,
title, message text, and whether the sender is a favorite contact), decide
whether this notification is urgent enough to interrupt the user right now.

Respond with ONLY a JSON object of the form:
{"urgent": <true|false>, "reason": "<short explanation>", "confidence": <0.0-1.0>}
"""

/**
 * Classifies ambiguous notifications using an OpenAI chat completion.
 *
 * Fail-safe: any HTTP error or malformed response is treated as urgent, since
 * silently swallowing a possibly-urgent notification is worse than an
 * unnecessary interruption.
 */
class OpenAiImportanceService(
    private val client: OpenAiClient
) : ImportanceService {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun classify(n: IncomingNotification, profileName: String): Verdict {
        return try {
            val userContent = buildString {
                append("Profile: $profileName\n")
                append("App: ${n.appLabel} (${n.packageName})\n")
                append("Title: ${n.title}\n")
                append("Text: ${n.text}\n")
                append("Sender: ${n.senderKey}\n")
                append("Favorite contact: ${n.isFavoriteContact}\n")
                n.emailFromDomain?.let { append("Email domain: $it\n") }
            }

            val content = client.chat(
                model = MODEL,
                systemPrompt = SYSTEM_PROMPT,
                userContent = userContent,
                jsonMode = true
            )

            val obj = json.parseToJsonElement(content).jsonObject
            Verdict(
                urgent = obj["urgent"]!!.jsonPrimitive.boolean,
                reason = obj["reason"]!!.jsonPrimitive.content,
                confidence = obj["confidence"]!!.jsonPrimitive.double
            )
        } catch (e: Exception) {
            Verdict(
                urgent = true,
                reason = "AI unavailable — erring toward waking you",
                confidence = 0.0
            )
        }
    }
}
