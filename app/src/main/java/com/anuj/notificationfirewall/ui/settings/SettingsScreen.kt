// ui/settings/SettingsScreen.kt
//
// Hush-styled control room. UI-only pass over the previous screen: every
// setting, list, dialog and destructive flow is wired to the same
// SettingsViewModel calls as before -- only the presentation changed to match
// the Hush mock (sensitivity card, firewall-rule rows, cadence, storage).
package com.anuj.notificationfirewall.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.anuj.notificationfirewall.domain.wall.OverrideKind
import com.anuj.notificationfirewall.ui.BlockIcon
import com.anuj.notificationfirewall.ui.BoltIcon
import com.anuj.notificationfirewall.ui.ClockIcon
import com.anuj.notificationfirewall.ui.HourglassIcon
import com.anuj.notificationfirewall.ui.NfButton
import com.anuj.notificationfirewall.ui.NfCard
import com.anuj.notificationfirewall.ui.NfRow
import com.anuj.notificationfirewall.ui.Routes
import com.anuj.notificationfirewall.ui.StarIcon
import com.anuj.notificationfirewall.ui.ThemeViewModel
import com.anuj.notificationfirewall.ui.TrashIcon
import com.anuj.notificationfirewall.ui.TuneIcon
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
    var digestTimeDialog by remember { mutableStateOf(false) }
    var retentionDialog by remember { mutableStateOf(false) }
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
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            // Header.
            Column(Modifier.padding(top = 14.dp)) {
                Text("Settings", style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp), color = c.title)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Calibrate your acoustic shield and notification filters",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textMuted,
                )
            }

            HushSection("Shield Sensitivity", trailing = {
                HushMiniPill(text = "Active", color = c.accent)
            }) {
                SensitivityCard(ui, vm::onThresholdChange)
            }

            HushSection("Core Firewall Rules") {
                FirewallRulesCard(
                    ui = ui,
                    onAddVip = { pickerFor = OverrideKind.VIP },
                    onAddBlock = { pickerFor = OverrideKind.BLOCK },
                    onRemove = vm::removeOverride,
                    onOtpChange = vm::setOtpFastPathEnabled,
                )
            }

            HushSection("Cadence") {
                CadenceCard(
                    digestMinuteOfDay = ui.digestTimeMinuteOfDay,
                    onEditTime = { digestTimeDialog = true },
                )
            }

            HushSection("Storage & Privacy", trailing = {
                Text("On-Device", style = MaterialTheme.typography.labelMedium, color = c.accent)
            }) {
                StorageCard(
                    retentionDays = ui.textRetentionDays,
                    onEditRetention = { retentionDialog = true },
                    onClearHistory = { deleteHistoryConfirm = true },
                )
            }

            HushSection("More Controls") {
                MoreControlsCard(
                    ui = ui,
                    vm = vm,
                    themeMode = themeMode,
                    onThemeSelect = themeViewModel::setMode,
                    onOpenKeys = { nav.navigate(Routes.KEYS) },
                    onResetLearning = { resetLearningConfirm = true },
                    onDeleteHistory = { deleteHistoryConfirm = true },
                )
            }

            HushSection("Developer tools") {
                DeveloperToolsCard(
                    ui = ui,
                    onEmptyCache = vm::emptyCache,
                    exportIncludeContent = exportIncludeContent,
                    onExportIncludeContentChange = { exportIncludeContent = it },
                    onExport = {
                        scope.launch {
                            pendingExportJson = vm.exportJson(exportIncludeContent)
                            exportLauncher.launch("notification-wall-export.json")
                        }
                    },
                )
            }

            HushSection("Health") {
                HealthCard(ui, context)
            }
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

    if (digestTimeDialog) {
        DigestTimeDialog(
            initial = ui.digestTimeMinuteOfDay,
            onDismiss = { digestTimeDialog = false },
            onConfirm = { minuteOfDay ->
                vm.setDigestTimeMinuteOfDay(minuteOfDay)
                digestTimeDialog = false
            },
        )
    }

    if (retentionDialog) {
        RetentionDialog(
            current = ui.textRetentionDays,
            onDismiss = { retentionDialog = false },
            onPick = { days ->
                vm.setTextRetentionDays(days)
                retentionDialog = false
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

/* ------------------------------------------------------------------ */
/* Hush building blocks                                                */
/* ------------------------------------------------------------------ */

@Composable
private fun HushSection(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalWallColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title.uppercase(Locale.ROOT),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                color = c.textMuted,
            )
            if (trailing != null) {
                Spacer(Modifier.weight(1f))
                trailing()
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(c.surface)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

@Composable
private fun HushMiniPill(text: String, color: Color) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 1.sp),
            color = color,
        )
    }
}

