// ui/onboarding/OnboardingScreen.kt
package com.anuj.notificationfirewall.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import com.anuj.notificationfirewall.ui.NfButton
import com.anuj.notificationfirewall.ui.NfCard
import com.anuj.notificationfirewall.ui.NfScreen
import com.anuj.notificationfirewall.ui.StatusDot
import com.anuj.notificationfirewall.ui.permissions.Permissions
import com.anuj.notificationfirewall.ui.theme.NfRang
import com.anuj.notificationfirewall.ui.theme.NfTextFaint
import com.anuj.notificationfirewall.ui.theme.NfTextMuted
import com.anuj.notificationfirewall.ui.theme.NfTitle

@Composable
fun OnboardingScreen(nav: NavHostController) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val status = remember(refresh) { Permissions.status(context, hasApiKey = false) }

    val contactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh++ }
    val postNotifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refresh++ }

    NfScreen(eyebrow = "Grant access", title = "Setup", onBack = { nav.popBackStack() }) { modifier ->
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Nothing leaves your phone except ambiguous notification text sent to " +
                    "OpenAI, and only when AI triage is on.",
                style = MaterialTheme.typography.bodyMedium,
                color = NfTextMuted,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            Step(
                "Notification access", true,
                "Required. Lets the firewall read and cancel notifications from other apps.",
                status.notificationAccess, "Open notification access",
            ) { runCatching { context.startActivity(Permissions.notificationAccessIntent()) } }

            Step(
                "Post notifications", true,
                "Required on Android 13+. Lets us re-post silenced copies and the digest.",
                status.postNotifications, "Grant",
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    postNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            Step(
                "DND override", false,
                "Needed for auto-DND: lets your ring-through rules sound while everything else stays silent.",
                status.dndAccess, "Open DND access",
            ) { runCatching { context.startActivity(Permissions.dndAccessIntent()) } }

            Step(
                "Contacts", false,
                "Optional. Used to match favorite-contact rules; without it that rule never matches.",
                status.contacts, "Grant",
            ) { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) }

            Step(
                "Battery exemption", false,
                "Recommended. Keeps the listener alive so notifications aren't missed.",
                status.batteryExempt, "Request exemption",
            ) { runCatching { context.startActivity(Permissions.batteryExemptionIntent(context)) } }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Step(
                    "Alarms & reminders", false,
                    "Recommended. Lets profiles start and stop exactly on time and keeps the " +
                        "firewall reliably active during their windows.",
                    status.exactAlarms, "Allow alarms & reminders",
                ) { runCatching { context.startActivity(Permissions.exactAlarmSettingsIntent(context)) } }
            }

            NfCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("OpenAI API key", style = MaterialTheme.typography.titleMedium, color = NfTitle)
                    Text(
                        "Optional, but needed for AI triage and the digest. Add it in Settings.",
                        style = MaterialTheme.typography.bodyMedium, color = NfTextMuted,
                    )
                    NfButton("Open Settings", primary = false, onClick = { nav.navigate(com.anuj.notificationfirewall.ui.Routes.SETTINGS) })
                }
            }

            NfButton("Done", onClick = { nav.popBackStack() }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Step(
    title: String,
    required: Boolean,
    why: String,
    granted: Boolean,
    buttonText: String,
    onClick: () -> Unit,
) {
    NfCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusDot(if (granted) NfRang else NfTextFaint, size = 8.dp)
                Text(title, style = MaterialTheme.typography.titleMedium, color = NfTitle)
                Text(
                    if (required) "Required" else "Optional",
                    style = MaterialTheme.typography.labelSmall,
                    color = NfTextFaint,
                )
            }
            Text(why, style = MaterialTheme.typography.bodyMedium, color = NfTextMuted)
            if (granted) {
                Text("Granted", style = MaterialTheme.typography.labelMedium, color = NfRang)
            } else {
                NfButton(buttonText, primary = false, onClick = onClick)
            }
        }
    }
}
