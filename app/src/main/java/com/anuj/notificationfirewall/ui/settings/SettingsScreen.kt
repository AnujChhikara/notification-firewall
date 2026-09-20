// ui/settings/SettingsScreen.kt
package com.anuj.notificationfirewall.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.anuj.notificationfirewall.domain.wall.OverrideKind
import com.anuj.notificationfirewall.ui.NfButton
import com.anuj.notificationfirewall.ui.NfCard
import com.anuj.notificationfirewall.ui.NfChip
import com.anuj.notificationfirewall.ui.NfRow
import com.anuj.notificationfirewall.ui.NfScreen
import com.anuj.notificationfirewall.ui.Routes
import com.anuj.notificationfirewall.ui.SectionLabel
import com.anuj.notificationfirewall.ui.ThemeViewModel
import com.anuj.notificationfirewall.ui.permissions.Permissions
import com.anuj.notificationfirewall.ui.theme.LocalWallColors
import com.anuj.notificationfirewall.ui.theme.ThemeMode
import com.anuj.notificationfirewall.ui.theme.WallColors
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The wall's control room: sensitivity, VIP/block lists, learned corrections,
 * behaviour, appearance, keys, data retention/export/purge, and live health.
 *
 * [themeViewModel] is passed in rather than resolved with `hiltViewModel()`
 * here, because [ThemeViewModel] is scoped to the Activity in
 * `MainActivity.setContent` — a second `hiltViewModel()` call from inside the
 * nav graph would resolve against the back-stack entry instead and produce a
 * second instance with its own `StateFlow`, silently out of sync with the one
 * actually driving `NfTheme`.
 */
@Composable
fun SettingsScreen(nav: NavHostController, themeViewModel: ThemeViewModel) {
    val vm: SettingsViewModel = hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val themeMode by themeViewModel.mode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LifecycleResumeEffect(Unit) {
        vm.refreshHealth()
        onPauseOrDispose {}
    }

    var pickerFor by remember { mutableStateOf<OverrideKind?>(null) }
    var resetLearningConfirm by remember { mutableStateOf(false) }
    var deleteHistoryConfirm by remember { mutableStateOf(false) }
    var exportIncludeContent by remember { mutableStateOf(false) }
    var pendingExportJson by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val json = pendingExportJson
        pendingExportJson = null
        if (uri != null && json != null) {
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
        }
    }

    NfScreen(eyebrow = "Tune the wall", title = "Settings") { modifier ->
        Column(
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel("Sensitivity")
            SensitivityCard(ui, vm::onThresholdChange)

            SectionLabel("Always ring")
            OverrideListCard(
                rows = ui.vipList,
                emptyHint = "No one is on the VIP list yet.",
                onAdd = { pickerFor = OverrideKind.VIP },
                onRemove = { vm.removeOverride(it, OverrideKind.VIP) },
            )

            SectionLabel("Never show")
            OverrideListCard(
                rows = ui.blockList,
                emptyHint = "Nothing is blocked yet.",
                onAdd = { pickerFor = OverrideKind.BLOCK },
                onRemove = { vm.removeOverride(it, OverrideKind.BLOCK) },
            )

            SectionLabel("Learned corrections")
            LearnedCorrectionsCard(
                rows = ui.learnedSenders,
                onClear = vm::clearBias,
                onResetAll = { resetLearningConfirm = true },
            )

            SectionLabel("Behaviour")
            BehaviourCard(ui, vm)

            SectionLabel("Appearance")
            AppearanceCard(themeMode, themeViewModel::setMode)

            SectionLabel("Keys")
            KeysLinkCard(onOpen = { nav.navigate(Routes.KEYS) })

            SectionLabel("Data")
            DataCard(
                ui = ui,
                exportIncludeContent = exportIncludeContent,
                onExportIncludeContentChange = { exportIncludeContent = it },
                onEmptyCache = vm::emptyCache,
                onExport = {
                    scope.launch {
                        pendingExportJson = vm.exportJson(exportIncludeContent)
                        exportLauncher.launch("notification-wall-export.json")
                    }
                },
                onDeleteHistory = { deleteHistoryConfirm = true },
                onRetentionChange = vm::setTextRetentionDays,
            )

            SectionLabel("Health")
            HealthCard(ui, context)
        }
    }

    pickerFor?.let { kind ->
        AppPickerDialog(
            vm = vm,
            title = if (kind == OverrideKind.VIP) "Always ring" else "Never show",
            onDismiss = { pickerFor = null },
            onPick = { app ->
                vm.addOverride(kind, app.packageName, app.label)
                pickerFor = null
            },
        )
    }

    if (resetLearningConfirm) {
        ConfirmDialog(
            title = "Reset all learning?",
            message = "Every sender's learned nudge goes back to zero. " +
                "The wall's threshold judgement is unaffected — only what you've " +
                "corrected it on is forgotten.",
            confirmLabel = "Reset",
            onConfirm = {
                vm.resetAllLearning()
                resetLearningConfirm = false
            },
            onDismiss = { resetLearningConfirm = false },
        )
    }

    if (deleteHistoryConfirm) {
        TypedConfirmDialog(
            title = "Delete all history?",
            message = "This permanently deletes every stored notification record " +
                "(${ui.historyCount} of them). This cannot be undone.",
            requiredText = "DELETE",
            onConfirm = {
                vm.deleteAllHistory()
                deleteHistoryConfirm = false
            },
            onDismiss = { deleteHistoryConfirm = false },
        )
    }
}

