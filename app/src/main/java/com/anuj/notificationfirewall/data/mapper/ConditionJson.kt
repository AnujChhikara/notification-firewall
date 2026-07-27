// data/mapper/ConditionJson.kt
package com.anuj.notificationfirewall.data.mapper

import com.anuj.notificationfirewall.domain.model.Condition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Manual JSON (de)serialization for the [Condition] sealed interface, used to persist
 * [com.anuj.notificationfirewall.domain.rules.Rule.conditions] as a single JSON string column
 * (see [com.anuj.notificationfirewall.data.db.RuleEntity.conditionsJson]).
 *
 * Each condition is encoded as a JSON object with a "type" discriminator matching the
 * Condition subtype's simple name.
 */
object ConditionJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(conditions: List<Condition>): String {
        val array = buildJsonArray {
            conditions.forEach { add(encodeOne(it)) }
        }
        return array.toString()
    }

    fun decode(jsonString: String): List<Condition> {
        val array = json.parseToJsonElement(jsonString).jsonArray
        return array.map { decodeOne(it.jsonObject) }
    }

    private fun encodeOne(condition: Condition): JsonObject = buildJsonObject {
        when (condition) {
            is Condition.AppIs -> {
                put("type", "AppIs")
                putJsonArray("packages") { condition.packages.forEach { add(it) } }
            }
            is Condition.TitleContains -> {
                put("type", "TitleContains")
                put("text", condition.text)
            }
            is Condition.BodyContainsAny -> {
                put("type", "BodyContainsAny")
                putJsonArray("keywords") { condition.keywords.forEach { add(it) } }
            }
            is Condition.IsFavoriteContact -> {
                put("type", "IsFavoriteContact")
            }
            is Condition.EmailFromDomain -> {
                put("type", "EmailFromDomain")
                put("domain", condition.domain)
                put("shouldMatch", condition.shouldMatch)
            }
        }
    }

    private fun decodeOne(obj: JsonObject): Condition {
        return when (val type = obj.getValue("type").jsonPrimitive.content) {
            "AppIs" -> Condition.AppIs(
                obj.getValue("packages").jsonArray.map { it.jsonPrimitive.content }.toSet()
            )
            "TitleContains" -> Condition.TitleContains(
                obj.getValue("text").jsonPrimitive.content
            )
            "BodyContainsAny" -> Condition.BodyContainsAny(
                obj.getValue("keywords").jsonArray.map { it.jsonPrimitive.content }
            )
            "IsFavoriteContact" -> Condition.IsFavoriteContact
            "EmailFromDomain" -> Condition.EmailFromDomain(
                domain = obj.getValue("domain").jsonPrimitive.content,
                shouldMatch = obj.getValue("shouldMatch").jsonPrimitive.boolean,
            )
            else -> throw IllegalArgumentException("Unknown Condition type: $type")
        }
    }
}