/** Icon disc + title/subtitle + optional badge + chevron, in the mock's row style. */
@Composable
private fun HushRuleRow(
    icon: @Composable () -> Unit,
    discTint: Color,
    title: String,
    subtitle: String,
    badge: String? = null,
    badgeTint: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = LocalWallColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(discTint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = c.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null && badgeTint != null) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(c.background)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            badge.uppercase(Locale.ROOT),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.8.sp),
                            color = badgeTint,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = c.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onClick != null) {
            Spacer(Modifier.width(6.dp))
            Text("›", style = MaterialTheme.typography.titleLarge, color = c.textFaint)
        }
    }
}

/* ------------------------------------------------------------------ */
/* Sensitivity (0-100 display over the real 1-5 threshold)             */
/* ------------------------------------------------------------------ */

private fun displayThreshold(threshold: Float): Float = ((threshold - 1f) / 4f * 100f).coerceIn(0f, 100f)

private fun sliderToThreshold(display: Float): Float = 1f + display / 100f * 4f

private data class SensitivityPreset(val label: String, val hint: String)

private fun sensitivityPreset(display: Float): SensitivityPreset = when {
    display < 40f -> SensitivityPreset("Permissive Mode", "Lets most notifications through")
    display <= 75f -> SensitivityPreset("Balanced Protection", "Allows verified and urgent notifications through")
    else -> SensitivityPreset("Fortress Isolation", "Only critical alerts break through")
}

@Composable
private fun SensitivityCard(ui: SettingsUiState, onChange: (Float) -> Unit) {
    val c = LocalWallColors.current
    val display = displayThreshold(ui.threshold)
    val preset = sensitivityPreset(display)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(c.surfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            TuneIcon(Modifier.size(21.dp), track = c.textMuted, knob = c.text)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(preset.label, style = MaterialTheme.typography.titleMedium, color = c.title)
            Text(preset.hint, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        }
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(c.background)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                display.toInt().toString(),
                style = MaterialTheme.typography.titleLarge,
                color = c.accent,
            )
            Text("/100", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
        }
    }
    Spacer(Modifier.height(10.dp))
    Slider(
        value = display,
        onValueChange = { onChange(sliderToThreshold(it)) },
        valueRange = 0f..100f,
        colors = SliderDefaults.colors(
            thumbColor = c.accent,
            activeTrackColor = c.accent,
            inactiveTrackColor = c.background,
        ),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("0 Permissive", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp), color = c.textMuted)
        Text("50 Balanced", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp), color = c.textMuted)
        Text("100 Fortress", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp), color = c.textMuted)
    }
    val preview = ui.thresholdPreview
    val previewLine = when {
        preview.wouldRingMore == 0 && preview.wouldSilenceMore == 0 ->
            "No change to today's notifications"
        else -> buildString {
            if (preview.wouldRingMore > 0) append("${preview.wouldRingMore} more would have rung you today")
            if (preview.wouldRingMore > 0 && preview.wouldSilenceMore > 0) append(" · ")
            if (preview.wouldSilenceMore > 0) append("${preview.wouldSilenceMore} fewer would have rung")
        }
    }
    Text(previewLine, style = MaterialTheme.typography.labelMedium, color = c.textMuted)
}

/* ------------------------------------------------------------------ */
/* Firewall rules                                                      */
/* ------------------------------------------------------------------ */

