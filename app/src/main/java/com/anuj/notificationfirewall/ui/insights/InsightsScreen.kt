// ui/insights/InsightsScreen.kt
//
// On-device analytics over the last 7 days. Plain typed queries, no API key
// and no network -- the numbers come from AskViewModel's stats, shared with
// the Ask chat screen.
package com.anuj.notificationfirewall.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anuj.notificationfirewall.ui.StatusDot
import com.anuj.notificationfirewall.ui.ask.AskViewModel
import com.anuj.notificationfirewall.ui.theme.LocalWallColors

@Composable
fun InsightsScreen() {
    // Intentionally the shared AskViewModel: the stats are the same typed
    // queries the chat reasons over, so both screens can never disagree.
    // (Scoped per back-stack entry, so this is a separate instance that
    // loads its own snapshot on entry.)
    val vm: AskViewModel = hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose {}
    }

    val c = LocalWallColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.background)
            .statusBarsPadding(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(
                Modifier.padding(top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(color = c.accent, size = 7.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "100% LOCAL TELEMETRY",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                        color = c.accent,
                    )
                }
                Text("Insights", style = MaterialTheme.typography.headlineLarge, color = c.title)
                Text(
                    "Your attention diet for the last 7 days, computed on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textMuted,
                )
            }

            RigorCard(stats = ui.stats)
            WeeklyCard(stats = ui.stats)
            FocusCard(stats = ui.stats)
            SourceCard(stats = ui.stats)
            TopAppsCard(stats = ui.stats)
        }
    }
}
