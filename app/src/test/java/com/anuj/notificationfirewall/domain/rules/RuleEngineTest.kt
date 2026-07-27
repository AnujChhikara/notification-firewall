// domain/rules/RuleEngineTest.kt
package com.anuj.notificationfirewall.domain.rules
import com.anuj.notificationfirewall.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RuleEngineTest {
    private val engine = RuleEngine()
    private fun n(pkg: String = "com.whatsapp", text: String = "hi", fav: Boolean = false) =
        IncomingNotification(pkg, "App", "t", text, "s", fav, null, Instant.EPOCH)

    @Test fun first_matching_rule_by_order_wins() {
        val rules = listOf(
            Rule(2, 2, listOf(Condition.AppIs(setOf("com.whatsapp"))), BucketAction.SILENCE),
            Rule(1, 1, listOf(Condition.AppIs(setOf("com.whatsapp")), Condition.IsFavoriteContact), BucketAction.LET_THROUGH_CUSTOM_SOUND),
        )
        val d = engine.evaluate(n(fav = true), rules)
        assertTrue(d is RuleDecision.Matched && d.rule.id == 1L)
    }
    @Test fun all_conditions_must_match() {
        val rules = listOf(
            Rule(1, 1, listOf(Condition.AppIs(setOf("com.whatsapp")), Condition.IsFavoriteContact), BucketAction.CAPTURE)
        )
        assertTrue(engine.evaluate(n(fav = false), rules) is RuleDecision.Ambiguous)
    }
    @Test fun no_rules_is_ambiguous() {
        assertTrue(engine.evaluate(n(), emptyList()) is RuleDecision.Ambiguous)
    }
}