@Composable
private fun FirewallRulesCard(
    ui: SettingsUiState,
    onAddVip: () -> Unit,
    onAddBlock: () -> Unit,
    onRemove: (OverrideRow, OverrideKind) -> Unit,
    onOtpChange: (Boolean) -> Unit,
) {
    val c = LocalWallColors.current
    var vipExpanded by rememberSaveable { mutableStateOf(false) }
    var blockExpanded by rememberSaveable { mutableStateOf(false) }

    HushRuleRow(
        icon = { StarIcon(color = c.bucketRang, modifier = Modifier.size(21.dp)) },
        discTint = c.bucketRang,
        title = "Always Ring (VIPs)",
        subtitle = "Family, starred contacts, and critical systems",
        badge = "${ui.vipList.size} VIPs",
        badgeTint = c.bucketRang,
        onClick = { vipExpanded = !vipExpanded },
    )
    if (vipExpanded) {
        OverrideEditor(
            rows = ui.vipList,
            retentionDays = ui.textRetentionDays,
            emptyHint = "No one is on the VIP list yet.",
            kind = OverrideKind.VIP,
            onAdd = onAddVip,
            onRemove = onRemove,
        )
    }
    HushRuleRow(
        icon = { BlockIcon(color = c.bucketDropped, modifier = Modifier.size(21.dp)) },
        discTint = c.bucketDropped,
        title = "Never Show (Blocklist)",
        subtitle = "Spam promotions, shopping alerts, and ads",
        badge = "${ui.blockList.size} Rules",
        badgeTint = c.bucketDropped,
        onClick = { blockExpanded = !blockExpanded },
    )
    if (blockExpanded) {
        OverrideEditor(
            rows = ui.blockList,
            retentionDays = ui.textRetentionDays,
            emptyHint = "Nothing is blocked yet.",
            kind = OverrideKind.BLOCK,
            onAdd = onAddBlock,
            onRemove = onRemove,
        )
    }
    // OTP fast path.
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(c.background),
            contentAlignment = Alignment.Center,
        ) {
            BoltIcon(color = c.accent, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("OTP Fast Path", style = MaterialTheme.typography.titleMedium, color = c.title)
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(c.bucketRang.copy(alpha = 0.14f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        "INSTANT",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.8.sp),
                        color = c.bucketRang,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "Immediately delivers 2FA verification codes",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textMuted,
            )
        }
        Switch(
            checked = ui.otpFastPathEnabled,
            onCheckedChange = onOtpChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onAccent,
                checkedTrackColor = c.accent,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = c.textMuted,
                uncheckedTrackColor = c.background,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun OverrideEditor(
    rows: List<OverrideRow>,
    retentionDays: Int,
    emptyHint: String,
    kind: OverrideKind,
    onAdd: () -> Unit,
    onRemove: (OverrideRow, OverrideKind) -> Unit,
) {
    val c = LocalWallColors.current
    // Read once per composition, not per row: a stable "now" keeps every row
    // in the list judged against the same cutoff.
    val now = remember(rows, retentionDays) { System.currentTimeMillis() }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.background)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (rows.isEmpty()) {
            Text(
                emptyHint,
                style = MaterialTheme.typography.bodyMedium,
                color = c.textMuted,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            rows.forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(row.displayLabel(retentionDays, now), style = MaterialTheme.typography.titleMedium, color = c.text)
                        Text(row.packageName, style = MaterialTheme.typography.bodySmall, color = c.textFaint)
                    }
                    Text(
                        "Remove",
                        style = MaterialTheme.typography.labelMedium,
                        color = c.bucketDropped,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onRemove(row, kind) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        Text(
            "Add app…",
            style = MaterialTheme.typography.labelLarge,
            color = c.accent,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onAdd)
                .padding(vertical = 8.dp),
        )
    }
}

/* ------------------------------------------------------------------ */
/* Cadence                                                             */
/* ------------------------------------------------------------------ */

private fun formatTime12(minuteOfDay: Int): String {
    val h24 = minuteOfDay / 60
    val m = minuteOfDay % 60
    val suffix = if (h24 < 12) "AM" else "PM"
    val h12 = when (val h = h24 % 12) {
        0 -> 12
        else -> h
    }
    return "$h12:${m.toString().padStart(2, '0')} $suffix"
}

@Composable
private fun CadenceCard(digestMinuteOfDay: Int, onEditTime: () -> Unit) {
    val c = LocalWallColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.background)
            .clickable(onClick = onEditTime)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(c.surfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            ClockIcon(color = c.accent, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Daily Digest", style = MaterialTheme.typography.titleMedium, color = c.title)
            Text(
                "Aggregated summary, written on-device",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textMuted,
            )
        }
        Row(
            Modifier
                .clip(CircleShape)
                .background(c.surfaceElevated)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatTime12(digestMinuteOfDay), style = MaterialTheme.typography.labelMedium, color = c.accent)
            Spacer(Modifier.width(4.dp))
            Text("›", style = MaterialTheme.typography.titleMedium, color = c.textMuted)
        }
    }
}

