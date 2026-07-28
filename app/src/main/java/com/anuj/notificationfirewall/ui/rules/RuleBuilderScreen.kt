// ui/rules/RuleBuilderScreen.kt
package com.anuj.notificationfirewall.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.anuj.notificationfirewall.data.db.RuleEntity
import com.anuj.notificationfirewall.data.db.dao.RuleDao
import com.anuj.notificationfirewall.data.mapper.ConditionJson
import com.anuj.notificationfirewall.domain.model.BucketAction
import com.anuj.notificationfirewall.domain.model.Condition
import com.anuj.notificationfirewall.ui.NfScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import javax.inject.Inject

// Verbatim consent copy from design §8 — required whenever a rule silences or
// swaps the sound of a notification (both lose the app's native actions).
private const val CONSENT_WARNING =
    "Turning this on lets us set a custom sound / silence this notification — but " +
        "you'll lose that app's quick-reply and action buttons on these notifications. " +
        "Leave it off to keep native reply."

@HiltViewModel
class RuleBuilderViewModel @Inject constructor(
    private val ruleDao: RuleDao,
) : ViewModel() {

    fun rulesFlow(profileId: Long): Flow<List<RuleEntity>> = ruleDao.observeRulesForProfile(profileId)

    fun addRule(profileId: Long, conditions: List<Condition>, action: BucketAction) {
        viewModelScope.launch {
            val order = ruleDao.nextOrder(profileId)
            ruleDao.upsert(
                RuleEntity(
                    profileId = profileId,
                    order = order,
                    conditionsJson = ConditionJson.encode(conditions),
                    action = action,
                    soundConfigJson = null,
                ),
            )
        }
    }

    fun delete(rule: RuleEntity) {
        viewModelScope.launch { ruleDao.delete(rule) }
    }
}

private enum class CondType { APP, TITLE, BODY, FAVORITE, EMAIL }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuleBuilderScreen(
    nav: NavHostController,
    profileId: Long,
    vm: RuleBuilderViewModel = hiltViewModel(),
) {
    val rules by vm.rulesFlow(profileId).collectAsState(initial = emptyList())

    // In-progress rule being built.
    var conditions by remember { mutableStateOf<List<Condition>>(emptyList()) }
    var action by remember { mutableStateOf(BucketAction.CAPTURE) }

    // Condition sub-form state.
    var condType by remember { mutableStateOf(CondType.APP) }
    var textA by remember { mutableStateOf("") }
    var emailMatches by remember { mutableStateOf(true) }

    NfScreen(title = "Rules", onBack = { nav.popBackStack() }) { modifier ->
        Column(
            modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Existing rules", style = MaterialTheme.typography.titleMedium)
            if (rules.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodyMedium)
            rules.forEach { rule ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("→ ${rule.action}", style = MaterialTheme.typography.labelLarge)
                        runCatching { ConditionJson.decode(rule.conditionsJson) }
                            .getOrDefault(emptyList())
                            .forEach { Text("• ${summarize(it)}", style = MaterialTheme.typography.bodySmall) }
                        OutlinedButton(onClick = { vm.delete(rule) }) { Text("Delete") }
                    }
                }
            }

            Text("Add a rule", style = MaterialTheme.typography.titleMedium)

            // Conditions accumulated for the new rule.
            if (conditions.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Conditions (all must match):", style = MaterialTheme.typography.labelLarge)
                        conditions.forEach { Text("• ${summarize(it)}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            Text("Condition type", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CondType.values().forEach { t ->
                    FilterChip(
                        selected = condType == t,
                        onClick = { condType = t; textA = "" },
                        label = { Text(condLabel(t)) },
                    )
                }
            }

            when (condType) {
                CondType.APP -> Field("Package(s), comma-separated (e.g. com.whatsapp)", textA) { textA = it }
                CondType.TITLE -> Field("Title contains", textA) { textA = it }
                CondType.BODY -> Field("Body keyword(s), comma-separated", textA) { textA = it }
                CondType.FAVORITE -> Text("No input — matches favorite (starred) contacts.", style = MaterialTheme.typography.bodySmall)
                CondType.EMAIL -> {
                    Field("Domain (e.g. mycompany.com)", textA) { textA = it }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Matches this domain")
                        Switch(checked = emailMatches, onCheckedChange = { emailMatches = it })
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    buildCondition(condType, textA, emailMatches)?.let { conditions = conditions + it }
                    textA = ""
                },
            ) { Text("Add condition") }

            Text("Action", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BucketAction.values().filter { it != BucketAction.ASK_AI }.forEach { a ->
                    FilterChip(selected = action == a, onClick = { action = a }, label = { Text(a.name) })
                }
            }

            if (action == BucketAction.SILENCE || action == BucketAction.LET_THROUGH_CUSTOM_SOUND) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        CONSENT_WARNING,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Button(
                enabled = conditions.isNotEmpty(),
                onClick = {
                    vm.addRule(profileId, conditions, action)
                    conditions = emptyList()
                    action = BucketAction.CAPTURE
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save rule") }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun condLabel(t: CondType): String = when (t) {
    CondType.APP -> "App"
    CondType.TITLE -> "Title"
    CondType.BODY -> "Body"
    CondType.FAVORITE -> "Favorite"
    CondType.EMAIL -> "Email domain"
}

private fun buildCondition(type: CondType, text: String, emailMatches: Boolean): Condition? {
    val trimmed = text.trim()
    return when (type) {
        CondType.APP -> trimmed.splitCsv().takeIf { it.isNotEmpty() }?.let { Condition.AppIs(it.toSet()) }
        CondType.TITLE -> trimmed.takeIf { it.isNotEmpty() }?.let { Condition.TitleContains(it) }
        CondType.BODY -> trimmed.splitCsv().takeIf { it.isNotEmpty() }?.let { Condition.BodyContainsAny(it) }
        CondType.FAVORITE -> Condition.IsFavoriteContact
        CondType.EMAIL -> trimmed.takeIf { it.isNotEmpty() }?.let { Condition.EmailFromDomain(it, emailMatches) }
    }
}

private fun String.splitCsv(): List<String> =
    split(",").map { it.trim() }.filter { it.isNotEmpty() }

private fun summarize(c: Condition): String = when (c) {
    is Condition.AppIs -> "App is ${c.packages.joinToString()}"
    is Condition.TitleContains -> "Title contains \"${c.text}\""
    is Condition.BodyContainsAny -> "Body contains any of ${c.keywords.joinToString()}"
    Condition.IsFavoriteContact -> "Sender is a favorite contact"
    is Condition.EmailFromDomain ->
        (if (c.shouldMatch) "Email from " else "Email NOT from ") + c.domain
}
