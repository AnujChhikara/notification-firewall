package com.anuj.notificationfirewall.data.db

import androidx.room.Entity

/**
 * A learned nudge for one (app, sender) pair, in importance-scale units.
 *
 * Clamped to ±0.75 so it can never overturn a decisive Jev judgement — it tunes
 * the borderline cases and nothing else.
 */
@Entity(tableName = "sender_bias", primaryKeys = ["packageName", "senderKey"])
data class SenderBiasEntity(
    val packageName: String,
    val senderKey: String,
    val bias: Float,
    val correctionCount: Int,
    val lastCorrectedEpochMs: Long,
)
