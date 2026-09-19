// ui/inbox/InboxScreen.kt
package com.anuj.notificationfirewall.ui.inbox

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.anuj.notificationfirewall.domain.wall.Correction
import com.anuj.notificationfirewall.domain.wall.OverrideKind
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.ui.NfChip
import com.anuj.notificationfirewall.ui.NfScreen
import com.anuj.notificationfirewall.ui.StatusDot
import com.anuj.notificationfirewall.ui.bucketColor
import com.anuj.notificationfirewall.ui.theme.LocalWallColors
import kotlinx.coroutines.launch

private enum class InboxFilter(val label: String) {
    ALL("All"), SILENCED("Silenced"), RANG("Rang"), DROPPED("Dropped");

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
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var sheetRow by remember { mutableStateOf<InboxRow?>(null) }
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Undo restores the exact pre-correction bias rather than applying the
    // opposite Correction: at the +-0.75 clamp a same-direction correction is
    // a no-op, but the opposite correction is never a no-op, so "undo via
    // inverse correction" would move a bias the original action never
    // touched. The snapshot is taken before vm.correct() runs.
    fun correctWithUndo(row: InboxRow, correction: Correction) {
        val label = row.sender ?: row.appLabel
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

    NfScreen(title = "Inbox") { modifier ->
        Box(modifier) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    InboxFilter.entries.forEach { f ->
                        NfChip(text = f.label, selected = filter == f, onClick = { filter = f })
                    }
                }

                val visible = rows.filter { filter.matches(it.bucket) }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(visible, key = { it.id }) { row ->
                        InboxRowItem(
                            row = row,
                            expanded = expandedId == row.id,
                            onTap = { expandedId = if (expandedId == row.id) null else row.id },
                            onLongPress = { sheetRow = row },
                            onSilence = { correctWithUndo(row, Correction.SHOULD_HAVE_BEEN_SILENT) },
                            onRing = { correctWithUndo(row, Correction.SHOULD_HAVE_RUNG) },
                        )
                    }
                }
            }

            SnackbarHost(snackbarHost, modifier = Modifier.align(Alignment.BottomCenter))
        }
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
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun InboxRowItem(
    row: InboxRow,
    expanded: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onSilence: () -> Unit,
    onRing: () -> Unit,
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

    Column {
        SwipeToDismissBox(
            state = dismissState,
            backgroundContent = { SwipeBackground(dismissState.dismissDirection) },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(c.background)
                    .combinedClickable(onClick = onTap, onLongClick = onLongPress)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(bucketColor(row.bucket))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            row.sender ?: row.appLabel,
                            style = MaterialTheme.typography.titleMedium,
                            color = c.text,
                        )
                        val bodyText = row.title ?: row.text
                        Text(
                            bodyText ?: "Content expired",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (bodyText == null) c.textFaint else c.textMuted,
                            maxLines = if (expanded) Int.MAX_VALUE else 1,
                        )
                    }
                    Text(
                        relativeTime(row.timestampMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = c.textMuted,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(row.explanation, style = MaterialTheme.typography.labelSmall, color = c.textFaint)

                if (expanded) {
                    Spacer(Modifier.height(8.dp))
                    if (row.text != null && row.text != row.title) {
                        Text(row.text, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(
                        "importance ${row.importance?.let { "%.2f".format(it) } ?: "—"}" +
                            " · bias ${"%+.2f".format(row.biasApplied)}" +
                            " · confidence ${row.confidence?.let { "%.0f%%".format(it * 100) } ?: "—"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.textFaint,
                    )
                }
            }
        }
        HorizontalDivider(color = c.borderSubtle)
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
            .clip(RoundedCornerShape(10.dp))
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
                row.sender ?: row.appLabel,
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

private fun relativeTime(timestampMs: Long): String = DateUtils.getRelativeTimeSpanString(
    timestampMs,
    System.currentTimeMillis(),
    DateUtils.MINUTE_IN_MILLIS,
).toString()
