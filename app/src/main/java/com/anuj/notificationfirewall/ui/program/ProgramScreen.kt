// ui/program/ProgramScreen.kt
package com.anuj.notificationfirewall.ui.program

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
fun ProgramScreen(nav: NavHostController) {
    NfScreen(eyebrow = "Your track", title = "Tame the Scroll") { modifier ->
        Column(
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NfCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Six small steps. Each one teaches a little and quietly changes how your phone works.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "Your program starts with the intake — it's how Still learns what's pulling at you.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NfTextMuted,
                    )
                }
            }
        }
    }
}
