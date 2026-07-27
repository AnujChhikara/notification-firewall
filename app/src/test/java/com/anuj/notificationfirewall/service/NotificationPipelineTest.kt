// service/NotificationPipelineTest.kt
package com.anuj.notificationfirewall.service
import com.anuj.notificationfirewall.ai.ImportanceService
import com.anuj.notificationfirewall.domain.model.*
import com.anuj.notificationfirewall.domain.profile.*
import com.anuj.notificationfirewall.domain.rules.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class NotificationPipelineTest {
    private fun n(fav: Boolean = false) =
        IncomingNotification("com.whatsapp", "WhatsApp", "t", "hi", "s", fav, null, Instant.EPOCH)
    private val sleep = ActiveProfile(1, "Sleep", 0, true, BucketAction.ASK_AI,
        22 * 60, 7 * 60, DayOfWeek.values().toSet(), true)
    private val night = ZonedDateTime.of(2026, 7, 27, 23, 0, 0, 0, ZoneId.of("UTC"))
    private val noon = ZonedDateTime.of(2026, 7, 27, 12, 0, 0, 0, ZoneId.of("UTC"))
    private fun pipeline(urgent: Boolean) = NotificationPipeline(
        ProfileManager(), RuleEngine(),
        object : ImportanceService {
            override suspend fun classify(n: IncomingNotification, profileName: String) =
                Verdict(urgent, "r", 0.9)
        })

    @Test fun no_active_profile_passes_through() = runBlocking {
        val r = pipeline(false).decide(n(), listOf(sleep), { emptyList() }, noon)
        assertEquals(BucketAction.LET_THROUGH_AS_IS, r.bucket)
        assertEquals(DecisionSource.PASS_THROUGH, r.source)
    }
    @Test fun matched_rule_uses_its_action() = runBlocking {
        val rule = Rule(1, 1, listOf(Condition.IsFavoriteContact), BucketAction.LET_THROUGH_CUSTOM_SOUND)
        val r = pipeline(false).decide(n(fav = true), listOf(sleep), { listOf(rule) }, night)
        assertEquals(BucketAction.LET_THROUGH_CUSTOM_SOUND, r.bucket)
        assertEquals(DecisionSource.RULE, r.source)
    }
    @Test fun ambiguous_with_ask_ai_and_not_urgent_is_silenced() = runBlocking {
        val r = pipeline(urgent = false).decide(n(), listOf(sleep), { emptyList() }, night)
        assertEquals(BucketAction.SILENCE, r.bucket)
        assertEquals(DecisionSource.AI, r.source)
    }
    @Test fun ambiguous_with_ask_ai_and_urgent_passes() = runBlocking {
        val r = pipeline(urgent = true).decide(n(), listOf(sleep), { emptyList() }, night)
        assertEquals(BucketAction.LET_THROUGH_AS_IS, r.bucket)
    }
}
