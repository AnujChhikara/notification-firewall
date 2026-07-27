// data/mapper/MapperTest.kt
package com.anuj.notificationfirewall.data.mapper

import com.anuj.notificationfirewall.data.db.ProfileEntity
import com.anuj.notificationfirewall.data.db.RuleEntity
import com.anuj.notificationfirewall.domain.model.BucketAction
import com.anuj.notificationfirewall.domain.model.Condition
import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek

class MapperTest {

    @Test fun appIs_round_trips() {
        val conditions = listOf(Condition.AppIs(setOf("com.whatsapp", "com.gmail")))
        assertEquals(conditions, ConditionJson.decode(ConditionJson.encode(conditions)))
    }

    @Test fun titleContains_round_trips() {
        val conditions = listOf(Condition.TitleContains("urgent"))
        assertEquals(conditions, ConditionJson.decode(ConditionJson.encode(conditions)))
    }

    @Test fun bodyContainsAny_round_trips() {
        val conditions = listOf(Condition.BodyContainsAny(listOf("sale", "% off")))
        assertEquals(conditions, ConditionJson.decode(ConditionJson.encode(conditions)))
    }

    @Test fun isFavoriteContact_round_trips() {
        val conditions = listOf(Condition.IsFavoriteContact)
        assertEquals(conditions, ConditionJson.decode(ConditionJson.encode(conditions)))
    }

    @Test fun emailFromDomain_round_trips() {
        val conditions = listOf(Condition.EmailFromDomain("mycompany.com", shouldMatch = false))
        assertEquals(conditions, ConditionJson.decode(ConditionJson.encode(conditions)))
    }

    @Test fun all_five_condition_types_round_trip_together() {
        val conditions = listOf(
            Condition.AppIs(setOf("com.whatsapp")),
            Condition.TitleContains("urgent"),
            Condition.BodyContainsAny(listOf("sale", "% off")),
            Condition.IsFavoriteContact,
            Condition.EmailFromDomain("mycompany.com", shouldMatch = true),
        )
        assertEquals(conditions, ConditionJson.decode(ConditionJson.encode(conditions)))
    }

    @Test fun decode_produces_expected_shape() {
        val json = ConditionJson.encode(listOf(Condition.AppIs(setOf("com.whatsapp"))))
        assertTrue(json.contains("\"type\":\"AppIs\""))
        assertTrue(json.contains("com.whatsapp"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun decode_unknown_type_throws() {
        ConditionJson.decode("""[{"type":"NotARealCondition"}]""")
    }

    @Test fun profileEntity_maps_minutes_and_days() {
        val entity = ProfileEntity(
            id = 5L,
            name = "Work",
            enabled = true,
            startMinuteOfDay = 540,
            endMinuteOfDay = 1020,
            daysOfWeek = setOf(1, 7),
            order = 2,
            aiEnabled = true,
            defaultAction = BucketAction.SILENCE,
        )
        val active = entity.toActiveProfile()

        assertEquals(5L, active.id)
        assertEquals("Work", active.name)
        assertEquals(2, active.order)
        assertTrue(active.aiEnabled)
        assertEquals(BucketAction.SILENCE, active.defaultAction)
        assertEquals(540, active.startMinute)
        assertEquals(1020, active.endMinute)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY), active.days)
        assertTrue(active.enabled)
    }

    @Test fun daysToInts_converts_back() {
        val days = setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY)
        assertEquals(setOf(1, 7), daysToInts(days))
    }

    @Test fun ruleEntity_maps_to_rule_with_decoded_conditions() {
        val conditions = listOf(
            Condition.AppIs(setOf("com.whatsapp")),
            Condition.IsFavoriteContact,
        )
        val entity = RuleEntity(
            id = 10L,
            profileId = 1L,
            order = 3,
            conditionsJson = ConditionJson.encode(conditions),
            action = BucketAction.CAPTURE,
            soundConfigJson = null,
        )
        val rule = entity.toRule()

        assertEquals(10L, rule.id)
        assertEquals(3, rule.order)
        assertEquals(BucketAction.CAPTURE, rule.action)
        assertEquals(conditions, rule.conditions)
    }
}
