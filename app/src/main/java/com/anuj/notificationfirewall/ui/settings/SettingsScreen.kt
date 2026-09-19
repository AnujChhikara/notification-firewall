// ui/settings/SettingsScreen.kt
package com.anuj.notificationfirewall.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavHostController
import com.anuj.notificationfirewall.data.prefs.WallSettings
import com.anuj.notificationfirewall.ui.NfCard
import com.anuj.notificationfirewall.ui.NfRow
import com.anuj.notificationfirewall.ui.NfScreen
import com.anuj.notificationfirewall.ui.Routes
import com.anuj.notificationfirewall.ui.SectionLabel
import com.anuj.notificationfirewall.ui.permissions.Permissions
import com.anuj.notificationfirewall.ui.theme.NfDanger
import com.anuj.notificationfirewall.ui.theme.NfRang
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val wallSettings: WallSettings,
) : ViewModel() {
    /** Whether the wall's classifier key is present — drives [Permissions.status]. */
    val hasJevKey: Boolean get() = !wallSettings.jevKey.isNullOrBlank()
}

@Composable
fun SettingsScreen(nav: NavHostController, vm: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current

    // Returning from a system settings screen (e.g. battery exemption) should
    // update these rows without requiring a manual re-open of this screen.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val status = remember(refresh) { Permissions.status(context, hasApiKey = vm.hasJevKey) }

    NfScreen(eyebrow = "Configuration", title = "Settings") { modifier ->
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel("Firewall")
            NfCard {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    NfRow("Wake-up digest", subtitle = "What you missed while away") { nav.navigate(Routes.DIGEST) }
                    NfRow("Permissions", subtitle = "Access & reliability grants") { nav.navigate(Routes.ONBOARDING) }
                }
            }

            SectionLabel("API keys")
            NfCard {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    NfRow(
                        "Jev & OpenAI keys",
                        subtitle = if (vm.hasJevKey) "Jev key set" else "Jev key missing — the wall can't classify",
                        dotColor = if (vm.hasJevKey) NfRang else NfDanger,
                        onClick = { nav.navigate(Routes.KEYS) },
                    )
                }
            }

            SectionLabel("Health")
            NfCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    PermissionRow(
                        label = "Notification access",
                        subtitle = "Lets the wall see incoming notifications",
                        granted = status.notificationAccess,
                        onClick = { runCatching { context.startActivity(Permissions.notificationAccessIntent()) } },
                    )
                    PermissionRow(
                        label = "Do Not Disturb access",
                        subtitle = "Lets the wall hold notifications back until judged",
                        granted = status.dndAccess,
                        onClick = { runCatching { context.startActivity(Permissions.dndAccessIntent()) } },
                    )
                    PermissionRow(
                        label = "Post notifications",
                        subtitle = "Lets the wall re-post the ones that matter",
                        granted = status.postNotifications,
                        onClick = { runCatching { context.startActivity(Permissions.appNotificationSettingsIntent(context)) } },
                    )
                    PermissionRow(
                        label = "Battery optimisation exemption",
                        subtitle = if (status.batteryExempt) {
                            "Exempt — Funtouch OS won't kill the listener"
                        } else {
                            "Vivo's Funtouch OS aggressively kills background apps. " +
                                "Without this, the listener gets killed and the wall silently stops working."
                        },
                        granted = status.batteryExempt,
                        onClick = { runCatching { context.startActivity(Permissions.batteryExemptionIntent(context)) } },
                        emphasize = !status.batteryExempt,
                    )
                    PermissionRow(
                        label = "Contacts",
                        subtitle = "Lets the wall recognise messages from people you know",
                        granted = status.contacts,
                        onClick = null,
                    )
                    PermissionRow(
                        label = "Alarms & reminders",
                        subtitle = "Used for scheduled housekeeping, not classification",
                        granted = status.exactAlarms,
                        onClick = { runCatching { context.startActivity(Permissions.exactAlarmSettingsIntent(context)) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    subtitle: String,
    granted: Boolean,
    onClick: (() -> Unit)?,
    emphasize: Boolean = false,
) {
    val clickable = onClick != null && !granted
    NfRow(
        title = label,
        subtitle = subtitle,
        dotColor = if (granted) NfRang else NfDanger,
        onClick = if (clickable) onClick else null,
        modifier = Modifier.padding(vertical = if (emphasize) 4.dp else 0.dp),
    )
}
