// domain/rules/RuleEngine.kt
package com.anuj.notificationfirewall.domain.rules
import com.anuj.notificationfirewall.domain.model.*

data class Rule(val id: Long, val order: Int, val conditions: List<Condition>, val action: BucketAction)
sealed interface RuleDecision {
    data class Matched(val rule: Rule) : RuleDecision
    data object Ambiguous : RuleDecision
}
class RuleEngine {
    fun evaluate(n: IncomingNotification, rules: List<Rule>): RuleDecision {
        val match = rules.sortedBy { it.order }
            .firstOrNull { rule -> rule.conditions.all { it.matches(n) } }
        return if (match != null) RuleDecision.Matched(match) else RuleDecision.Ambiguous
    }
}
