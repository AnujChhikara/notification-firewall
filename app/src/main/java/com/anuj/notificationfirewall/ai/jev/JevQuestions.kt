package com.anuj.notificationfirewall.ai.jev

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The five questions asked of every notification, in one call.
 *
 * Jev evaluates questions in parallel against the same state, so asking five
 * costs roughly what asking one costs. Only "importance" drives the ring
 * decision; the rest exist so the Inbox can explain itself and so Ask can
 * answer questions about categories and humans-versus-machines later.
 *
 * This object is the single place notification policy is expressed. Changing
 * what the wall considers important is an edit here, under review.
 */
internal object JevQuestions {

    fun build(): JsonObject = buildJsonObject {
        put("importance", buildJsonObject {
            put("type", "score")
            put("instructions", "How much this notification deserves to interrupt the recipient right now")
            put("criteria", buildJsonArray {
                add("Pure noise: marketing, promotional offers, engagement bait, re-engagement nudges")
                add("Low: informational or automated, can wait days without any cost")
                add("Routine: worth reading today, no action needed within the hour")
                add("Matters: the recipient should know about this soon")
                add("Critical: time-critical or personally urgent, interrupt immediately")
            })
        })

        put("category", buildJsonObject {
            put("type", "choice")
            put("instructions", "What kind of notification this is")
            put("criteria", buildJsonObject {
                put("promotion", "Marketing, sales, offers, discounts, app re-engagement nudges")
                put("personal_message", "A message written by a person to the recipient")
                put("transactional", "Account activity: payments, OTPs, bookings, receipts, alerts")
                put("work", "Work tooling: email, chat, tickets, calendar, code review")
                put("social", "Social network activity: likes, follows, mentions, comments")
                put("news", "News, headlines, editorial content, sports scores")
                put("system", "Device or operating-system notices, updates, storage, battery")
                put("delivery", "Order, shipment, or ride status and tracking updates")
                put("other", "Does not fit any category above")
            })
        })

        put("is_time_sensitive", buildJsonObject {
            put("type", "noul")
            put("instructions", "Acting on this later rather than now would lose real value")
            put("criteria", buildJsonObject {
                put("true", "There is a deadline, a window, or something in progress right now")
                put("false", "Equally useful whenever the recipient gets to it")
            })
        })

        put("is_from_human", buildJsonObject {
            put("type", "noul")
            put("instructions", "A specific person wrote this to the recipient, rather than an automated system")
            put("criteria", buildJsonObject {
                put("true", "Written by a person: conversational, addressed to the recipient")
                put("false", "Automated, templated, or broadcast to many recipients")
            })
        })

        put("needs_action", buildJsonObject {
            put("type", "noul")
            put("instructions", "This expects a reply or an action from the recipient")
            put("criteria", buildJsonObject {
                put("true", "A question, request, approval, or task is directed at the recipient")
                put("false", "Purely informational, nothing is expected back")
            })
        })
    }
}
