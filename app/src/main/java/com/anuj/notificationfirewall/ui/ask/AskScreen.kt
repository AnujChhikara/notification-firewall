// ui/ask/AskScreen.kt
package com.anuj.notificationfirewall.ui.ask

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.anuj.notificationfirewall.ui.NfButton
import com.anuj.notificationfirewall.ui.NfCard
import com.anuj.notificationfirewall.ui.NfChip
import com.anuj.notificationfirewall.ui.NfScreen
import com.anuj.notificationfirewall.ui.Routes
import com.anuj.notificationfirewall.ui.SectionLabel
import com.anuj.notificationfirewall.ui.theme.LocalWallColors
import kotlin.math.max

/**
 * Ask: what the wall knows about itself, then a chat box over the same data.
 *
 * The stat cards need no API key and no network — they are plain typed queries
 * — so they render whether or not the chat below them is usable.
 */
@Composable
fun AskScreen(nav: NavHostController) {
    val vm: AskViewModel = hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose {}
    }

    LaunchedEffect(ui.messages.size) {
        if (ui.messages.isNotEmpty()) listState.animateScrollToItem(ui.messages.size)
    }

    NfScreen(title = "Ask", eyebrow = "Your history, answered on this phone") { modifier ->
        Column(modifier.imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { StatCards(ui.stats) }
                item { SectionLabel("Chat") }

                if (!ui.hasKey) {
                    item { NoKeyCard(onOpenSettings = { nav.navigate(Routes.KEYS) }) }
                } else if (ui.messages.isEmpty()) {
                    item { EmptyChatHint() }
                }

                itemsIndexed(ui.messages) { _, message -> MessageBubble(message) }

                if (ui.sending) item { ThinkingRow() }
                item { Spacer(Modifier.height(96.dp)) }
            }

            if (ui.hasKey) {
                Composer(
                    draft = draft,
                    onDraftChange = { draft = it },
                    allowContent = ui.allowContent,
                    onAllowContentChange = vm::setAllowContent,
                    sending = ui.sending,
                    onSend = {
                        vm.send(draft)
                        draft = ""
                    },
                )
            }
        }
    }
}

// --- Stat cards -------------------------------------------------------------

@Composable
private fun StatCards(stats: AskStats) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Last 7 days")

        StatCard(
            label = "Noise ratio",
            value = stats.noiseRatioPercent?.let { "$it%" } ?: "—",
            caption = if (stats.total == 0) {
                "Nothing has arrived yet."
            } else {
                "${stats.keptQuiet} of ${stats.total} never reached your screen."
            },
        )

        NfCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardLabel("Top offenders")
                if (stats.topOffenders.isEmpty()) {
                    CardCaption("Nothing has been silenced yet.")
                } else {
                    val worst = max(1, stats.topOffenders.first().count)
                    stats.topOffenders.forEach { app ->
                        OffenderRow(app.appLabel, app.count, app.count / worst.toFloat())
                    }
                }
            }
        }

        StatCard(
            label = "Machines vs humans",
            value = stats.humanSharePercent?.let { "$it%" } ?: "—",
            caption = if (stats.judged == 0) {
                "Nothing judged yet, so there is no share to report."
            } else {
                "${stats.fromHuman} of ${stats.judged} judged notifications were written by a person."
            },
        )

        NfCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CardLabel("By hour")
                HourBars(stats.byHour)
                CardCaption("When notifications arrive, midnight to midnight.")
            }
        }

        StatCard(
            label = "Correction rate",
            value = stats.correctionRatePercent?.let { "$it%" } ?: "—",
            caption = if (stats.judgedByThisApp == 0) {
                "Nothing judged yet — no accuracy to report."
            } else {
                "${stats.corrections} corrections across ${stats.judgedByThisApp} judgements. " +
                    "The lower this is, the more the numbers above are worth."
            },
        )
    }
}

@Composable
private fun StatCard(label: String, value: String, caption: String) {
    NfCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CardLabel(label)
            Text(
                value,
                style = MaterialTheme.typography.headlineLarge,
                color = LocalWallColors.current.title,
            )
            CardCaption(caption)
        }
    }
}

@Composable
private fun CardLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = LocalWallColors.current.textMuted)
}

@Composable
private fun CardCaption(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = LocalWallColors.current.textMuted)
}

@Composable
private fun OffenderRow(label: String, count: Int, fraction: Float) {
    val c = LocalWallColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = c.text,
            modifier = Modifier.width(110.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(c.borderSubtle),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(c.bucketSilenced),
            )
        }
        Text("$count", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
    }
}

@Composable
private fun HourBars(byHour: List<Int>) {
    val c = LocalWallColors.current
    val peak = max(1, byHour.maxOrNull() ?: 1)
    Row(
        Modifier.fillMaxWidth().height(48.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        byHour.forEach { count ->
            Box(
                Modifier
                    .weight(1f)
                    .height((6 + 42 * count / peak).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (count == 0) c.borderSubtle else c.accent),
            )
        }
    }
}

// --- Chat -------------------------------------------------------------------

@Composable
private fun NoKeyCard(onOpenSettings: () -> Unit) {
    NfCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardLabel("Chat needs an OpenAI key")
            CardCaption(
                "The numbers above are computed on this phone and need no key. " +
                    "Asking questions in words does: the model writes the query, " +
                    "your phone runs it, and only the counts come back.",
            )
            NfButton("Add a key in Settings", onClick = onOpenSettings, primary = false)
        }
    }
}

@Composable
private fun EmptyChatHint() {
    NfCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CardCaption("Try: “who interrupted me most last week?”")
            CardCaption("Your notifications stay here. Only the question, the table names and the counts are sent.")
        }
    }
}

@Composable
private fun MessageBubble(message: AskMessage) {
    val c = LocalWallColors.current
    var showSql by remember(message) { mutableStateOf(false) }

    Row(horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start) {
        if (message.fromUser) Spacer(Modifier.weight(0.15f))
        Column(
            Modifier
                .weight(0.85f)
                .clip(RoundedCornerShape(14.dp))
                .background(if (message.fromUser) c.accentSoft else c.surface)
                .border(
                    1.dp,
                    if (message.refused) c.danger else c.borderSubtle,
                    RoundedCornerShape(14.dp),
                )
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (message.refused) c.danger else c.text,
            )
            if (message.sql != null) {
                Text(
                    if (showSql) "Hide query" else "Show query",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.textMuted,
                    modifier = Modifier.clickable { showSql = !showSql },
                )
                if (showSql) {
                    Text(
                        message.sql,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = c.textMuted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(c.background)
                            .padding(10.dp),
                    )
                }
            }
        }
        if (!message.fromUser) Spacer(Modifier.weight(0.15f))
    }
}

@Composable
private fun ThinkingRow() {
    CardCaption("Thinking…")
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    allowContent: Boolean,
    onAllowContentChange: (Boolean) -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
) {
    val c = LocalWallColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 86.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NfChip(
            text = if (allowContent) "Including message content" else "Include message content in this answer",
            selected = allowContent,
            onClick = { onAllowContentChange(!allowContent) },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.surface)
                    .border(1.dp, c.border, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (draft.isEmpty()) {
                    Text(
                        "Ask about your notifications…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textFaint,
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    singleLine = false,
                    maxLines = 4,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            NfButton(
                text = "Ask",
                onClick = onSend,
                enabled = !sending && draft.isNotBlank(),
            )
        }
    }
}