@Composable
private fun SensitivityCard(ui: SettingsUiState, onChange: (Float) -> Unit) {
    val c = LocalWallColors.current
    NfCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Strict", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
                Text("Relaxed", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
            }
            Slider(
                value = ui.threshold,
                onValueChange = onChange,
                valueRange = 1f..5f,
                colors = SliderDefaults.colors(
                    thumbColor = c.accent,
                    activeTrackColor = c.accent,
                    inactiveTrackColor = c.border,
                ),
            )
            Text(
                "Rings at ${String.format(Locale.ROOT, "%.1f", ui.threshold)} and above",
                style = MaterialTheme.typography.bodyMedium,
                color = c.text,
            )
            val preview = ui.thresholdPreview
            val previewLine = when {
                preview.wouldRingMore == 0 && preview.wouldSilenceMore == 0 ->
                    "No change to today's notifications"
                else -> buildString {
                    if (preview.wouldRingMore > 0) append("${preview.wouldRingMore} more would have rung you today")
                    if (preview.wouldRingMore > 0 && preview.wouldSilenceMore > 0) append(" · ")
                    if (preview.wouldSilenceMore > 0) append("${preview.wouldSilenceMore} fewer would have")
                }
            }
            Text(previewLine, style = MaterialTheme.typography.labelMedium, color = c.textMuted)
        }
    }
}

@Composable
private fun OverrideListCard(
    rows: List<OverrideRow>,
    emptyHint: String,
    onAdd: () -> Unit,
    onRemove: (OverrideRow) -> Unit,
) {
    val c = LocalWallColors.current
    NfCard {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            if (rows.isEmpty()) {
                Text(
                    emptyHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textMuted,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                )
            } else {
                rows.forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(vertical = 8.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(row.label, style = MaterialTheme.typography.titleMedium, color = c.text)
                                if (row.fromInbox) {
                                    NfChip(text = "from inbox", selected = false, onClick = {})
                                }
                            }
                            Text(row.packageName, style = MaterialTheme.typography.bodySmall, color = c.textFaint)
                        }
                        NfButton("Remove", primary = false, onClick = { onRemove(row) })
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            NfButton("Add app…", primary = false, onClick = onAdd, modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun LearnedCorrectionsCard(
    rows: List<LearnedSenderRow>,
    onClear: (LearnedSenderRow) -> Unit,
    onResetAll: () -> Unit,
) {
    val c = LocalWallColors.current
    NfCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (rows.isEmpty()) "No corrections learned yet" else "${rows.size} sender(s) with a learned nudge",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textMuted,
            )
            rows.forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(row.senderKey, style = MaterialTheme.typography.titleMedium, color = c.text)
                        Text(row.packageName, style = MaterialTheme.typography.bodySmall, color = c.textFaint)
                    }
                    Text(
                        String.format(Locale.ROOT, "%+.2f", row.bias),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (row.bias >= 0) c.bucketRang else c.bucketDropped,
                    )
                    NfButton("Clear", primary = false, onClick = { onClear(row) })
                }
            }
            if (rows.isNotEmpty()) {
                NfButton("Reset all learning", primary = false, onClick = onResetAll)
            }
        }
    }
}

