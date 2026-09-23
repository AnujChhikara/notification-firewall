// ui/inbox/InboxScreen.kt
//
// Hush-styled classification stream. UI-only pass: filtering, search, expand,
// swipe corrections with undo, the long-press sheet and override actions all
// call the same InboxViewModel methods as before.
package com.anuj.notificationfirewall.ui.inbox

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.anuj.notificationfirewall.domain.wall.Correction
import com.anuj.notificationfirewall.domain.wall.NotificationCategory
import com.anuj.notificationfirewall.domain.wall.OverrideKind
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.domain.wall.WallDecisionSource
import com.anuj.notificationfirewall.ui.BlockIcon
import com.anuj.notificationfirewall.ui.BoltIcon
import com.anuj.notificationfirewall.ui.SearchIcon
import com.anuj.notificationfirewall.ui.StarIcon
import com.anuj.notificationfirewall.ui.StatusDot
import com.anuj.notificationfirewall.ui.TuneIcon
import com.anuj.notificationfirewall.ui.bucketColor
import com.anuj.notificationfirewall.ui.theme.LocalWallColors
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

private enum class InboxFilter(val label: String) {
    ALL("All"), RANG("Rang"), SILENCED("Silenced"), DROPPED("Dropped");

    fun matches(bucket: WallBucket): Boolean = when (this) {
        ALL -> true
        SILENCED -> bucket == WallBucket.SILENCE
        RANG -> bucket == WallBucket.RING
        DROPPED -> bucket == WallBucket.DROP
    }
}

@Composable
fun InboxScreen(nav: NavHostController) {
    val vm: InboxViewModel = hiltViewModel()
    val rows by vm.rows.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(InboxFilter.ALL) }
    var query by remember { mutableStateOf("") }
    var timeRange by remember { mutableStateOf(TimeRange.ALL) }
    var appFilter by remember { mutableStateOf<String?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var sheetRow by remember { mutableStateOf<InboxRow?>(null) }
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val c = LocalWallColors.current

    // Undo restores the exact pre-correction bias rather than applying the
    // opposite Correction: at the +-0.75 clamp a same-direction correction is
    // a no-op, but the opposite correction is never a no-op, so "undo via
    // inverse correction" would move a bias the original action never
    // touched. The snapshot is taken before vm.correct() runs.
    fun correctWithUndo(row: InboxRow, correction: Correction) {
        val label = row.displayName
        val verdict = if (correction == Correction.SHOULD_HAVE_BEEN_SILENT) "more harshly" else "more kindly"
        scope.launch {
            val previousBias = vm.biasBefore(row)
            vm.correct(row, correction)
            val result = snackbarHost.showSnackbar(
                message = "Noted — $label will be judged $verdict",
                actionLabel = "Undo",
            )
            if (result == SnackbarResult.ActionPerformed) {
                vm.restoreBias(row, previousBias)
            }
        }
    }

    val visible = remember(rows, filter, query, timeRange, appFilter) {
        val q = query.trim().lowercase(Locale.ROOT)
        val cutoff = timeRange.cutoffMs()
        rows.filter { row ->
            filter.matches(row.bucket) &&
                row.timestampMs >= cutoff &&
                (appFilter == null || row.appLabel == appFilter) &&
                (q.isBlank() ||
                    row.appLabel.contains(q, ignoreCase = true) ||
                    row.displayName.contains(q, ignoreCase = true) ||
                    (row.title?.contains(q, ignoreCase = true) == true) ||
                    (row.text?.contains(q, ignoreCase = true) == true))
        }
    }
    val apps = remember(rows) {
        rows.groupBy { it.appLabel }
            .mapValues { it.value.size }
            .toList()
            .sortedByDescending { it.second }
    }
    fun countFor(f: InboxFilter): Int = rows.count { f.matches(it.bucket) }

    Box(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SearchRow(
                    query = query,
                    onQueryChange = { query = it },
                    filtersActive = timeRange != TimeRange.ALL || appFilter != null,
                    onOpenFilters = { showFilters = true },
                )
            }
            item {
                FilterPills(
                    filter = filter,
                    onSelect = { filter = it },
                    countFor = ::countFor,
                )
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "CLASSIFICATION STREAM",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                        color = c.textMuted,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(color = c.bucketRang, size = 6.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("Live Triage", style = MaterialTheme.typography.labelMedium, color = c.bucketRang)
                    }
                }
            }
            items(visible, key = { it.id }) { row ->
                InboxCard(
                    row = row,
                    expanded = expandedId == row.id,
                    onTap = { expandedId = if (expandedId == row.id) null else row.id },
                    onLongPress = { sheetRow = row },
                    onSilence = { correctWithUndo(row, Correction.SHOULD_HAVE_BEEN_SILENT) },
                    onRing = { correctWithUndo(row, Correction.SHOULD_HAVE_RUNG) },
                    onAlwaysRing = { vm.addOverride(row, OverrideKind.VIP) },
                    onBlock = { vm.addOverride(row, OverrideKind.BLOCK) },
                )
            }
            item {
                if (visible.isEmpty()) {
                    Text(
                        if (rows.isEmpty()) "No notifications yet. When they arrive, they will be triaged here."
                        else "No matches for this filter.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textMuted,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    EndOfStream()
                }
            }
        }

        SnackbarHost(snackbarHost, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp))
    }

    sheetRow?.let { row ->
        InboxActionSheet(
            row = row,
            onDismiss = { sheetRow = null },
            onAlwaysRing = {
                vm.addOverride(row, OverrideKind.VIP)
                sheetRow = null
            },
            onBlock = {
                vm.addOverride(row, OverrideKind.BLOCK)
                sheetRow = null
            },
            onClearBias = {
                vm.clearBias(row)
                sheetRow = null
            },
        )
    }

    if (showFilters) {
        FilterDialog(
            timeRange = timeRange,
            onTimeRange = { timeRange = it },
            appFilter = appFilter,
            apps = apps,
            onApp = { appFilter = it },
            onClearAll = {
                timeRange = TimeRange.ALL
                appFilter = null
            },
            onDismiss = { showFilters = false },
        )
    }
}

