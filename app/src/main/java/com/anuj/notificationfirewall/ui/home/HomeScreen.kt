// ui/home/HomeScreen.kt
package com.anuj.notificationfirewall.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.anuj.notificationfirewall.data.db.NotificationRecordEntity
import com.anuj.notificationfirewall.data.db.dao.NotificationDao
import com.anuj.notificationfirewall.data.prefs.SecurePrefs
import com.anuj.notificationfirewall.service.ArmingController
import com.anuj.notificationfirewall.service.HealthEvaluator
import com.anuj.notificationfirewall.service.HealthFlags
import com.anuj.notificationfirewall.service.HealthLevel
import com.anuj.notificationfirewall.service.WallState
import com.anuj.notificationfirewall.ui.NfButton
import com.anuj.notificationfirewall.ui.NfCard
import com.anuj.notificationfirewall.ui.NfRow
import com.anuj.notificationfirewall.ui.NfScreen
import com.anuj.notificationfirewall.ui.Routes
import com.anuj.notificationfirewall.ui.SectionLabel
import com.anuj.notificationfirewall.ui.StatusDot
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.ui.permissions.Permissions
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.anuj.notificationfirewall.ui.theme.NfAccent
import com.anuj.notificationfirewall.ui.theme.NfCaptured
import com.anuj.notificationfirewall.ui.theme.NfDanger
import com.anuj.notificationfirewall.ui.theme.NfRang
import com.anuj.notificationfirewall.ui.theme.NfText
import com.anuj.notificationfirewall.ui.theme.NfTextMuted
import com.anuj.notificationfirewall.ui.theme.NfTitle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val armingController: ArmingController,
    notificationDao: NotificationDao,
    private val securePrefs: SecurePrefs,
) : ViewModel() {

    val wallState = armingController.observeState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), armingController.state())

    val recent = notificationDao.observeRecent(8)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hasApiKey: Boolean get() = securePrefs.hasKey
    val listenerConnected: Boolean get() = securePrefs.listenerConnected

    fun arm() = armingController.arm()
    fun disarm() = armingController.disarm()

    /**
     * Per design §6.1, a reconcile runs on every app resume so a DND change
     * made from the system shade (or a listener that dropped while
     * backgrounded) is reflected immediately rather than waiting for the
     * next system broadcast. Safe to call unconditionally: it only clears
     * [SecurePrefs]'s dndSetByApp bookkeeping when DND is genuinely off, and
     * always nudges [wallState] to re-read live state.
     */
    fun reconcileOnResume() = armingController.onSystemDndChanged()
}

