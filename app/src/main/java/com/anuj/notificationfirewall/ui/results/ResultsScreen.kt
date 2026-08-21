// ui/results/ResultsScreen.kt
package com.anuj.notificationfirewall.ui.results

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
import com.anuj.notificationfirewall.ui.NfRow
import com.anuj.notificationfirewall.ui.NfScreen
import com.anuj.notificationfirewall.ui.Routes
import com.anuj.notificationfirewall.ui.SectionLabel
import com.anuj.notificationfirewall.ui.theme.NfRang
import com.anuj.notificationfirewall.ui.theme.NfTextMuted

@Composable
fun ResultsScreen(nav: NavHostController) {
    NfScreen(eyebrow = "This week", title = "Results") { modifier ->
        Column(
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NfCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Still is gathering your first week of data. Real numbers — opens " +
                            "intercepted, times you walked away, focus you protected — land here.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "Baseline comparison arrives with the results engine (Phase 1).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NfTextMuted,
                    )
                }
            }
            SectionLabel("Detail")
            NfCard {
                NfRow(
                    title = "Notification log",
                    subtitle = "Everything the firewall has seen",
                    dotColor = NfRang,
                    onClick = { nav.navigate(Routes.ANALYTICS) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}