/* ------------------------------------------------------------------ */
/* Header, search, filters                                             */
/* ------------------------------------------------------------------ */

/** Time-window presets for the filter sheet. Cutoffs are computed live. */
private enum class TimeRange(val label: String) {
    ALL("All time"),
    TODAY("Today"),
    SEVEN("Last 7 days"),
    THIRTY("Last 30 days");

    fun cutoffMs(nowMs: Long = System.currentTimeMillis()): Long = when (this) {
        ALL -> Long.MIN_VALUE
        TODAY -> LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        SEVEN -> nowMs - 7 * 24L * 60 * 60 * 1000
        THIRTY -> nowMs - 30 * 24L * 60 * 60 * 1000
    }
}

@Composable
private fun SearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    filtersActive: Boolean,
    onOpenFilters: () -> Unit,
) {
    val c = LocalWallColors.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .clip(CircleShape)
                .background(c.surface)
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchIcon(color = c.textMuted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        "Search notifications, apps...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (filtersActive) c.accentSoft else c.surface)
                .border(1.dp, if (filtersActive) c.accent else c.borderSubtle, CircleShape)
                .clickable(onClick = onOpenFilters),
            contentAlignment = Alignment.Center,
        ) {
            TuneIcon(Modifier.size(20.dp), track = c.textMuted, knob = c.text)
            if (filtersActive) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(9.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(c.accent),
                )
            }
        }
    }
}

