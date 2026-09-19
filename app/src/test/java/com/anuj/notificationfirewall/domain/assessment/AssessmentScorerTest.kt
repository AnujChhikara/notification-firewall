package com.anuj.notificationfirewall.domain.assessment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssessmentScorerTest {

    private val insta = ProtectedApp("com.instagram.android", "Instagram")
    private val tiktok = ProtectedApp("com.zhiliaoapp.musically", "TikTok")

    @Test
    fun full_answers_map_directly() {
        val answers = AssessmentAnswers(
            pattern = ScrollPattern.REELS,
            worstWindow = WorstWindow.MORNING,
            goal = Goal.CALM_MORNINGS,
            cost = Cost.FOCUS,
            protectedApps = listOf(insta, tiktok),
            opensPerDayBucket = OpensBucket.MANY,
            sleepStartMinute = 23 * 60,
            sleepEndMinute = 7 * 60,
            readiness = 8,
        )
        val p = AssessmentScorer.score(answers)
        assertEquals(ScrollPattern.REELS, p.pattern)
        assertEquals(WorstWindow.MORNING, p.worstWindow)
        assertEquals(Goal.CALM_MORNINGS, p.goal)
        assertEquals(listOf(insta, tiktok), p.protectedApps)
        assertEquals(55, p.selfReportedOpensPerDay)
        assertEquals(8, p.readiness)
    }

    @Test
    fun skipped_answers_get_sane_defaults() {
        val p = AssessmentScorer.score(AssessmentAnswers())
        assertEquals(ScrollPattern.CHECKING, p.pattern)
        assertEquals(WorstWindow.ALL_DAY, p.worstWindow)
        assertEquals(Goal.LESS_NOISE, p.goal)
        assertEquals(Cost.FOCUS, p.cost)
        assertEquals(OpensBucket.SOME.midpoint, p.selfReportedOpensPerDay)
        assertEquals(5, p.readiness)
    }

    @Test
    fun readiness_clamped_to_1_10() {
        assertEquals(1, AssessmentScorer.score(AssessmentAnswers(readiness = -3)).readiness)
        assertEquals(10, AssessmentScorer.score(AssessmentAnswers(readiness = 99)).readiness)
    }

    @Test
    fun headline_names_the_pattern_and_window() {
        val base = AssessmentAnswers(pattern = ScrollPattern.REELS, worstWindow = WorstWindow.MORNING)
        assertTrue(
            AssessmentScorer.diagnosisHeadline(AssessmentScorer.score(base))
                .contains("morning scroll"),
        )
        val night = base.copy(worstWindow = WorstWindow.NIGHT)
        assertTrue(
            AssessmentScorer.diagnosisHeadline(AssessmentScorer.score(night))
                .contains("late-night"),
        )
        val doom = base.copy(pattern = ScrollPattern.NEWS, worstWindow = WorstWindow.ALL_DAY)
        assertTrue(
            AssessmentScorer.diagnosisHeadline(AssessmentScorer.score(doom))
                .contains("doomscroll"),
        )
    }

    @Test
    fun support_line_reflects_goal() {
        val p = AssessmentScorer.score(AssessmentAnswers(goal = Goal.REAL_SLEEP))
        assertTrue(AssessmentScorer.diagnosisSupport(p).isNotBlank())
    }
}
