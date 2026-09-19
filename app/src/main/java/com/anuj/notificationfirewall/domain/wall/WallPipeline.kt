package com.anuj.notificationfirewall.domain.wall

import android.util.Log
import com.anuj.notificationfirewall.data.prefs.WallSettings
import com.anuj.notificationfirewall.domain.model.IncomingNotification
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "WallPipeline"
private val HOUR_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Decides what happens to one notification.
 *
 * Ordering is deliberate and load-bearing:
 *
 * 1. **OTP** first, ahead of even the block list — a passcode has an immediate
 *    concrete cost if silenced, and this path needs no network.
 * 2. **Block**, then **VIP** — the user's explicit instructions outrank any
 *    model judgement.
 * 3. **Cache** — a verdict already earned for this exact content shape.
 * 4. **Jev** — the only step that touches the network.
 *
 * Every step before Jev is local and instant, so the common case (a sender the
 * wall already recognises) costs nothing and works on a plane.
 *
 * Do not reorder these steps. `otpRingsEvenWhenTheSenderIsBlocked` in
 * [WallPipelineTest] exists specifically to catch someone "tidying" the block
 * check above the OTP check.
 */
class WallPipeline(
    private val overrides: OverrideStore,
    private val cache: VerdictCache,
    private val bias: BiasStore,
    private val jev: JevApi,
    private val settings: WallSettings,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    suspend fun decide(
        n: IncomingNotification,
        channelId: String?,
        isReplyCapable: Boolean,
    ): WallDecision {
        val shape = ContentShape.of(n.title, n.text)
        val sender = n.senderKey.ifBlank { null }

        if (settings.otpFastPathEnabled && OtpDetector.isOtp(n.title, n.text)) {
            return WallDecision(WallBucket.RING, WallDecisionSource.OTP, null, 0f, shape, false)
        }

        when (overrides.kindFor(n.packageName, sender)) {
            OverrideKind.BLOCK ->
                return WallDecision(WallBucket.DROP, WallDecisionSource.BLOCK, null, 0f, shape, false)
            OverrideKind.VIP ->
                return WallDecision(WallBucket.RING, WallDecisionSource.VIP, null, 0f, shape, false)
            null -> Unit
        }

        val appliedBias = bias.biasFor(n.packageName, sender)

        cache.get(shape)?.let { cached ->
            return decided(cached, WallDecisionSource.CACHE, appliedBias, shape)
        }

        val verdict = try {
            jev.classify(
                JevState(
                    app = n.appLabel,
                    channel = channelId,
                    title = n.title,
                    text = n.text,
                    arrivedAtLocal = n.postedAt.atZone(zone).format(HOUR_MINUTE),
                    isReplyCapable = isReplyCapable,
                    isFromContact = n.isFavoriteContact,
                ),
            )
        } catch (e: JevException) {
            // Offline, throttled, down, or malformed. Nothing is lost: the
            // notification is stored and flagged, and the re-classification
            // worker picks it up when the network returns.
            Log.w(TAG, "Jev unavailable for ${n.packageName}; silencing pending re-classification", e)
            return WallDecision(
                bucket = WallBucket.SILENCE,
                source = WallDecisionSource.PENDING,
                verdict = null,
                biasApplied = appliedBias,
                contentShape = shape,
                pendingClassification = true,
            )
        }

        cache.put(shape, n.packageName, sender, verdict)
        return decided(verdict, WallDecisionSource.JEV, appliedBias, shape)
    }

    private fun decided(
        verdict: JevVerdict,
        source: WallDecisionSource,
        appliedBias: Float,
        shape: String,
    ): WallDecision {
        val biased = verdict.importance + appliedBias
        return WallDecision(
            bucket = if (biased >= settings.threshold) WallBucket.RING else WallBucket.SILENCE,
            source = source,
            verdict = verdict,
            biasApplied = appliedBias,
            contentShape = shape,
            pendingClassification = false,
        )
    }
}
