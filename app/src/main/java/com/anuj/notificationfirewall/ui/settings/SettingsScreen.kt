// ui/settings/SettingsScreen.kt
package com.anuj.notificationfirewall.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavHostController
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import com.anuj.notificationfirewall.ui.NfButton
import com.anuj.notificationfirewall.ui.NfCard
import com.anuj.notificationfirewall.ui.NfScreen
import com.anuj.notificationfirewall.ui.SectionLabel
import com.anuj.notificationfirewall.ui.StatusDot
import com.anuj.notificationfirewall.ui.permissions.Permissions
import com.anuj.notificationfirewall.ui.theme.NfAccent
import com.anuj.notificationfirewall.ui.theme.NfBorder
import com.anuj.notificationfirewall.ui.theme.NfRang
import com.anuj.notificationfirewall.ui.theme.NfText
import com.anuj.notificationfirewall.ui.theme.NfTextFaint
import com.anuj.notificationfirewall.ui.theme.NfTextMuted
import com.anuj.notificationfirewall.ui.theme.NfTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val securePrefs: SecurePrefs,
) : ViewModel() {
    val currentKey: String get() = securePrefs.openAiKey.orEmpty()
    fun saveKey(value: String) {
        securePrefs.openAiKey = value.trim().ifBlank { null }
    }
}

@Composable
fun SettingsScreen(nav: NavHostController, vm: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var key by remember { mutableStateOf(vm.currentKey) }
    var saved by remember { mutableStateOf(false) }
    val status = Permissions.status(context, hasApiKey = key.isNotBlank())

    NfScreen(eyebrow = "Configuration", title = "Settings", onBack = { nav.popBackStack() }) { modifier ->
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel("OpenAI")
            NfCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Your key is stored encrypted on-device and used only to triage " +
                            "ambiguous notifications and write the wake-up digest.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NfTextMuted,
                    )
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it; saved = false },
                        label = { Text("API key") },
                        placeholder = { Text("sk-…") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = nfFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NfButton("Save key", onClick = { vm.saveKey(key); saved = true })
                        if (saved) Text("Saved", style = MaterialTheme.typography.labelMedium, color = NfRang)
                    }
                }
            }

            SectionLabel("Permissions")
            NfCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusLine("Notification access", status.notificationAccess)
                    StatusLine("Post notifications", status.postNotifications)
                    StatusLine("Silence access", status.dndAccess)
                    StatusLine("Contacts", status.contacts)
                    StatusLine("Battery exemption", status.batteryExempt)
                    StatusLine("Alarms & reminders", status.exactAlarms)
                    NfButton(
                        "Request battery exemption",
                        primary = false,
                        onClick = { runCatching { context.startActivity(Permissions.batteryExemptionIntent(context)) } },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, granted: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatusDot(if (granted) NfRang else NfTextFaint, size = 7.dp)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (granted) NfText else NfTextMuted)
    }
}

@Composable
private fun nfFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
    unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
    focusedIndicatorColor = NfAccent,
    unfocusedIndicatorColor = NfBorder,
    focusedTextColor = NfTitle,
    unfocusedTextColor = NfText,
    cursorColor = NfAccent,
    focusedLabelColor = NfTextMuted,
    unfocusedLabelColor = NfTextFaint,
)
