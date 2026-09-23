package com.anuj.notificationfirewall.domain.wall

import android.util.Log
import com.anuj.notificationfirewall.data.prefs.WallSettings
import com.anuj.notificationfirewall.domain.model.IncomingNotification
import kotlinx.coroutines.CancellationException
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
 *    model judgement, heuristic or otherwise.
 * 3. **Call** — a live huddle/call invite expires within minutes, so it rings
 *    without consulting Jev. Deliberately below block/VIP (an instruction
 *    beats a heuristic) and above cache (a live invite must never be answered
 *    from a stale verdict).
 * 4. **Cache** — a verdict already earned for this exact content shape.
 * 5. **Jev** — the only step that touches the network.
 *
 * The final buzz bar is cost-sensitive, not global: a 1:1 personal question
 * ([DmDetector]) rings at [PERSONAL_QUESTION_BAR] instead of the user's
 * threshold, because a missed direct question costs more than a missed
 * promo. Everything else keeps the global bar.
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

        if (CallDetector.isLiveCallInvite(n.packageName, n.title, n.text)) {
            return WallDecision(WallBucket.RING, WallDecisionSource.CALL, null, 0f, shape, false)
        }

        cache.get(n.packageName, sender, shape)?.let { cached ->
            return decided(cached, WallDecisionSource.CACHE, appliedBias, shape, n)
        }

        val state = JevState(
            app = n.appLabel,
            channel = channelId,
            title = n.title,
            text = n.text,
            arrivedAtLocal = n.postedAt.atZone(zone).format(HOUR_MINUTE),
            isReplyCapable = isReplyCapable,
            isFromContact = n.isFavoriteContact,
        )

        val verdict = try {
            jev.classify(state)
        } catch (e: CancellationException) {
            // Structured concurrency: a cancelled coroutine must keep propagating
            // cancellation, never be reinterpreted as a Jev failure. Do not fold
            // this into the clause below.
            throw e
        } catch (e: Exception) {
            // Any other failure of the classify call — offline, throttled, down,
            // malformed, or anything else a JevApi implementation can throw —
            // degrades to silence-and-store. Nothing is lost: the notification
            // is stored and flagged, and the re-classification worker picks it
            // up when the network returns. This guarantee is structural to the
            // JevApi type, not tied to JevException: the try wraps only the
            // jev.classify(state) call, with `state` built beforehand, so this
            // clause is the single, unconditional gate on the only network hop
            // in the pipeline.
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
        return decided(verdict, WallDecisionSource.JEV, appliedBias, shape, n)
    }

    companion object {
        /**
         * Buzz bar for 1:1 personal questions. Calibrated against a 65-row
         * hand-labelled export sample (Sept 2026): every genuine personal
         * miss scored 2.68–3.44 while correctly-silenced 1:1 chatter with no
         * question signal sits anywhere, so the [DmDetector] shape gate does
         * the real work and this bar just needs to clear the misses without
         * reaching down into reaction/GIF territory.
         */
        const val PERSONAL_QUESTION_BAR = 2.5f
    }

    private fun decided(
        verdict: JevVerdict,
        source: WallDecisionSource,
        appliedBias: Float,
        shape: String,
        n: IncomingNotification,
    ): WallDecision {
        val biased = verdict.importance + appliedBias
        val personalQuestion = verdict.category == NotificationCategory.PERSONAL_MESSAGE &&
            DmDetector.isPersonalQuestion(n.title, n.text)
        val bar = if (personalQuestion) PERSONAL_QUESTION_BAR else settings.threshold
        return WallDecision(
            bucket = if (biased >= bar) WallBucket.RING else WallBucket.SILENCE,
            source = source,
            verdict = verdict,
            biasApplied = appliedBias,
            contentShape = shape,
            pendingClassification = false,
        )
    }
}
