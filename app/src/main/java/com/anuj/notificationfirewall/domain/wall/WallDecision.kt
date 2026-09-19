package com.anuj.notificationfirewall.domain.wall

/**
 * The wall's judgement of one notification, and everything needed to explain it
 * back to the user in the Inbox.
 */
data class WallDecision(
    val bucket: WallBucket,
    val source: WallDecisionSource,
    val verdict: JevVerdict?,
    val biasApplied: Float,
    val contentShape: String,
    val pendingClassification: Boolean,
)
