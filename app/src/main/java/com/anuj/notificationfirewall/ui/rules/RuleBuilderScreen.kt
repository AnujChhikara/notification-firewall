// ui/rules/RuleBuilderScreen.kt
package com.anuj.notificationfirewall.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.anuj.notificationfirewall.ui.NfButton
import com.anuj.notificationfirewall.ui.NfCard
import com.anuj.notificationfirewall.ui.NfChip
import com.anuj.notificationfirewall.ui.NfScreen
import com.anuj.notificationfirewall.ui.SectionLabel
import com.anuj.notificationfirewall.ui.bucketColor
import com.anuj.notificationfirewall.ui.bucketLabel
import com.anuj.notificationfirewall.ui.StatusDot
import com.anuj.notificationfirewall.ui.theme.NfAccent
import com.anuj.notificationfirewall.ui.theme.NfBorder
import com.anuj.notificationfirewall.ui.theme.NfDanger
import com.anuj.notificationfirewall.ui.theme.NfDangerSurface
import com.anuj.notificationfirewall.ui.theme.NfSurfaceElevated
import com.anuj.notificationfirewall.ui.theme.NfText
import com.anuj.notificationfirewall.ui.theme.NfTextFaint
import com.anuj.notificationfirewall.ui.theme.NfTextMuted
import com.anuj.notificationfirewall.ui.theme.NfTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

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
                    profileId = profileId, order = order,
                    conditionsJson = ConditionJson.encode(conditions),
                    action = action, soundConfigJson = null,
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

    var conditions by remember { mutableStateOf<List<Condition>>(emptyList()) }
    var action by remember { mutableStateOf(BucketAction.CAPTURE) }
    var condType by remember { mutableStateOf(CondType.APP) }
    var textA by remember { mutableStateOf("") }
    var emailMatches by remember { mutableStateOf(true) }

    NfScreen(eyebrow = "Top-down, first match wins", title = "Rules", onBack = { nav.popBackStack() }) { modifier ->
        Column(
            modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel("Current rules")
            if (rules.isEmpty()) {
                Text("None yet.", style = MaterialTheme.typography.bodyMedium, color = NfTextMuted, modifier = Modifier.padding(8.dp))
            }
            rules.forEach { rule ->
                NfCard {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusDot(bucketColor(rule.action), size = 8.dp)
                            Text(bucketLabel(rule.action), style = MaterialTheme.typography.titleMedium, color = NfTitle)
                        }
                        runCatching { ConditionJson.decode(rule.conditionsJson) }.getOrDefault(emptyList())
                            .forEach { Text("• ${summarize(it)}", style = MaterialTheme.typography.bodyMedium, color = NfTextMuted) }
                        NfButton("Delete", primary = false, onClick = { vm.delete(rule) })
                    }
                }
            }

            SectionLabel("Add a rule")
            if (conditions.isNotEmpty()) {
                NfCard {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Conditions (all must match)", style = MaterialTheme.typography.labelSmall, color = NfTextFaint)
                        conditions.forEach { Text("• ${summarize(it)}", style = MaterialTheme.typography.bodyMedium, color = NfText) }
                    }
                }
            }

            Text("Condition type", style = MaterialTheme.typography.titleMedium, color = NfText, modifier = Modifier.padding(top = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CondType.values().forEach { t -> NfChip(condLabel(t), condType == t, { condType = t; textA = "" }) }
            }

            when (condType) {
                CondType.APP -> Field("Package(s), comma-separated", textA) { textA = it }
                CondType.TITLE -> Field("Title contains", textA) { textA = it }
                CondType.BODY -> Field("Body keyword(s), comma-separated", textA) { textA = it }
                CondType.FAVORITE -> Text("Matches favorite (starred) contacts. No input.", style = MaterialTheme.typography.bodyMedium, color = NfTextMuted)
                CondType.EMAIL -> {
                    Field("Domain (e.g. mycompany.com)", textA) { textA = it }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Matches this domain", color = NfTextMuted, style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = emailMatches, onCheckedChange = { emailMatches = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = NfTitle, checkedTrackColor = NfAccent,
                                uncheckedThumbColor = NfTextMuted, uncheckedTrackColor = NfSurfaceElevated, uncheckedBorderColor = NfBorder,
                            ),
                        )
                    }
                }
            }
            NfButton("Add condition", primary = false, onClick = {
                buildCondition(condType, textA, emailMatches)?.let { conditions = conditions + it }
                textA = ""
            })

            Text("Action", style = MaterialTheme.typography.titleMedium, color = NfText, modifier = Modifier.padding(top = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BucketAction.values().filter { it != BucketAction.ASK_AI }.forEach { a ->
                    NfChip(bucketLabel(a), action == a, { action = a })
                }
            }

            if (action == BucketAction.SILENCE || action == BucketAction.LET_THROUGH_CUSTOM_SOUND) {
                Column(
                    Modifier.fillMaxWidth()
                        .padding(top = 2.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .background(NfDangerSurface)
                        .padding(14.dp),
                ) {
                    Text(CONSENT_WARNING, style = MaterialTheme.typography.bodyMedium, color = NfDanger)
                }
            }

            NfButton(
                "Save rule",
                enabled = conditions.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                onClick = {
                    vm.addRule(profileId, conditions, action)
                    conditions = emptyList()
                    action = BucketAction.CAPTURE
                },
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = NfAccent, unfocusedIndicatorColor = NfBorder,
            focusedTextColor = NfTitle, unfocusedTextColor = NfText, cursorColor = NfAccent,
            focusedLabelColor = NfTextMuted, unfocusedLabelColor = NfTextFaint,
        ),
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
    is Condition.EmailFromDomain -> (if (c.shouldMatch) "Email from " else "Email NOT from ") + c.domain
}