/* ------------------------------------------------------------------ */
/* Storage & privacy                                                   */
/* ------------------------------------------------------------------ */

private fun retentionLabel(days: Int): String =
    if (days == 0) "Forever" else "$days Days"

@Composable
private fun StorageCard(retentionDays: Int, onEditRetention: () -> Unit, onClearHistory: () -> Unit) {
    val c = LocalWallColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.background)
            .clickable(onClick = onEditRetention)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(c.surfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            HourglassIcon(color = c.accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Data Retention", style = MaterialTheme.typography.titleMedium, color = c.title)
            Text(
                "Automatically prune past notifications",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textMuted,
            )
        }
        Box(
            Modifier
                .clip(CircleShape)
                .background(c.surfaceElevated)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text(
                retentionLabel(retentionDays),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = c.accent,
            )
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.background)
            .clickable(onClick = onClearHistory)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(c.surfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            TrashIcon(color = c.textMuted, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Clear Notification History", style = MaterialTheme.typography.titleMedium, color = c.title)
            Text(
                "Remove logs and cached triage data",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textMuted,
            )
        }
        Text(
            "CLEAR",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
            color = c.textMuted,
        )
    }
}

/* ------------------------------------------------------------------ */
/* More controls (everything the mock leaves out, Hush-styled)         */
/* ------------------------------------------------------------------ */

@Composable
private fun MoreControlsCard(
    ui: SettingsUiState,
    vm: SettingsViewModel,
    themeMode: ThemeMode,
    onThemeSelect: (ThemeMode) -> Unit,
    onOpenKeys: () -> Unit,
    onResetLearning: () -> Unit,
    onDeleteHistory: () -> Unit,
) {
    val c = LocalWallColors.current
    val now = remember(ui.learnedSenders, ui.textRetentionDays) { System.currentTimeMillis() }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Learned corrections", style = MaterialTheme.typography.titleMedium, color = c.title)
        Text(
            if (ui.learnedSenders.isEmpty()) "No corrections learned yet"
            else "${ui.learnedSenders.size} sender(s) with a learned nudge",
            style = MaterialTheme.typography.bodyMedium,
            color = c.textMuted,
        )
    }
    ui.learnedSenders.forEach { row ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(row.displayLabel(ui.textRetentionDays, now), style = MaterialTheme.typography.titleMedium, color = c.text)
                Text(row.packageName, style = MaterialTheme.typography.bodySmall, color = c.textFaint)
            }
            Text(
                String.format(Locale.ROOT, "%+.2f", row.bias),
                style = MaterialTheme.typography.labelLarge,
                color = if (row.bias >= 0) c.bucketRang else c.bucketDropped,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Clear",
                style = MaterialTheme.typography.labelMedium,
                color = c.textMuted,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { vm.clearBias(row) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
    if (ui.learnedSenders.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        HushOutlineButton(text = "Reset all learning", onClick = onResetLearning)
    }

    Spacer(Modifier.height(14.dp))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Break-glass duration", style = MaterialTheme.typography.titleMedium, color = c.title)
        Text(
            "How long an emergency window stays open once you trigger it.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.textMuted,
        )
    }
    Spacer(Modifier.height(6.dp))
    Stepper(
        label = "${ui.breakGlassDurationMinutes} min",
        onDecrement = { vm.setBreakGlassDurationMinutes((ui.breakGlassDurationMinutes - 5).coerceAtLeast(5)) },
        onIncrement = { vm.setBreakGlassDurationMinutes((ui.breakGlassDurationMinutes + 5).coerceAtMost(120)) },
    )

    Spacer(Modifier.height(14.dp))
    Text("Appearance", style = MaterialTheme.typography.titleMedium, color = c.title)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeMode.entries.forEach { option ->
            val selected = themeMode == option
            Box(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(if (selected) c.accent else c.background)
                    .clickable { onThemeSelect(option) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (selected) c.onAccent else c.textMuted,
                )
            }
        }
    }

    Spacer(Modifier.height(6.dp))
    HushRuleRow(
        icon = { BoltIcon(color = c.accent, modifier = Modifier.size(19.dp)) },
        discTint = c.accent,
        title = "API keys",
        subtitle = "Jev and OpenAI, stored encrypted",
        onClick = onOpenKeys,
    )

    Spacer(Modifier.height(6.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(CircleShape)
            .background(c.danger)
            .clickable(onClick = onDeleteHistory)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            "Delete all history",
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
            color = c.background,
        )
    }
}

