package com.anuj.notificationfirewall.domain.wall

/**
 * Shape test for a 1:1 personal message that asks something of the user.
 *
 * Calibrated against a 65-row hand-labelled export sample (Sept 2026): every
 * genuine miss was a 1:1 DM containing a question or request ("Can you come
 * to gurgaon?", "Busy ho aap abhi?", "Bhai voice waala kaise hoga abhi?"),
 * while group rows mentioning third parties ("is this fine? @Kanhaiya") were
 * correctly silent. The two gates below encode exactly that split:
 *
 * - [isOneToOne]: WhatsApp-style group titles always carry a sender suffix
 *   after a colon ("Flatties #2: Asutosh", "AI-QMS (4 messages): Aashish",
 *   "V-AI x NeuroDrift: ~ Divyam"), a member count, or a '#'.
 * - question vocabulary: '?' plus a small tested Hindi/English request list.
 *   Deliberately excludes "check" and "need": the sample has 1:1 rows
 *   ("Check karta hu", "Damn, I will need that") where those words are
 *   statements of the sender's own intent, and a false positive here costs
 *   a loud buzz, not a quiet card.
 *
 * "You" (the user's own outgoing echo) is excluded: it is 1:1-shaped and
 * can contain '?', and buzzing for your own sent message is pure noise.
 */
object DmDetector {

    private val GROUP_COUNT = Regex("""\(\d+\s+messages?\)""", RegexOption.IGNORE_CASE)

    fun isOneToOne(title: String): Boolean {
        val t = title.trim()
        if (t.isEmpty() || t.equals("You", ignoreCase = true)) return false
        if (':' in t) return false
        if ('#' in t) return false
        if (GROUP_COUNT.containsMatchIn(t)) return false
        return true
    }

    private val QUESTION = Regex(
        """\?|\b(can you|could you|please|call|come|busy ho|bata\w*|karn\w*|karo|puch\w*|confirm|\bkya\b)""",
        RegexOption.IGNORE_CASE,
    )

    fun isPersonalQuestion(title: String, text: String): Boolean =
        isOneToOne(title) && QUESTION.containsMatchIn(text)
}
