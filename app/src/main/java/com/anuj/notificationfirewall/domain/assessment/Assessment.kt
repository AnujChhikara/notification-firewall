package com.anuj.notificationfirewall.domain.assessment

data class ProtectedApp(val packageName: String, val label: String)

enum class ScrollPattern { REELS, NEWS, YOUTUBE, CHECKING }

enum class WorstWindow { MORNING, NIGHT, WORK, ALL_DAY }

enum class Goal { CALM_MORNINGS, REAL_SLEEP, FOCUSED_WORK, LESS_NOISE }

enum class Cost { FOCUS, SLEEP, MOOD, PEOPLE }

enum class OpensBucket(val midpoint: Int) {
    FEW(7),
    SOME(20),
    MANY(55),
    CONSTANT(120),
}

data class AssessmentAnswers(
    val pattern: ScrollPattern? = null,
    val worstWindow: WorstWindow? = null,
    val goal: Goal? = null,
    val cost: Cost? = null,
    val protectedApps: List<ProtectedApp> = emptyList(),
    val opensPerDayBucket: OpensBucket? = null,
    val sleepStartMinute: Int? = null,
    val sleepEndMinute: Int? = null,
    val readiness: Int? = null,
)

data class ProblemProfile(
    val pattern: ScrollPattern,
    val worstWindow: WorstWindow,
    val goal: Goal,
    val cost: Cost,
    val protectedApps: List<ProtectedApp>,
    val selfReportedOpensPerDay: Int,
    val sleepStartMinute: Int?,
    val sleepEndMinute: Int?,
    val readiness: Int,
)
