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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.anuj.notificationfirewall.R
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.ui.theme.LocalWallColors
// NOTE(Task 2): NfBottomBar/NfNavItems below still read the raw Nf* constants
// from ui/theme/Color.kt rather than LocalWallColors. They are left untouched
// here because MainActivity.NfApp calls NfBottomBar directly and Task 2 is the
// task that rebuilds nav against the new four-tab structure -- migrating them
// now, ahead of that redesign, would be thrown away immediately and deleting
// them now would break `:app:assembleDebug`, which this task must keep green.
import com.anuj.notificationfirewall.ui.theme.NfAccentSoft
import com.anuj.notificationfirewall.ui.theme.NfBorder
import com.anuj.notificationfirewall.ui.theme.NfSurfaceElevated
import com.anuj.notificationfirewall.ui.theme.NfText
import com.anuj.notificationfirewall.ui.theme.NfTextMuted

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
    val c = LocalWallColors.current
    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
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
                Text(eyebrow, style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                Spacer(Modifier.height(2.dp))
            }
            Text(title, style = MaterialTheme.typography.headlineLarge, color = c.title)
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().weight(1f)) { content(Modifier.fillMaxSize()) }
    }
}

@Composable
private fun NfBackButton(onBack: () -> Unit) {
    val c = LocalWallColors.current
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(c.surface)
            .clickable(onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        Text("←", color = c.text, style = MaterialTheme.typography.titleLarge)
    }
}

/** A quietly-bordered surface panel. Padding is the caller's to set. */
@Composable
fun NfCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalWallColors.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.borderSubtle, shape),
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
    val c = LocalWallColors.current
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
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.text)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            }
        }
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = c.textMuted)
        }
        if (onClick != null) {
            Text("›", style = MaterialTheme.typography.titleLarge, color = c.textFaint)
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
    val c = LocalWallColors.current
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = c.textFaint,
        modifier = modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp),
    )
}

/** Primary (accent-filled) or secondary (bordered surface) action button. */
@Composable
fun NfButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
) {
    val c = LocalWallColors.current
    val shape = RoundedCornerShape(10.dp)
    val bg = when {
        !enabled -> c.surface
        primary -> c.accent
        else -> c.surface
    }
    val fg = when {
        !enabled -> c.textFaint
        primary -> c.title
        else -> c.text
    }
    Box(
        modifier
            .clip(shape)
            .background(bg)
            .then(if (!primary) Modifier.border(1.dp, c.border, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

/** Selectable pill (replaces Material FilterChip in the app's dark language). */
@Composable
fun NfChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalWallColors.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (selected) c.accentSoft else c.surface)
            .border(1.dp, if (selected) c.accent else c.border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) c.text else c.textMuted,
        )
    }
}

/** Maps a wall verdict to its semantic status colour. */
@Composable
fun bucketColor(bucket: WallBucket): Color {
    val c = LocalWallColors.current
    return when (bucket) {
        WallBucket.RING -> c.bucketRang
        WallBucket.SILENCE -> c.bucketSilenced
        WallBucket.DROP -> c.bucketDropped
    }
}

fun bucketLabel(bucket: WallBucket): String = when (bucket) {
    WallBucket.RING -> "Rang through"
    WallBucket.SILENCE -> "Silenced"
    WallBucket.DROP -> "Dropped"
}

data class NfNavItem(val route: String, val iconRes: Int, val label: String)

val NfNavItems = listOf(
    NfNavItem(Routes.HOME, R.drawable.ic_nav_home, "Home"),
    NfNavItem(Routes.SETTINGS, R.drawable.ic_nav_settings, "Settings"),
)

/** Floating pill navigation for the primary destinations. */
@Composable
fun NfBottomBar(currentRoute: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .navigationBarsPadding()
            .padding(bottom = 14.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(NfSurfaceElevated)
            .border(1.dp, NfBorder, RoundedCornerShape(26.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NfNavItems.forEach { item ->
            val selected = currentRoute == item.route
            Box(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (selected) NfAccentSoft else Color.Transparent)
                    .clickable { onSelect(item.route) }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(item.iconRes),
                    contentDescription = item.label,
                    tint = if (selected) NfText else NfTextMuted,
                    modifier = Modifier.size(21.dp),
                )
            }
        }
    }
}
