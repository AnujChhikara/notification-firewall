// ui/onboarding/OnboardingScreen.kt
package com.anuj.notificationfirewall.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import com.anuj.notificationfirewall.ui.NfScreen
import com.anuj.notificationfirewall.ui.Routes
import com.anuj.notificationfirewall.ui.permissions.Permissions

@Composable
fun OnboardingScreen(nav: NavHostController) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Settings-based grants (notification access, DND, battery) resolve in the
    // system Settings app, so re-check status every time we resume.
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

    NfScreen(title = "Setup", onBack = { nav.popBackStack() }) { modifier ->
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Notification Firewall needs these grants to intercept, silence, " +
                    "and summarize your notifications. Nothing leaves your phone " +
                    "except ambiguous notification text sent to OpenAI (only when AI is on).",
                style = MaterialTheme.typography.bodyMedium,
            )

            Step(
                title = "1. Notification access",
                why = "Required. Lets the firewall read and cancel notifications from " +
                    "other apps. It cannot be granted in-app — we'll open Settings.",
                granted = status.notificationAccess,
                buttonText = "Open notification access",
                onClick = { runCatching { context.startActivity(Permissions.notificationAccessIntent()) } },
            )

            Step(
                title = "2. Post notifications",
                why = "Required (Android 13+). Lets us re-post silenced copies and the " +
                    "wake-up digest.",
                granted = status.postNotifications,
                buttonText = "Grant",
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        postNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )

            Step(
                title = "3. DND override",
                why = "Optional. Lets 'important' rules ring through system Do Not Disturb.",
                granted = status.dndAccess,
                buttonText = "Open DND access",
                onClick = { runCatching { context.startActivity(Permissions.dndAccessIntent()) } },
            )

            Step(
                title = "4. Contacts",
                why = "Optional. Only used to match 'favorite contact' rules. Without it, " +
                    "that condition simply never matches.",
                granted = status.contacts,
                buttonText = "Grant",
                onClick = { contactsLauncher.launch(Manifest.permission.READ_CONTACTS) },
            )

            Step(
                title = "5. Battery exemption",
                why = "Recommended. Keeps the listener alive so notifications aren't missed.",
                granted = status.batteryExempt,
                buttonText = "Request exemption",
                onClick = { runCatching { context.startActivity(Permissions.batteryExemptionIntent(context)) } },
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("6. OpenAI API key", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Optional but needed for AI triage and the digest. Add it in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { nav.navigate(Routes.SETTINGS) }) { Text("Open Settings") }
                }
            }

            Button(onClick = { nav.popBackStack() }, Modifier.fillMaxWidth()) { Text("Done") }
        }
    }
}

@Composable
private fun Step(
    title: String,
    why: String,
    granted: Boolean,
    buttonText: String,
    onClick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${if (granted) "✓ " else ""}$title",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(why, style = MaterialTheme.typography.bodySmall)
            if (!granted) {
                Button(onClick = onClick) { Text(buttonText) }
            } else {
                Text("Granted", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
