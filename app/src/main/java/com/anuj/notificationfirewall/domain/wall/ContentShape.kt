package com.anuj.notificationfirewall.domain.wall

import java.security.MessageDigest

/**
 * A normalized fingerprint of a notification's visible content.
 *
 * Marketing senders post the same template over and over with only the numbers
 * changed. Stripping the variable parts — amounts, percentages, dates, times,
 * URLs, order ids, bare digit runs — collapses a whole campaign onto one key, so
 * a single Jev verdict can answer for all of it.
 *
 * The normalizer is deliberately conservative about *words*: nothing lexical is
 * removed. "Reached home safely" and "Check out this sale" keep every word they
 * have and therefore never collide, which is what stops a real message from a
 * person inheriting a promo's cached verdict.
 */
object ContentShape {

    private val URL = Regex("""https?://\S+|www\.\S+""", RegexOption.IGNORE_CASE)
    private val EMAIL = Regex("""[\w.+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+""")
    private val DATE = Regex("""\b\d{1,4}[/-]\d{1,2}[/-]\d{1,4}\b""")
    private val TIME = Regex("""\b\d{1,2}:\d{2}(?::\d{2})?\s*(?:am|pm)?\b""", RegexOption.IGNORE_CASE)
    private val PERCENT = Regex("""\b\d+(?:\.\d+)?\s*%""")
    private val MONEY = Regex(
        """(?:rs\.?|inr|usd|eur|gbp|[$€£₹])\s*\d[\d,]*(?:\.\d+)?""",
        RegexOption.IGNORE_CASE,
    )
    private val ALNUM_ID = Regex("""\b(?=\w*\d)(?=\w*[a-z])\w{6,}\b""", RegexOption.IGNORE_CASE)
    private val NUMBER = Regex("""\b\d[\d,]*(?:\.\d+)?\b""")
    private val WHITESPACE = Regex("""\s+""")

    fun of(title: String, text: String): String = hash(normalize("$title $text"))

    private fun normalize(raw: String): String =
        raw.lowercase()
            // Order matters: the broadest patterns that contain digits must run
            // before the bare-number rule, or they lose their distinguishing
            // punctuation and collapse into <n> prematurely.
            .replace(URL, " <url> ")
            .replace(EMAIL, " <email> ")
            .replace(MONEY, " <amt> ")
            .replace(PERCENT, " <pct> ")
            .replace(DATE, " <date> ")
            .replace(TIME, " <time> ")
            .replace(ALNUM_ID, " <id> ")
            .replace(NUMBER, " <n> ")
            .replace(WHITESPACE, " ")
            .trim()

    private fun hash(normalized: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(normalized.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
