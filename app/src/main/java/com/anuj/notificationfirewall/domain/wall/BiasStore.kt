package com.anuj.notificationfirewall.domain.wall

import com.anuj.notificationfirewall.data.db.SenderBiasEntity
import com.anuj.notificationfirewall.data.db.dao.SenderBiasDao

/** Which way the user said the wall got it wrong. */
enum class Correction { SHOULD_HAVE_RUNG, SHOULD_HAVE_BEEN_SILENT }

/**
 * Learned per-(app, sender) nudges, in importance-scale units.
 *
 * The clamp is the safety property. Three "should have been silent" swipes drag
 * a marketing sender to the floor of −0.75, which reliably suppresses its
 * routine blasts — but if that same sender ever emits something Jev scores 5.0,
 * the bias cannot pull it under a 4.0 threshold. **Bias tunes; it never
 * overrides.** Overriding is what [OverrideStore] is for, and that only ever
 * happens because the user asked for it explicitly.
 *
 * Scoping is per sender, never per app, because one messaging app carries both
 * a family conversation and forwarded promotional junk.
 */
class BiasStore(
    private val dao: SenderBiasDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun biasFor(pkg: String, sender: String?): Float {
        if (sender.isNullOrBlank()) return 0f
        return dao.find(pkg, sender)?.bias ?: 0f
    }

    /** Applies one correction and returns the resulting clamped bias. */
    suspend fun record(pkg: String, sender: String?, correction: Correction): Float {
        if (sender.isNullOrBlank()) return 0f

        val existing = dao.find(pkg, sender)
        val delta = when (correction) {
            Correction.SHOULD_HAVE_RUNG -> STEP
            Correction.SHOULD_HAVE_BEEN_SILENT -> -STEP
        }
        val updated = ((existing?.bias ?: 0f) + delta).coerceIn(-MAX_BIAS, MAX_BIAS)

        dao.upsert(
            SenderBiasEntity(
                packageName = pkg,
                senderKey = sender,
                bias = updated,
                correctionCount = (existing?.correctionCount ?: 0) + 1,
                lastCorrectedEpochMs = clock(),
            ),
        )
        return updated
    }

    suspend fun clear(pkg: String, sender: String) = dao.clear(pkg, sender)

    /**
     * Overwrites the bias with an exact snapshot value.
     *
     * Undo needs this, not another [record] call: applying the opposite
     * [Correction] is not a true inverse once the sender is sitting at the
     * ±[MAX_BIAS] clamp -- a same-direction correction there is absorbed
     * (a no-op), but "undoing" it by applying the opposite correction would
     * still move the bias a full [STEP] away from the clamp, corrupting a
     * value the correction never actually touched. Restoring the exact
     * pre-correction snapshot is the only correct inverse.
     */
    suspend fun restore(pkg: String, sender: String?, value: Float) {
        if (sender.isNullOrBlank()) return
        val existing = dao.find(pkg, sender)
        dao.upsert(
            SenderBiasEntity(
                packageName = pkg,
                senderKey = sender,
                bias = value.coerceIn(-MAX_BIAS, MAX_BIAS),
                correctionCount = existing?.correctionCount ?: 0,
                lastCorrectedEpochMs = existing?.lastCorrectedEpochMs ?: clock(),
            ),
        )
    }

    companion object {
        const val MAX_BIAS = 0.75f
        const val STEP = 0.25f
    }
}
