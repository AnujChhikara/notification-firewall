package com.anuj.notificationfirewall.domain.assessment

object AssessmentScorer {

    fun score(answers: AssessmentAnswers): ProblemProfile = ProblemProfile(
        pattern = answers.pattern ?: ScrollPattern.CHECKING,
        worstWindow = answers.worstWindow ?: WorstWindow.ALL_DAY,
        goal = answers.goal ?: Goal.LESS_NOISE,
        cost = answers.cost ?: Cost.FOCUS,
        protectedApps = answers.protectedApps,
        selfReportedOpensPerDay =
            (answers.opensPerDayBucket ?: OpensBucket.SOME).midpoint,
        sleepStartMinute = answers.sleepStartMinute,
        sleepEndMinute = answers.sleepEndMinute,
        readiness = (answers.readiness ?: 5).coerceIn(1, 10),
    )

    fun diagnosisHeadline(p: ProblemProfile): String {
        val when1: String = when (p.worstWindow) {
            WorstWindow.MORNING -> "morning"
            WorstWindow.NIGHT -> "late-night"
            WorstWindow.WORK -> "work-hours"
            WorstWindow.ALL_DAY -> "all-day"
        }
        return when (p.pattern) {
            ScrollPattern.REELS ->
                if (p.worstWindow == WorstWindow.MORNING) "You're caught in the $when1 scroll"
                else "You're caught in the $when1 scroll loop"
            ScrollPattern.NEWS -> "You're caught in the ${doomWord(p)} doomscroll"
            ScrollPattern.YOUTUBE -> "You're caught in the $when1 autoplay spiral"
            ScrollPattern.CHECKING -> "You're caught in the $when1 checking habit"
        }
    }

    fun diagnosisSupport(p: ProblemProfile): String = when (p.goal) {
        Goal.CALM_MORNINGS -> "A calm first hour sets the tone for the whole day — that's what we'll protect first."
        Goal.REAL_SLEEP -> "Sleep is what your attention runs on. We'll guard your wind-down like it matters — because it does."
        Goal.FOCUSED_WORK -> "Real focus needs long, uninterrupted blocks. We'll clear the space; you bring the work."
        Goal.LESS_NOISE -> "Less noise, more signal. We'll quiet everything that isn't yours to carry."
    }

    private fun doomWord(p: ProblemProfile) =
        if (p.worstWindow == WorstWindow.NIGHT) "late-night" else "endless"
}
