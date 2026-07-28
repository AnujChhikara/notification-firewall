// ui/settings/SettingsScreen.kt
package com.anuj.notificationfirewall.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavHostController
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import com.anuj.notificationfirewall.ui.NfScreen
import com.anuj.notificationfirewall.ui.permissions.Permissions
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

    NfScreen(title = "Settings", onBack = { nav.popBackStack() }) { modifier ->
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("OpenAI API key", style = MaterialTheme.typography.titleMedium)
            Text(
                "Stored encrypted on-device. Used only to classify ambiguous " +
                    "notifications and write the wake-up digest.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it; saved = false },
                label = { Text("sk-...") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { vm.saveKey(key); saved = true }) { Text("Save key") }
            if (saved) Text("Saved.", style = MaterialTheme.typography.labelMedium)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Permissions", style = MaterialTheme.typography.titleMedium)
                    StatusLine("Notification access", status.notificationAccess)
                    StatusLine("Post notifications", status.postNotifications)
                    StatusLine("DND override", status.dndAccess)
                    StatusLine("Contacts", status.contacts)
                    StatusLine("Battery exemption", status.batteryExempt)
                    Button(onClick = {
                        runCatching { context.startActivity(Permissions.batteryExemptionIntent(context)) }
                    }) { Text("Battery exemption") }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, granted: Boolean) {
    Text(
        "${if (granted) "✓" else "✗"}  $label",
        style = MaterialTheme.typography.bodyMedium,
    )
}