@Composable
private fun BehaviourCard(ui: SettingsUiState, vm: SettingsViewModel) {
    val c = LocalWallColors.current
    NfCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("OTP fast path", style = MaterialTheme.typography.titleMedium, color = c.text)
                    Text(
                        "One-time codes always ring, instantly, with no network call.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textMuted,
                    )
                }
                Switch(
                    checked = ui.otpFastPathEnabled,
                    onCheckedChange = vm::setOtpFastPathEnabled,
                    colors = SwitchDefaults.colors(checkedTrackColor = c.accent),
                )
            }

            Column {
                Text("Digest time", style = MaterialTheme.typography.titleMedium, color = c.text)
                Text(
                    "The daily summary aims to arrive around ${formatMinuteOfDay(ui.digestTimeMinuteOfDay)}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                )
                Spacer(Modifier.height(6.dp))
                Stepper(
                    label = formatMinuteOfDay(ui.digestTimeMinuteOfDay),
                    onDecrement = { vm.setDigestTimeMinuteOfDay(wrapMinuteOfDay(ui.digestTimeMinuteOfDay - 30)) },
                    onIncrement = { vm.setDigestTimeMinuteOfDay(wrapMinuteOfDay(ui.digestTimeMinuteOfDay + 30)) },
                )
            }

            Column {
                Text("Break-glass duration", style = MaterialTheme.typography.titleMedium, color = c.text)
                Text(
                    "How long an emergency window stays open once you trigger it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                )
                Spacer(Modifier.height(6.dp))
                Stepper(
                    label = "${ui.breakGlassDurationMinutes} min",
                    onDecrement = { vm.setBreakGlassDurationMinutes((ui.breakGlassDurationMinutes - 5).coerceAtLeast(5)) },
                    onIncrement = { vm.setBreakGlassDurationMinutes((ui.breakGlassDurationMinutes + 5).coerceAtMost(120)) },
                )
            }
        }
    }
}

@Composable
private fun Stepper(label: String, onDecrement: () -> Unit, onIncrement: () -> Unit) {
    val c = LocalWallColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NfButton("−", primary = false, onClick = onDecrement)
        Text(label, style = MaterialTheme.typography.titleMedium, color = c.text)
        NfButton("+", primary = false, onClick = onIncrement)
    }
}

private fun formatMinuteOfDay(minuteOfDay: Int): String {
    val h = minuteOfDay / 60
    val m = minuteOfDay % 60
    return String.format(Locale.ROOT, "%02d:%02d", h, m)
}

private fun wrapMinuteOfDay(value: Int): Int = ((value % 1440) + 1440) % 1440