@Composable
private fun FilterDialog(
    timeRange: TimeRange,
    onTimeRange: (TimeRange) -> Unit,
    appFilter: String?,
    apps: List<Pair<String, Int>>,
    onApp: (String?) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalWallColors.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(c.surface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Filter", style = MaterialTheme.typography.titleLarge, color = c.title)
                Spacer(Modifier.weight(1f))
                Text(
                    "Clear",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.accent,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onClearAll)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            Text(
                "TIME RANGE",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                color = c.textMuted,
            )
            TimeRange.entries.forEach { range ->
                FilterOptionRow(
                    label = range.label,
                    selected = timeRange == range,
                    onClick = { onTimeRange(range) },
                )
            }
            Text(
                "APP",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                color = c.textMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .height(220.dp),
            ) {
                item {
                    FilterOptionRow(
                        label = "All apps (${apps.sumOf { it.second }})",
                        selected = appFilter == null,
                        onClick = { onApp(null) },
                    )
                }
                items(apps, key = { it.first }) { (label, count) ->
                    FilterOptionRow(
                        label = "$label ($count)",
                        selected = appFilter == label,
                        onClick = { onApp(label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalWallColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) c.title else c.textMuted,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (selected) {
            Text("✓", style = MaterialTheme.typography.titleMedium, color = c.accent)
        }
    }
}

@Composable
private fun FilterPills(filter: InboxFilter, onSelect: (InboxFilter) -> Unit, countFor: (InboxFilter) -> Int) {
    val c = LocalWallColors.current
    val order = listOf(InboxFilter.ALL, InboxFilter.RANG, InboxFilter.SILENCED, InboxFilter.DROPPED)
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        order.forEach { f ->
            val selected = filter == f
            val dot = when (f) {
                InboxFilter.ALL -> null
                InboxFilter.RANG -> c.bucketRang
                InboxFilter.SILENCED -> c.bucketSilenced
                InboxFilter.DROPPED -> c.bucketDropped
            }
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(if (selected) c.accent else c.surface)
                    .clickable { onSelect(f) }
                    .padding(horizontal = 15.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (dot != null) {
                    StatusDot(color = if (selected) c.onAccent else dot, size = 7.dp)
                    Spacer(Modifier.width(7.dp))
                }
                Text(
                    f.label,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = if (selected) c.onAccent else c.textMuted,
                )
                Spacer(Modifier.width(7.dp))
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(
                            if (selected) c.onAccent.copy(alpha = 0.18f)
                            else c.background,
                        )
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        "${countFor(f)}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = if (selected) c.onAccent else c.textMuted,
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Stream cards                                                        */
/* ------------------------------------------------------------------ */

private data class OutcomePill(val text: String, val tint: Color)

@Composable
private fun outcomePill(row: InboxRow): OutcomePill {
    val c = LocalWallColors.current
    return when (row.bucket) {
        WallBucket.RING -> {
            val tag = when (row.source) {
                WallDecisionSource.VIP -> "VIP Contact"
                WallDecisionSource.OTP -> "OTP Fast Path"
                WallDecisionSource.CALL -> "Live Call"
                else -> row.importance?.let { "Importance %.1f".format(it) } ?: "Allowed"
            }
            OutcomePill("Rang • $tag", c.bucketRang)
        }
        WallBucket.SILENCE -> {
            val tag = when (row.source) {
                WallDecisionSource.PENDING -> "Re-judging soon"
                else -> row.category?.let { categoryLabel(it) } ?: "Held quietly"
            }
            OutcomePill("Silenced • $tag", c.bucketSilenced)
        }
        WallBucket.DROP -> OutcomePill("Dropped • Block list", c.bucketDropped)
    }
}

private fun categoryLabel(category: NotificationCategory): String =
    category.name.lowercase(Locale.ROOT).replace('_', ' ')
        .replaceFirstChar { it.uppercase(Locale.ROOT) }

private fun inboxTime(timestampMs: Long): String {
    val zone = ZoneId.systemDefault()
    val dt = Instant.ofEpochMilli(timestampMs).atZone(zone)
    return if (dt.toLocalDate() == LocalDate.now(zone)) {
        val h12 = when (val h = dt.hour % 12) {
            0 -> 12
            else -> h
        }
        "$h12:${dt.minute.toString().padStart(2, '0')} ${if (dt.hour < 12) "AM" else "PM"}"
    } else {
        "${dt.dayOfMonth} ${dt.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}"
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun InboxCard(
    row: InboxRow,
    expanded: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onSilence: () -> Unit,
    onRing: () -> Unit,
    onAlwaysRing: () -> Unit,
    onBlock: () -> Unit,
) {
    val c = LocalWallColors.current
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            // A swipe here is a judgement about the row, not a request to
            // remove it, so the row always springs back -- returning false is
            // what makes SwipeToDismissBox rebound instead of dismissing.
            when (target) {
                SwipeToDismissBoxValue.EndToStart -> onSilence()
                SwipeToDismissBoxValue.StartToEnd -> onRing()
                SwipeToDismissBoxValue.Settled -> {}
            }
            false
        },
    )
    val status = bucketColor(row.bucket)
    val pill = outcomePill(row)

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = { SwipeBackground(dismissState.dismissDirection) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(if (expanded) c.surfaceElevated else c.surface)
                .combinedClickable(onClick = onTap, onLongClick = onLongPress)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Outcome chip row on top, with the time.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(c.background)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (row.source == WallDecisionSource.OTP) {
                        BoltIcon(color = pill.tint, modifier = Modifier.size(12.dp))
                    } else {
                        StatusDot(color = pill.tint, size = 6.dp)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        pill.text,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = pill.tint,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(inboxTime(row.timestampMs), style = MaterialTheme.typography.labelMedium, color = c.textMuted)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(c.background),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        row.appLabel.firstOrNull()?.uppercase() ?: "•",
                        style = MaterialTheme.typography.titleMedium,
                        color = status,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            row.appLabel,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = c.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (row.displayName != row.appLabel) {
                            Text(" • ", style = MaterialTheme.typography.bodyMedium, color = c.textFaint)
                            Text(
                                row.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = c.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
                    val bodyText = row.title ?: row.text
                    Text(
                        bodyText ?: "Content expired",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (bodyText == null) c.textFaint else c.text,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (expanded) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(c.background)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TraceLine(
                        label = "Classification",
                        value = buildString {
                            append(row.category?.let { categoryLabel(it) } ?: "Uncategorised")
                            row.confidence?.let { append(" (%.0f%% conf.)".format(it * 100)) }
                        },
                        valueTint = c.bucketSilenced,
                    )
                    TraceLine(label = "Matched rule", value = row.explanation)
                    if (row.text != null && row.text != row.title) {
                        Text(row.text, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                    }
                    Text(
                        "importance ${row.importance?.let { "%.2f".format(it) } ?: "—"}" +
                            " · bias ${"%+.2f".format(row.biasApplied)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.textFaint,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(c.surfaceElevated)
                                .clickable(onClick = onAlwaysRing)
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            StarIcon(color = c.bucketRang, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Always Ring", style = MaterialTheme.typography.labelLarge, color = c.text)
                        }
                        Row(
                            Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(c.surfaceElevated)
                                .clickable(onClick = onBlock)
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            BlockIcon(color = c.bucketDropped, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Never Show", style = MaterialTheme.typography.labelLarge, color = c.text)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TraceLine(label: String, value: String, valueTint: Color? = null) {
    val c = LocalWallColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = valueTint ?: c.text,
        )
    }
}

@Composable
private fun EndOfStream() {
    val c = LocalWallColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(c.surface),
            contentAlignment = Alignment.Center,
        ) {
            Text("✓", style = MaterialTheme.typography.titleMedium, color = c.accent)
        }
        Text("Inbox in uninterrupted flow", style = MaterialTheme.typography.titleMedium, color = c.title)
        Text(
            "Zero unclassified notifications. Hush is guarding your attention.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.textMuted,
        )
    }
}

@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue?) {
    val c = LocalWallColors.current
    val (bg, label, alignment) = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> Triple(c.bucketRang, "Ring this", Alignment.CenterStart)
        SwipeToDismissBoxValue.EndToStart -> Triple(c.bucketSilenced, "Silence this", Alignment.CenterEnd)
        else -> Triple(c.surface, "", Alignment.Center)
    }
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .padding(horizontal = 20.dp),
        contentAlignment = alignment,
    ) {
        if (label.isNotEmpty()) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = c.title)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InboxActionSheet(
    row: InboxRow,
    onDismiss: () -> Unit,
    onAlwaysRing: () -> Unit,
    onBlock: () -> Unit,
    onClearBias: () -> Unit,
) {
    val c = LocalWallColors.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                row.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = c.title,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            SheetOption("Always ring this sender", onAlwaysRing)
            SheetOption("Block this sender", onBlock)
            SheetOption("Clear learned bias", onClearBias)
        }
    }
}

@Composable
private fun SheetOption(text: String, onClick: () -> Unit) {
    val c = LocalWallColors.current
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = c.text,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    )
}
