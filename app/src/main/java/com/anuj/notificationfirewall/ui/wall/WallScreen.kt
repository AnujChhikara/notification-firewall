// ui/wall/WallScreen.kt
package com.anuj.notificationfirewall.ui.wall

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.service.WallState
import com.anuj.notificationfirewall.ui.NfButton
import com.anuj.notificationfirewall.ui.NfCard
import com.anuj.notificationfirewall.ui.NfScreen
import com.anuj.notificationfirewall.ui.StatusDot
import com.anuj.notificationfirewall.ui.bucketColor
import com.anuj.notificationfirewall.ui.permissions.Permissions
import com.anuj.notificationfirewall.ui.theme.LocalWallColors

@Composable
fun WallScreen(nav: NavHostController) {
    val vm: WallViewModel = hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose {}
    }

    NfScreen(title = "Wall") { modifier ->
        Column(
            modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            when (ui.state) {
                WallState.ARMED, WallState.DISARMED -> {
                    ToggleHero(state = ui.state, onToggle = vm::toggle)
                }
                WallState.BLOCKED_NO_LISTENER -> {
                    BlockedCard(
                        message = "Notification access is off",
                        buttonLabel = "Open notification access settings",
                        onFix = { context.startActivity(Permissions.notificationAccessIntent()) },
                    )
                }
                WallState.BLOCKED_NO_POLICY_ACCESS -> {
                    BlockedCard(
                        message = "Do Not Disturb access is off",
                        buttonLabel = "Open Do Not Disturb access settings",
                        onFix = { context.startActivity(Permissions.dndAccessIntent()) },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            CountersRow(ui.counts)

            Spacer(Modifier.height(20.dp))
            NfButton(
                text = "Let everything through for 1 hour",
                onClick = {},
                enabled = false,
                primary = false,
                modifier = Modifier.fillMaxWidth(),
            )

            // Digest card lands in Task 10.
        }
    }
}

@Composable
private fun ToggleHero(state: WallState, onToggle: () -> Unit) {
    val c = LocalWallColors.current
    val armed = state == WallState.ARMED
    val borderColor = if (armed) c.bucketRang else c.border
    val shape = RoundedCornerShape(24.dp)

    Column(
        Modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(shape)
            .background(c.surface)
            .border(2.dp, borderColor, shape)
            .clickable(onClick = onToggle)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .padding(bottom = 16.dp)
                .clip(CircleShape)
                .then(if (!armed) Modifier.border(2.dp, c.border, CircleShape) else Modifier)
                .padding(6.dp),
        ) {
            StatusDot(color = if (armed) c.bucketRang else c.background, size = 14.dp)
        }
        Text(
            if (armed) "ARMED" else "DISARMED",
            style = MaterialTheme.typography.headlineMedium,
            color = c.title,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (armed) "Nothing reaches you unless it matters" else "Everything is getting through",
            style = MaterialTheme.typography.bodyMedium,
            color = c.textMuted,
        )
        if (armed) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Do Not Disturb is on. Calls still ring.",
                style = MaterialTheme.typography.labelMedium,
                color = c.textMuted,
            )
        }
    }
}

@Composable
private fun BlockedCard(message: String, buttonLabel: String, onFix: () -> Unit) {
    val c = LocalWallColors.current
    NfCard(Modifier.padding(4.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(message, style = MaterialTheme.typography.titleLarge, color = c.title)
            Spacer(Modifier.height(4.dp))
            Text(
                "The wall can't protect you until this is granted.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textMuted,
            )
            Spacer(Modifier.height(16.dp))
            NfButton(text = buttonLabel, onClick = onFix, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun CountersRow(counts: TodayCounts) {
    val c = LocalWallColors.current
    if (counts.total == 0) {
        Text(
            "Nothing yet today",
            style = MaterialTheme.typography.bodyMedium,
            color = c.textMuted,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        return
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CounterCard(
            modifier = Modifier.weight(1f),
            count = counts.rang,
            label = "Rang",
            bucket = WallBucket.RING,
        )
        CounterCard(
            modifier = Modifier.weight(1f),
            count = counts.silenced,
            label = "Silenced",
            bucket = WallBucket.SILENCE,
        )
        CounterCard(
            modifier = Modifier.weight(1f),
            count = counts.dropped,
            label = "Dropped",
            bucket = WallBucket.DROP,
        )
    }
}

@Composable
private fun CounterCard(count: Int, label: String, bucket: WallBucket, modifier: Modifier = Modifier) {
    val c = LocalWallColors.current
    NfCard(modifier) {
        Column(Modifier.padding(14.dp)) {
            StatusDot(bucketColor(bucket))
            Spacer(Modifier.height(8.dp))
            Text(count.toString(), style = MaterialTheme.typography.displaySmall, color = c.text)
            Text(label, style = MaterialTheme.typography.labelSmall, color = c.textMuted)
        }
    }
}
