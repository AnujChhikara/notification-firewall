package com.anuj.notificationfirewall.domain.model

data class Verdict(
    val urgent: Boolean,
    val reason: String,
    val confidence: Double
)
