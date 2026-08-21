// ui/pods/PodsScreen.kt
package com.anuj.notificationfirewall.ui.pods

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.anuj.notificationfirewall.ui.NfCard
import com.anuj.notificationfirewall.ui.NfScreen
import com.anuj.notificationfirewall.ui.theme.NfTextMuted

@Composable
fun PodsScreen(nav: NavHostController) {
    NfScreen(eyebrow = "Together", title = "Pods") { modifier ->
        Column(
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NfCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "A pod is a few friends keeping each other going — a dashboard, not a feed.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "Create or join a pod by invite link — coming with the pods build (Phase 1).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NfTextMuted,
                    )
                }
            }
        }
    }
}
