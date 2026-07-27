// service/NotificationPipeline.kt
package com.anuj.notificationfirewall.service

import com.anuj.notificationfirewall.ai.ImportanceService
import com.anuj.notificationfirewall.domain.model.BucketAction
import com.anuj.notificationfirewall.domain.model.DecisionSource
import com.anuj.notificationfirewall.domain.model.IncomingNotification
import com.anuj.notificationfirewall.domain.model.Verdict
import com.anuj.notificationfirewall.domain.profile.ActiveProfile
import com.anuj.notificationfirewall.domain.profile.ProfileManager
import com.anuj.notificationfirewall.domain.rules.Rule
import com.anuj.notificationfirewall.domain.rules.RuleDecision
import com.anuj.notificationfirewall.domain.rules.RuleEngine
import java.time.ZonedDateTime

data class PipelineResult(
    val bucket: BucketAction,
    val source: DecisionSource,
    val verdict: Verdict?,
    val activeProfileId: Long?,
    val matchedRuleId: Long?,
)

class NotificationPipeline(
    private val profileManager: ProfileManager,
    private val ruleEngine: RuleEngine,
    private val importanceService: ImportanceService,
) {
    suspend fun decide(
        n: IncomingNotification,
        profiles: List<ActiveProfile>,
        rulesByProfile: (Long) -> List<Rule>,
        at: ZonedDateTime,
    ): PipelineResult {
        val profile = profileManager.activeProfile(profiles, at)
            ?: return PipelineResult(
                bucket = BucketAction.LET_THROUGH_AS_IS,
                source = DecisionSource.PASS_THROUGH,
                verdict = null,
                activeProfileId = null,
                matchedRuleId = null,
            )

        return when (val decision = ruleEngine.evaluate(n, rulesByProfile(profile.id))) {
            is RuleDecision.Matched -> PipelineResult(
                bucket = decision.rule.action,
                source = DecisionSource.RULE,
                verdict = null,
                activeProfileId = profile.id,
                matchedRuleId = decision.rule.id,
            )
            is RuleDecision.Ambiguous -> {
                if (profile.defaultAction == BucketAction.ASK_AI && profile.aiEnabled) {
                    val verdict = importanceService.classify(n, profile.name)
                    PipelineResult(
                        bucket = if (verdict.urgent) BucketAction.LET_THROUGH_AS_IS else BucketAction.SILENCE,
                        source = DecisionSource.AI,
                        verdict = verdict,
                        activeProfileId = profile.id,
                        matchedRuleId = null,
                    )
                } else {
                    // ASK_AI must never leak as a final bucket. If it is the literal
                    // default but AI is not being consulted (aiEnabled == false),
                    // resolve it to SILENCE.
                    val resolved = if (profile.defaultAction == BucketAction.ASK_AI) {
                        BucketAction.SILENCE
                    } else {
                        profile.defaultAction
                    }
                    PipelineResult(
                        bucket = resolved,
                        source = DecisionSource.DEFAULT,
                        verdict = null,
                        activeProfileId = profile.id,
                        matchedRuleId = null,
                    )
                }
            }
        }
    }
}
