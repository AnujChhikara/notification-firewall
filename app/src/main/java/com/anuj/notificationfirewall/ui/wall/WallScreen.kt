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
import androidx.compose.runtime.LaunchedEffect
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
import com.anuj.notificationfirewall.ai.PersistedDigest
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.service.WallState
import com.anuj.notificationfirewall.ui.NfButton
import com.anuj.notificationfirewall.ui.NfCard
import com.anuj.notificationfirewall.ui.NfScreen
import com.anuj.notificationfirewall.ui.StatusDot
import com.anuj.notificationfirewall.ui.bucketColor
import com.anuj.notificationfirewall.ui.permissions.Permissions
import com.anuj.notificationfirewall.ui.theme.LocalWallColors
import kotlinx.coroutines.delay

@Composable
fun WallScreen(nav: NavHostController) {
    val vm: WallViewModel = hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose {}
    }

    // The countdown text is derived from a fixed timestamp, so nothing else
    // recomposes it as time passes -- tick refresh() every few seconds while
    // a window is open, purely so "N min left" counts down and the row
    // disappears on its own the moment the window (or the alarm's re-arm)
    // ends.
    LaunchedEffect(ui.breakGlassUntilMs != null) {
        while (ui.breakGlassUntilMs != null) {
            delay(5_000)
            vm.refresh()
        }
    }

    NfScreen(title = "Wall") { modifier ->
        Column(
            modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            val breakGlassUntilMs = ui.breakGlassUntilMs
            // Blocked states are checked first, ahead of the break-glass
            // countdown: a listener disconnect mid-window is exactly the
            // condition that will make the pending re-arm fail (see
            // BreakGlassController.finishExpiry), so it is more urgent than
            // a cheerful countdown and must not be hidden behind one.
            when {
                ui.state == WallState.BLOCKED_NO_LISTENER -> {
                    BlockedCard(
                        message = "Notification access is off",
                        buttonLabel = "Open notification access settings",
                        onFix = { context.startActivity(Permissions.notificationAccessIntent()) },
                    )
                }
                ui.state == WallState.BLOCKED_NO_POLICY_ACCESS -> {
                    BlockedCard(
                        message = "Do Not Disturb access is off",
                        buttonLabel = "Open Do Not Disturb access settings",
                        onFix = { context.startActivity(Permissions.dndAccessIntent()) },
                    )
                }
                breakGlassUntilMs != null -> {
                    BreakGlassHero(untilMs = breakGlassUntilMs, onReArmNow = vm::cancelBreakGlass)
                }
                else -> {
                    ToggleHero(state = ui.state, onToggle = vm::toggle)
                }
            }

            Spacer(Modifier.height(20.dp))
            CountersRow(ui.counts)

            if (breakGlassUntilMs == null) {
                Spacer(Modifier.height(20.dp))
                NfButton(
                    text = "Let everything through for ${formatBreakGlassDuration(ui.breakGlassDurationMinutes)}",
                    onClick = vm::breakGlass,
                    enabled = ui.state == WallState.ARMED || ui.state == WallState.DISARMED,
                    primary = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Spec §5.1: "the most recent digest as a card." Nothing was
            // computed here -- ui.digest is exactly what DigestWorker
            // persisted after building the notification (see
            // WallViewModel.loadDigestIfFresh), so this card can never show
            // a different headline than the one the user was actually
            // notified with.
            ui.digest?.let { digest ->
                Spacer(Modifier.height(20.dp))
                DigestCard(digest)
            }
        }
    }
}

/** Minutes remaining, rounded up so the row never shows "0 min left". */
private fun minutesLeft(untilMs: Long, nowMs: Long = System.currentTimeMillis()): Int =
    ((untilMs - nowMs).coerceAtLeast(0) + 59_999L).let { (it / 60_000L).toInt() }

/**
 * "Let everything through for N" label text, driven by the actual configured
 * duration rather than a hardcoded "1 hour" -- the default is 15 minutes
 * ([com.anuj.notificationfirewall.data.prefs.WallSettings]), so a fixed
 * "1 hour" label would promise something the button did not do out of the box.
 */
private fun formatBreakGlassDuration(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> if (minutes == 60) "1 hour" else "${minutes / 60} hours"
    else -> "${minutes / 60}h ${minutes % 60}min"
}

@Composable
private fun BreakGlassHero(untilMs: Long, onReArmNow: () -> Unit) {
    val c = LocalWallColors.current
    val shape = RoundedCornerShape(24.dp)
    val minutes = minutesLeft(untilMs)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .border(2.dp, c.border, shape)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Everything is getting through · $minutes min left",
            style = MaterialTheme.typography.titleMedium,
            color = c.title,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Break-glass is open. The wall re-arms itself automatically.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.textMuted,
        )
        Spacer(Modifier.height(16.dp))
        NfButton(text = "Re-arm now", onClick = onReArmNow, modifier = Modifier.fillMaxWidth())
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

/**
 * Spec §5.1's "most recent digest as a card". Shows exactly [digest] as
 * persisted by [com.anuj.notificationfirewall.work.DigestWorker] --
 * [PersistedDigest.headline] is the literal sentence the notification led
 * with (model prose or the local fallback, whichever actually ran), and
 * [PersistedDigest.worthALook] is already purge-guarded by
 * [com.anuj.notificationfirewall.ai.DigestBuilder] before it was ever
 * persisted, so nothing extra needs to be filtered here.
 */
@Composable
private fun DigestCard(digest: PersistedDigest) {
    val c = LocalWallColors.current
    NfCard(Modifier.padding(4.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text("Yesterday's digest", style = MaterialTheme.typography.titleMedium, color = c.title)
            Spacer(Modifier.height(6.dp))
            Text(digest.headline, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            if (digest.worthALook.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Worth a look",
                    style = MaterialTheme.typography.labelLarge,
                    color = c.title,
                )
                Spacer(Modifier.height(4.dp))
                digest.worthALook.forEach { line ->
                    Text(
                        "· $line",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textMuted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
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