@Composable
fun HomeScreen(nav: NavHostController, vm: HomeViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val wallState by vm.wallState.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()

    // Per design §6.1: reconcile on every resume, since DND can be changed
    // from the system shade while this screen isn't collecting.
    LifecycleResumeEffect(Unit) {
        vm.reconcileOnResume()
        onPauseOrDispose { }
    }
    val status = remember(wallState) { Permissions.status(context, vm.hasApiKey) }
    val health = remember(wallState) {
        HealthEvaluator.evaluate(
            HealthFlags(
                notificationAccess = status.notificationAccess,
                listenerConnected = vm.listenerConnected,
                postNotifications = status.postNotifications,
                // DND is now the wall's sole silencing mechanism, so it's
                // always needed rather than conditional on a profile's setup.
                needsDndAccess = true,
                dndAccess = status.dndAccess,
                batteryExempt = status.batteryExempt,
                exactAlarms = status.exactAlarms,
            ),
        )
    }

    NfScreen(eyebrow = "Still", title = if (status.coreReady) "Armed" else "Setup needed") { modifier ->
        LazyColumn(
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 100.dp),
        ) {
            if (health.level != HealthLevel.HEALTHY) {
                item { HealthBanner(health.level, health.reason) { nav.navigate(Routes.ONBOARDING) } }
            }

            item {
                ArmToggleCard(
                    wallState = wallState,
                    onArm = { vm.arm() },
                    onDisarm = { vm.disarm() },
                    onFixNotificationAccess = {
                        runCatching { context.startActivity(Permissions.notificationAccessIntent()) }
                    },
                    onFixDndAccess = {
                        runCatching { context.startActivity(Permissions.dndAccessIntent()) }
                    },
                )
            }

            if (!status.coreReady) {
                item {
                    NfCard(Modifier.padding(top = 8.dp)) {
                        NfRow(
                            title = "Finish setup",
                            subtitle = "Grant notification access so the firewall can start",
                            dotColor = NfAccent,
                            onClick = { nav.navigate(Routes.ONBOARDING) },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
            }

            item { SectionLabel("Overview") }
            item { NfRow("Inbox", subtitle = "Captured & silenced", dotColor = NfAccent) { nav.navigate(Routes.INBOX) } }

            if (recent.isNotEmpty()) {
                item { SectionLabel("Recent") }
                items(recent) { rec -> RecentRow(rec) }
            }
        }
    }
}

/**
 * The screen's hero control (design §5.1). Renders live [WallState] — never a
 * stored boolean — and is the single thing on Home that arms/disarms the
 * wall. When the engine cannot honor a toggle (policy access or the listener
 * missing), the toggle is replaced entirely by an explanation and a fix
 * button, per §5.1: "The toggle is never shown in a state it cannot honor."
 */
@Composable
private fun ArmToggleCard(
    wallState: WallState,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onFixNotificationAccess: () -> Unit,
    onFixDndAccess: () -> Unit,
) {
    NfCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (wallState) {
                WallState.BLOCKED_NO_POLICY_ACCESS -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusDot(NfDanger, size = 9.dp)
                        Text("Can't arm yet", style = MaterialTheme.typography.titleLarge, color = NfTitle)
                    }
                    Text(
                        "The wall needs Do Not Disturb access to hold back notifications " +
                            "until they're judged important. Grant it to continue.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NfTextMuted,
                    )
                    NfButton("Grant Do Not Disturb access", onClick = onFixDndAccess)
                }

                WallState.BLOCKED_NO_LISTENER -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusDot(NfDanger, size = 9.dp)
                        Text("Can't arm yet", style = MaterialTheme.typography.titleLarge, color = NfTitle)
                    }
                    Text(
                        "The wall needs notification access to see and re-post notifications. " +
                            "Grant it to continue.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NfTextMuted,
                    )
                    NfButton("Grant notification access", onClick = onFixNotificationAccess)
                }

                WallState.ARMED -> {
                    ArmedRow(armed = true, onClick = onDisarm)
                    Text(
                        "Do Not Disturb is on. Calls still ring.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NfTextMuted,
                    )
                }

                WallState.DISARMED -> {
                    ArmedRow(armed = false, onClick = onArm)
                }
            }
        }
    }
}

@Composable
private fun ArmedRow(armed: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (armed) {
            StatusDot(NfRang, size = 16.dp)
        } else {
            Box(
                Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .border(2.dp, NfTextMuted, CircleShape),
            )
        }
        Column {
            Text(
                if (armed) "ARMED" else "DISARMED",
                style = MaterialTheme.typography.headlineSmall,
                color = NfTitle,
            )
            Text(
                if (armed) "Nothing reaches you unless it matters" else "Everything is getting through",
                style = MaterialTheme.typography.bodyMedium,
                color = NfTextMuted,
            )
        }
    }
}

@Composable
private fun RecentRow(rec: NotificationRecordEntity) {
    NfRow(
        title = rec.title?.ifBlank { rec.appLabel } ?: rec.appLabel,
        subtitle = "${rec.appLabel} · ${wallBucketLabel(rec.bucket)}",
        dotColor = wallBucketColor(rec.bucket),
    )
}

// TODO(Task 10): temporary WallBucket display mapping; this screen is
// rewritten against the wall pipeline.
private fun wallBucketColor(bucket: WallBucket) = when (bucket) {
    WallBucket.RING -> NfRang
    WallBucket.SILENCE -> NfTextMuted
    WallBucket.DROP -> NfCaptured
}

private fun wallBucketLabel(bucket: WallBucket) = when (bucket) {
    WallBucket.RING -> "Rang through"
    WallBucket.SILENCE -> "Silenced"
    WallBucket.DROP -> "Dropped"
}

@Composable
private fun HealthBanner(level: HealthLevel, reason: String?, onFix: () -> Unit) {
    val broken = level == HealthLevel.BROKEN
    val accent = if (broken) NfDanger else NfCaptured
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(shape)
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.5f), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(accent, size = 9.dp)
            Text(
                if (broken) "Firewall isn't running" else "Firewall is limited",
                style = MaterialTheme.typography.titleMedium,
                color = NfTitle,
            )
        }
        reason?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = NfText) }
        NfButton(if (broken) "Fix now" else "Review", onClick = onFix, primary = broken)
    }
}