/* ------------------------------------------------------------------ */
/* Developer tools: verdict cache + decision export for verification   */
/* ------------------------------------------------------------------ */

@Composable
private fun DeveloperToolsCard(
    ui: SettingsUiState,
    onEmptyCache: () -> Unit,
    exportIncludeContent: Boolean,
    onExportIncludeContentChange: (Boolean) -> Unit,
    onExport: () -> Unit,
) {
    val c = LocalWallColors.current

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Decision export", style = MaterialTheme.typography.titleMedium, color = c.title)
        Text(
            "Every notification plus what the wall did — shown, muted, or blocked — and why. " +
                "Feed the file to another model to check our calls.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.textMuted,
        )
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = exportIncludeContent,
            onCheckedChange = onExportIncludeContentChange,
            colors = CheckboxDefaults.colors(checkedColor = c.accent, checkmarkColor = c.onAccent),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "Include titles and message text in the export",
            style = MaterialTheme.typography.bodyMedium,
            color = c.text,
        )
    }
    Text(
        "${ui.historyCount} notification(s) stored",
        style = MaterialTheme.typography.bodyMedium,
        color = c.textMuted,
    )
    Spacer(Modifier.height(8.dp))
    HushOutlineButton(text = "Export decisions", onClick = onExport)

    Spacer(Modifier.height(14.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Verdict cache", style = MaterialTheme.typography.titleMedium, color = c.title)
            Text(
                "${ui.cacheEntryCount} cached verdict(s)",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textMuted,
            )
        }
        Text(
            "Empty",
            style = MaterialTheme.typography.labelMedium,
            color = c.accent,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onEmptyCache)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun HushOutlineButton(text: String, onClick: () -> Unit) {
    val c = LocalWallColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(CircleShape)
            .background(c.background)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = c.text)
    }
}

@Composable
private fun Stepper(label: String, onDecrement: () -> Unit, onIncrement: () -> Unit) {
    val c = LocalWallColors.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(c.background)
                .clickable(onClick = onDecrement),
            contentAlignment = Alignment.Center,
        ) {
            Text("−", style = MaterialTheme.typography.titleLarge, color = c.text)
        }
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = c.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(c.background)
                .clickable(onClick = onIncrement),
            contentAlignment = Alignment.Center,
        ) {
            Text("+", style = MaterialTheme.typography.titleLarge, color = c.text)
        }
    }
}

/* ------------------------------------------------------------------ */
/* Dialogs                                                             */
/* ------------------------------------------------------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DigestTimeDialog(initial: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val c = LocalWallColors.current
    val state = rememberTimePickerState(
        initialHour = initial / 60,
        initialMinute = initial % 60,
        is24Hour = false,
    )
    Dialog(onDismissRequest = onDismiss) {
        NfCard {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Digest time", style = MaterialTheme.typography.titleLarge, color = c.title)
                Spacer(Modifier.height(4.dp))
                Text(
                    "The daily summary aims to arrive around this time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textMuted,
                )
                Spacer(Modifier.height(12.dp))
                TimePicker(state = state)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NfButton("Cancel", primary = false, onClick = onDismiss, modifier = Modifier.weight(1f))
                    NfButton(
                        "Set time",
                        onClick = { onConfirm(state.hour * 60 + state.minute) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private val RetentionPresets = listOf(7, 14, 30, 90, 0)

@Composable
private fun RetentionDialog(current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val c = LocalWallColors.current
    Dialog(onDismissRequest = onDismiss) {
        NfCard {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Text retention", style = MaterialTheme.typography.titleLarge, color = c.title)
                Text(
                    "How long notification text stays on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textMuted,
                )
                Spacer(Modifier.height(8.dp))
                RetentionPresets.forEach { days ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(days) }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (days == 0) "Keep forever" else "$days days",
                            style = MaterialTheme.typography.titleMedium,
                            color = c.text,
                            modifier = Modifier.weight(1f),
                        )
                        if (days == current) {
                            Text("✓", style = MaterialTheme.typography.titleMedium, color = c.accent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthCard(ui: SettingsUiState, context: android.content.Context) {
    val c = LocalWallColors.current
    val status = ui.permissionStatus
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(c.surface)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
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