@Composable
private fun AppearanceCard(mode: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    NfCard {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemeMode.entries.forEach { option ->
                NfChip(
                    text = option.name.lowercase().replaceFirstChar { it.uppercase() },
                    selected = mode == option,
                    onClick = { onSelect(option) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun KeysLinkCard(onOpen: () -> Unit) {
    val c = LocalWallColors.current
    NfCard {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            NfRow(
                title = "API keys",
                subtitle = "Jev (required) and OpenAI (optional), stored encrypted",
                onClick = onOpen,
            )
            Text(
                "Changing the Jev key needs an app restart to take effect — " +
                    "it's read once when the app process starts.",
                style = MaterialTheme.typography.labelSmall,
                color = c.textFaint,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
            )
        }
    }
}

@Composable
private fun DataCard(
    ui: SettingsUiState,
    exportIncludeContent: Boolean,
    onExportIncludeContentChange: (Boolean) -> Unit,
    onEmptyCache: () -> Unit,
    onExport: () -> Unit,
    onDeleteHistory: () -> Unit,
    onRetentionChange: (Int) -> Unit,
) {
    val c = LocalWallColors.current
    NfCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column {
                Text("Text retention", style = MaterialTheme.typography.titleMedium, color = c.text)
                Text(
                    if (ui.textRetentionDays == 0) {
                        "Notification text is kept forever"
                    } else {
                        "Notification text is purged after ${ui.textRetentionDays} day(s)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                )
                Spacer(Modifier.height(6.dp))
                Stepper(
                    label = if (ui.textRetentionDays == 0) "Never" else "${ui.textRetentionDays}d",
                    onDecrement = { onRetentionChange((ui.textRetentionDays - 7).coerceAtLeast(0)) },
                    onIncrement = { onRetentionChange((ui.textRetentionDays + 7).coerceAtMost(365)) },
                )
            }

            NfRow(
                title = "Verdict cache",
                subtitle = "${ui.cacheEntryCount} cached verdict(s)",
                trailing = "Empty",
                onClick = onEmptyCache,
            )

            NfRow(
                title = "History",
                subtitle = "${ui.historyCount} notification(s) stored",
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(
                    checked = exportIncludeContent,
                    onCheckedChange = onExportIncludeContentChange,
                    colors = CheckboxDefaults.colors(checkedColor = c.accent),
                )
                Text(
                    "Include titles and message text in the export",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.text,
                )
            }
            NfButton("Export history", primary = false, onClick = onExport)

            NfButton("Delete all history", primary = false, onClick = onDeleteHistory)
        }
    }
}

@Composable
private fun HealthCard(ui: SettingsUiState, context: android.content.Context) {
    val c = LocalWallColors.current
    val status = ui.permissionStatus
    NfCard {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            if (status == null) {
                Text(
                    "Checking…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textMuted,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                HealthRow(
                    "Notification access", status.notificationAccess, c,
                    onFix = { context.startActivity(Permissions.notificationAccessIntent()) },
                )
                HealthRow(
                    "Do Not Disturb access", status.dndAccess, c,
                    onFix = { context.startActivity(Permissions.dndAccessIntent()) },
                )
                HealthRow(
                    "Contacts (for VIP detection)", status.contacts, c,
                    onFix = { context.startActivity(Permissions.appDetailsSettingsIntent(context)) },
                )
                HealthRow(
                    "Post notifications", status.postNotifications, c,
                    onFix = { context.startActivity(Permissions.appNotificationSettingsIntent(context)) },
                )
                HealthRow(
                    "Battery optimization exempt", status.batteryExempt, c,
                    onFix = { context.startActivity(Permissions.batteryExemptionIntent(context)) },
                )
                HealthRow(
                    "Exact alarms allowed", status.exactAlarms, c,
                    onFix = { context.startActivity(Permissions.exactAlarmSettingsIntent(context)) },
                )
                HealthRow("Jev API key configured", status.hasApiKey, c, onFix = null)
            }
        }
    }
}

@Composable
private fun HealthRow(label: String, ok: Boolean, c: WallColors, onFix: (() -> Unit)?) {
    NfRow(
        title = label,
        dotColor = if (ok) c.bucketRang else c.danger,
        trailing = if (ok) "OK" else "Fix",
        onClick = if (!ok) onFix else null,
    )
}

@Composable
private fun AppPickerDialog(
    vm: SettingsViewModel,
    title: String,
    onDismiss: () -> Unit,
    onPick: (InstalledApp) -> Unit,
) {
    val c = LocalWallColors.current
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { apps = vm.installedApps() }

    Dialog(onDismissRequest = onDismiss) {
        NfCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = c.title)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search apps") },
                    singleLine = true,
                    colors = dialogFieldColors(c),
                    modifier = Modifier.fillMaxWidth(),
                )
                val filtered = apps.filter {
                    query.isBlank() ||
                        it.label.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
                }
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        NfRow(title = app.label, subtitle = app.packageName, onClick = { onPick(app) })
                    }
                }
                NfButton("Cancel", primary = false, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalWallColors.current
    Dialog(onDismissRequest = onDismiss) {
        NfCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = c.title)
                Text(message, style = MaterialTheme.typography.bodyMedium, color = c.text)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NfButton("Cancel", primary = false, onClick = onDismiss)
                    NfButton(confirmLabel, onClick = onConfirm)
                }
            }
        }
    }
}

@Composable
private fun TypedConfirmDialog(
    title: String,
    message: String,
    requiredText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalWallColors.current
    var typed by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        NfCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = c.danger)
                Text(message, style = MaterialTheme.typography.bodyMedium, color = c.text)
                Text(
                    "Type $requiredText to confirm.",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.textMuted,
                )
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    colors = dialogFieldColors(c, accent = c.danger),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NfButton("Cancel", primary = false, onClick = onDismiss)
                    NfButton(
                        "Delete permanently",
                        onClick = onConfirm,
                        enabled = typed == requiredText,
                    )
                }
            }
        }
    }
}

@Composable
private fun dialogFieldColors(c: WallColors, accent: Color = c.accent) = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedIndicatorColor = accent,
    unfocusedIndicatorColor = c.border,
    focusedTextColor = c.title,
    unfocusedTextColor = c.text,
    cursorColor = accent,
)
