// ui/Components.kt
package com.anuj.notificationfirewall.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.anuj.notificationfirewall.domain.model.BucketAction
import com.anuj.notificationfirewall.ui.theme.NfAccent
import com.anuj.notificationfirewall.ui.theme.NfBackground
import com.anuj.notificationfirewall.ui.theme.NfBorderSubtle
import com.anuj.notificationfirewall.ui.theme.NfCaptured
import com.anuj.notificationfirewall.ui.theme.NfRang
import com.anuj.notificationfirewall.ui.theme.NfSilenced
import com.anuj.notificationfirewall.ui.theme.NfSurface
import com.anuj.notificationfirewall.ui.theme.NfText
import com.anuj.notificationfirewall.ui.theme.NfTextFaint
import com.anuj.notificationfirewall.ui.theme.NfTextMuted
import com.anuj.notificationfirewall.ui.theme.NfTitle

/**
 * Standard screen chrome: a small muted eyebrow over a big bold title, drawn
 * edge-to-edge below the status bar, then the screen's content. No Material app
 * bar — the title *is* the header.
 */
@Composable
fun NfScreen(
    title: String,
    eyebrow: String? = null,
    onBack: (() -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(NfBackground)
            .statusBarsPadding(),
    ) {
        if (onBack != null) {
            Box(Modifier.padding(start = 12.dp, top = 10.dp)) { NfBackButton(onBack) }
            Spacer(Modifier.height(6.dp))
        } else {
            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            if (eyebrow != null) {
                Text(eyebrow, style = MaterialTheme.typography.labelSmall, color = NfTextMuted)
                Spacer(Modifier.height(2.dp))
            }
            Text(title, style = MaterialTheme.typography.headlineLarge, color = NfTitle)
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().weight(1f)) { content(Modifier.fillMaxSize()) }
    }
}

@Composable
private fun NfBackButton(onBack: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(NfSurface)
            .clickable(onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        Text("←", color = NfText, style = MaterialTheme.typography.titleLarge)
    }
}

/** A quietly-bordered surface panel. Padding is the caller's to set. */
@Composable
fun NfCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(NfSurface)
            .border(1.dp, NfBorderSubtle, shape),
        content = content,
    )
}

/** A tappable list row: status dot · title (+ optional subtitle) · optional
 *  trailing value · chevron. The building block of the whole app. */
@Composable
fun NfRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    dotColor: Color? = null,
    trailing: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (dotColor != null) StatusDot(dotColor)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = NfText)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = NfTextMuted)
            }
        }
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = NfTextMuted)
        }
        if (onClick != null) {
            Text("›", style = MaterialTheme.typography.titleLarge, color = NfTextFaint)
        }
    }
}

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** Small muted section header, e.g. "Overview", "Manage". */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = NfTextFaint,
        modifier = modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp),
    )
}

fun bucketColor(bucket: BucketAction): Color = when (bucket) {
    BucketAction.LET_THROUGH_AS_IS, BucketAction.LET_THROUGH_CUSTOM_SOUND -> NfRang
    BucketAction.SILENCE -> NfSilenced
    BucketAction.CAPTURE -> NfCaptured
    BucketAction.ASK_AI -> NfAccent
}

fun bucketLabel(bucket: BucketAction): String = when (bucket) {
    BucketAction.LET_THROUGH_AS_IS -> "Let through"
    BucketAction.LET_THROUGH_CUSTOM_SOUND -> "Rang through"
    BucketAction.SILENCE -> "Silenced"
    BucketAction.CAPTURE -> "Captured"
    BucketAction.ASK_AI -> "Ask AI"
}
