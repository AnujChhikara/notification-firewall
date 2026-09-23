package com.anuj.notificationfirewall.domain.wall

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cases are verbatim rows from the Sept 2026 hand-labelled export sample
 * (scores in comments), plus the near-miss negatives that pinned down the
 * vocabulary: "check" and "need" were dropped because real 1:1 rows use
 * them as statements of the sender's own intent.
 */
class DmDetectorTest {

    // ── 1:1 shape ──────────────────────────────────────────────────────

    @Test
    fun plainContactNameIsOneToOne() {
        assertTrue(DmDetector.isOneToOne("Vinayak Neurodrift"))
        assertTrue(DmDetector.isOneToOne("Anmol Rishi Neuro Drift"))
    }

    @Test
    fun groupTitleWithSenderSuffixIsNotOneToOne() {
        // "V-AI x NeuroDrift: ~ Divyam Nigam" (3.51): no count marker, no '#',
        // still a group -- the colon + sender suffix is the tell.
        assertFalse(DmDetector.isOneToOne("V-AI x NeuroDrift: ~ Divyam Nigam"))
        assertFalse(DmDetector.isOneToOne("Flatties #2: Asutosh Rout Jumbo"))
        assertFalse(DmDetector.isOneToOne("AI-QMS (4 messages): Aashish Neurodrift"))
        assertFalse(DmDetector.isOneToOne("Flatties #2 (2 messages): Kaushik Jumbo"))
    }

    @Test
    fun ownOutgoingEchoIsNotOneToOne() {
        // Title "You" (3.01, "Now ? Like it will take one hour"): 1:1-shaped
        // with a '?', but buzzing for your own sent message is pure noise.
        assertFalse(DmDetector.isOneToOne("You"))
    }

    // ── misses that must now buzz ──────────────────────────────────────

    @Test
    fun directQuestionsFromSampleArePersonalQuestions() {
        assertTrue(DmDetector.isPersonalQuestion("Anmol Rishi Neuro Drift", "Can you come to gurgaon?")) // 3.44
        assertTrue(DmDetector.isPersonalQuestion("Anmol Rishi Neuro Drift", "Or go to a nearby cafe or something?")) // 2.83
        assertTrue(DmDetector.isPersonalQuestion("Ayush Neurodrift", "Busy ho aap abhi?")) // 3.05
        assertTrue(
            DmDetector.isPersonalQuestion(
                "Ayush Neurodrift",
                "Okay, cool Then later, socha call karne ka, kuch kuch puchta regarding jev",
            ), // 3.24
        )
        assertTrue(DmDetector.isPersonalQuestion("Vinayak Neurodrift", "Bhai voice waala kaise hoga abhi?")) // 3.10
        assertTrue(DmDetector.isPersonalQuestion("Vinayak Neurodrift", "Humaare llm models alag h kya iss env me?")) // 2.80
        assertTrue(DmDetector.isPersonalQuestion("Vinayak Neurodrift", "Quick summary waala karna padega but")) // 2.68
    }

    // ── negatives: same sample, must stay silent ───────────────────────

    @Test
    fun statementsOfSenderIntentAreNotQuestions() {
        // "Check" dropped: "I'll check" is the sender's own action (2.50).
        assertFalse(DmDetector.isPersonalQuestion("Ayush Neurodrift", "Check karta hu"))
        // "Need" dropped: sender's own need, nothing asked of you (2.97).
        assertFalse(DmDetector.isPersonalQuestion("Ayush Neurodrift", "Damn, I will need that or build one myself"))
        assertFalse(DmDetector.isPersonalQuestion("Ayush Neurodrift", "It was nothing urgent, I will discuss on Wednesday"))
        assertFalse(DmDetector.isPersonalQuestion("Vinayak Neurodrift", "Hai hi nahi unke paas"))
        assertFalse(DmDetector.isPersonalQuestion("Vinayak Neurodrift", "Ye nafeed ko bhejna h mujhe"))
        assertFalse(DmDetector.isPersonalQuestion("Ayush Neurodrift", "But kabhi aur kar lenge"))
    }

    @Test
    fun reactionsAndAcksAreNotQuestions() {
        assertFalse(DmDetector.isPersonalQuestion("Vinayak Neurodrift", "Sent a GIF"))
        assertFalse(DmDetector.isPersonalQuestion("Anmol Rishi Neuro Drift", "Okk"))
        assertFalse(DmDetector.isPersonalQuestion("Anmol Rishi Neuro Drift", "Perfect"))
        assertFalse(DmDetector.isPersonalQuestion("Ayush Neurodrift", "Hello"))
    }

    @Test
    fun groupQuestionsDoNotGetTheFloor() {
        // "is this fine?" is asked of Kanhaiya, not of you (3.51).
        assertFalse(
            DmDetector.isPersonalQuestion(
                "V-AI x NeuroDrift: ~ Divyam Nigam",
                "done @Kanhaiya Mohan Neurodrift, is this fine?",
            ),
        )
        assertFalse(
            DmDetector.isPersonalQuestion(
                "AI-QMS (4 messages): Aashish Neurodrift",
                "can we check who did this",
            ),
        )
    }

    @Test
    fun contentlessRowsDoNotGetTheFloor() {
        // Truecaller "Sensitive notification content hidden" (2.82): nothing
        // to judge, so no question signal may fire.
        assertFalse(DmDetector.isPersonalQuestion("Truecaller", ""))
        assertFalse(DmDetector.isPersonalQuestion("Truecaller", "Sensitive notification content hidden"))
    }
}
