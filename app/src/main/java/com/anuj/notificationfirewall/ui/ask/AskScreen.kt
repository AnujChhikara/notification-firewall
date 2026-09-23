// ui/ask/AskScreen.kt
//
// Ask: a chat over the notification history. The on-device stats that used to
// live above the chat now have their own Insights screen; this one is just
// the conversation, answered by the agentic loop (AskService.askDeep).
package com.anuj.notificationfirewall.ui.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.anuj.notificationfirewall.ui.NfButton
import com.anuj.notificationfirewall.ui.Routes
import com.anuj.notificationfirewall.ui.SearchIcon
import com.anuj.notificationfirewall.ui.SparkleIcon
import com.anuj.notificationfirewall.ui.StatusDot
import com.anuj.notificationfirewall.ui.theme.LocalWallColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
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

    LaunchedEffect(ui.messages.size, ui.sending) {
        if (ui.messages.isNotEmpty()) {
            val lastItem = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
            listState.scrollToItem(lastItem)
        }
    }

    fun send() {
        if (!ui.hasKey) {
            nav.navigate(Routes.KEYS)
        } else {
            vm.send(draft)
            draft = ""
        }
    }

    val c = LocalWallColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        // Slim header.
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 14.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Ask", style = MaterialTheme.typography.headlineLarge, color = c.title)
            Text(
                "Chat with your notification history.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textMuted,
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            if (!ui.hasKey) {
                item { NoKeyCard(onOpenSettings = { nav.navigate(Routes.KEYS) }) }
            }
            items(ui.messages, key = { it.atMs.toString() + it.text.hashCode() }) { message ->
                MessageTurn(message)
            }
            if (ui.sending) item {
                Text("Thinking…", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        // Composer: suggestions (until the first answer), opt-in, input row.
        // The bottom clearance follows the keyboard: a fixed 100dp would hover
        // far above an open keyboard, while nothing would leave the composer
        // hidden behind the floating nav pill when it is closed.
        val imeVisible = WindowInsets.isImeVisible
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = if (imeVisible) 12.dp else 100.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ui.hasKey && ui.messages.isEmpty()) {
                SuggestionChips(onPick = { draft = it })
            }
            if (ui.hasKey) {
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(if (ui.allowContent) c.accentSoft else c.surface)
                        .border(1.dp, if (ui.allowContent) c.accent else c.borderSubtle, CircleShape)
                        .clickable { vm.setAllowContent(!ui.allowContent) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (ui.allowContent) "Including message content in this answer"
                        else "Include message content in this answer",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (ui.allowContent) c.text else c.textMuted,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(c.surface)
                        .border(1.dp, c.borderSubtle, RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SearchIcon(color = c.textFaint, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(9.dp))
                    Box(Modifier.weight(1f)) {
                        if (draft.isEmpty()) {
                            Text(
                                "Ask about your notifications…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = c.textFaint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BasicTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.text),
                            cursorBrush = SolidColor(c.accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                val canSend = draft.isNotBlank() && !ui.sending
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (canSend || !ui.hasKey) c.accent else c.surface)
                        .clickable(enabled = canSend || !ui.hasKey, onClick = ::send),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "→",
                        style = MaterialTheme.typography.titleLarge,
                        color = if (canSend || !ui.hasKey) c.onAccent else c.textFaint,
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Conversation                                                        */
/* ------------------------------------------------------------------ */

private val Suggestions = listOf(
    "Which apps interrupted me most last week?",
    "Show quieted delivery notifications",
    "Break down bot vs human pings",
)

@Composable
private fun SuggestionChips(onPick: (String) -> Unit) {
    val c = LocalWallColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Suggestions.forEach { question ->
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(c.surface)
                    .border(1.dp, c.borderSubtle, CircleShape)
                    .clickable { onPick(question) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(color = c.accent, size = 6.dp)
                Spacer(Modifier.width(7.dp))
                Text(
                    question,
                    style = MaterialTheme.typography.labelMedium,
                    color = c.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun formatMessageTime(atMs: Long): String {
    val zone = ZoneId.systemDefault()
    val dt = Instant.ofEpochMilli(atMs).atZone(zone)
    val time = String.format(Locale.ROOT, "%02d:%02d", dt.hour, dt.minute)
    return if (dt.toLocalDate() == LocalDate.now(zone)) {
        "Today, $time"
    } else {
        "${dt.dayOfMonth} ${dt.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}, $time"
    }
}

@Composable
private fun NoKeyCard(onOpenSettings: () -> Unit) {
    val c = LocalWallColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(c.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Chat needs an OpenAI key", style = MaterialTheme.typography.titleMedium, color = c.title)
        Text(
            "Asking questions in words works like this: the model writes the " +
                "query, your phone runs it, and only the counts come back. " +
                "Meanwhile your Insights stay available with no key at all.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.textMuted,
        )
        NfButton("Add a key in Settings", onClick = onOpenSettings, primary = false)
    }
}

@Composable
private fun MessageTurn(message: AskMessage) {
    val c = LocalWallColors.current
    if (message.fromUser) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .fillMaxWidth(0.85f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(c.accentSoft)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(message.text, style = MaterialTheme.typography.bodyMedium, color = c.text)
            }
        }
        return
    }
    var showQueries by remember(message) { mutableStateOf(false) }
    // The agent's full trail, oldest first; falls back to the single query
    // the one-shot path stored.
    val trail = if (message.queries.isNotEmpty()) message.queries
    else listOfNotNull(message.sql)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(c.surface)
            .border(if (message.refused) 1.dp else 0.dp, if (message.refused) c.danger else Color.Transparent, RoundedCornerShape(24.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(c.accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                SparkleIcon(color = c.accent, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(9.dp))
            Text(
                "Hush Assistant",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                color = c.accent,
            )
            Spacer(Modifier.weight(1f))
            Text(formatMessageTime(message.atMs), style = MaterialTheme.typography.labelMedium, color = c.textMuted)
        }
        Text(
            message.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (message.refused) c.danger else c.text,
        )
        if (trail.isNotEmpty()) {
            Text(
                if (showQueries) "Hide queries" else "Show ${if (trail.size > 1) "${trail.size} queries" else "query"}",
                style = MaterialTheme.typography.labelMedium,
                color = c.textMuted,
                modifier = Modifier.clickable { showQueries = !showQueries },
            )
            if (showQueries) {
                trail.forEachIndexed { i, sql ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (trail.size > 1) {
                            Text(
                                "Query ${i + 1}",
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                                color = c.textFaint,
                            )
                        }
                        Text(
                            sql,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = c.textMuted,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(c.background)
                                .padding(10.dp),
                        )
                    }
                }
            }
        }
    }
}
