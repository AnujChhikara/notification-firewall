// ui/insights/InsightCards.kt
//
// The on-device insight cards: plain typed queries over the last 7 days, no
// API key and no network. Shared by the Insights screen; the numbers come
// from AskViewModel's stats.
package com.anuj.notificationfirewall.ui.insights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anuj.notificationfirewall.ui.ShieldIcon
import com.anuj.notificationfirewall.ui.ClockIcon
import com.anuj.notificationfirewall.ui.StatusDot
import com.anuj.notificationfirewall.ui.ask.AskStats
import com.anuj.notificationfirewall.ui.ask.DaySplit
import com.anuj.notificationfirewall.ui.theme.LocalWallColors
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max

@Composable
fun HushInsightCard(content: @Composable () -> Unit) {
    val c = LocalWallColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(c.surface)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = { content() },
    )
}

@Composable
fun RigorCard(stats: AskStats) {
    val c = LocalWallColors.current
    val ratio = stats.noiseRatioPercent
    HushInsightCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "FIREWALL RIGOR",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                    color = c.textMuted,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ratio != null) {
                        Text(
                            "$ratio%",
                            style = MaterialTheme.typography.headlineLarge.copy(fontSize = 42.sp),
                            color = c.title,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Noise Shielded", style = MaterialTheme.typography.titleMedium, color = c.bucketRang)
                    } else {
                        // A bare "—" renders as a stray bar at display size; say
                        // plainly that there is nothing to measure yet instead.
                        Text("No arrivals yet", style = MaterialTheme.typography.headlineMedium, color = c.title)
                    }
                }
            }
            Box(
                Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(c.surfaceElevated),
                contentAlignment = Alignment.Center,
            ) {
                ShieldIcon(color = c.accent, modifier = Modifier.size(24.dp))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(CircleShape)
                    .background(c.background),
            ) {
                if (ratio != null) {
                    Box(
                        Modifier
                            .fillMaxWidth((ratio / 100f).coerceIn(0.02f, 1f))
                            .height(10.dp)
                            .clip(CircleShape)
                            .background(c.accent),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${stats.keptQuiet} quieted", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
                Text("${stats.total} total pings", style = MaterialTheme.typography.labelMedium, color = c.text)
            }
        }
        val correction = stats.correctionRatePercent
        Text(
            if (stats.judgedByThisApp == 0) "No judgements yet — no accuracy to report."
            else "Correction rate ${correction ?: "—"}% · ${stats.corrections} corrections across ${stats.judgedByThisApp} judgements.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.textMuted,
        )
    }
}

fun peakDayName(weekly: List<DaySplit>): String {
    if (weekly.isEmpty()) return "—"
    val peakTotal = weekly.maxOfOrNull { it.total } ?: 0
    val idx = weekly.indexOfFirst { it.total == peakTotal }.coerceAtLeast(0)
    val date = LocalDate.now(ZoneId.systemDefault()).minusDays((weekly.size - 1 - idx).toLong())
    return date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
}

@Composable
fun WeeklyCard(stats: AskStats) {
    val c = LocalWallColors.current
    val weekly = stats.weekly
    val peakTotal = weekly.maxOfOrNull { it.total } ?: 0
    val maxTotal = max(1, peakTotal)
    val peakIdx = weekly.indexOfFirst { it.total == peakTotal }.coerceAtLeast(0)
    HushInsightCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(color = c.accent, size = 7.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "TREND ANALYSIS",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                        color = c.accent,
                    )
                }
                Text("Weekly Attention Shielded", style = MaterialTheme.typography.titleLarge, color = c.title)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color = c.bucketRang, size = 7.dp)
                Spacer(Modifier.width(5.dp))
                Text("Allowed", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
                Spacer(Modifier.width(10.dp))
                StatusDot(color = c.accent, size = 7.dp)
                Spacer(Modifier.width(5.dp))
                Text("Silenced", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
            }
        }
        if (weekly.isEmpty() || peakTotal <= 0) {
            Text("Nothing has arrived in the last 7 days.", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                weekly.forEachIndexed { i, day ->
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        Text(
                            if (day.total > 0) "${day.total}" else "",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = c.textMuted,
                        )
                        Spacer(Modifier.height(3.dp))
                        // Track with the quieted share on top and the allowed share below.
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height((20 + 100 * day.total / maxTotal).dp)
                                .clip(CircleShape)
                                .background(c.background)
                                .padding(2.dp),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                if (day.quiet > 0) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .weight(day.quiet.toFloat())
                                            .clip(CircleShape)
                                            .background(c.accent),
                                    )
                                }
                                if (day.rang > 0) {
                                    if (day.quiet > 0) Spacer(Modifier.height(2.dp))
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .weight(day.rang.toFloat())
                                            .clip(CircleShape)
                                            .background(c.bucketRang),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            day.dayLabel,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (i == peakIdx) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                            color = if (i == peakIdx) c.accent else c.textMuted,
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "Avg ${stats.noiseRatioPercent?.let { "$it%" } ?: "—"} intercepted",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.textMuted,
                )
                Text(
                    "Peak ${peakDayName(weekly)}",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = c.accent,
                )
            }
        }
    }
}

@Composable
fun FocusCard(stats: AskStats) {
    val c = LocalWallColors.current
    val phases = stats.phases
    val arrivals = stats.byHour.sum()
    val peak = max(1, phases.maxOfOrNull { it.count } ?: 1)
    HushInsightCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Focus Windows", style = MaterialTheme.typography.titleLarge, color = c.title)
                Text("Interceptions across daily phases", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            }
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.background),
                contentAlignment = Alignment.Center,
            ) {
                ClockIcon(color = c.accent, modifier = Modifier.size(19.dp))
            }
        }
        if (arrivals == 0) {
            Text("Nothing has arrived in the last 7 days.", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        } else {
            phases.forEach { phase ->
                val share = (phase.count * 100f / arrivals).toInt()
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${phase.name} (${phase.range})", style = MaterialTheme.typography.labelLarge, color = c.title)
                        Text("${phase.count} pings · $share%", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(c.background),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth((phase.count / peak.toFloat()).coerceIn(0.02f, 1f))
                                .height(8.dp)
                                .clip(CircleShape)
                                .background(c.accent),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
fun SourceCard(stats: AskStats) {
    val c = LocalWallColors.current
    val judged = stats.judged
    val humans = stats.fromHuman.coerceAtMost(max(1, judged)).let { if (judged == 0) 0 else it }
    val bots = (judged - humans).coerceAtLeast(0)
    val botsFrac = if (judged == 0) 0f else bots / judged.toFloat()
    val botsPct = (botsFrac * 100).toInt()
    HushInsightCard {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Source Origin", style = MaterialTheme.typography.titleLarge, color = c.title)
            Text("Real people vs algorithmic alerts", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(84.dp)) {
                    val w = size.minDimension * 0.16f
                    drawArc(c.background, -90f, 360f, false, style = Stroke(width = w))
                    if (botsFrac > 0f) {
                        drawArc(c.accent, -90f, 360f * botsFrac, false, style = Stroke(width = w))
                    }
                    if (botsFrac < 1f && judged > 0) {
                        drawArc(c.bucketRang, -90f + 360f * botsFrac, 360f * (1f - botsFrac), false, style = Stroke(width = w))
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$botsPct%", style = MaterialTheme.typography.titleMedium, color = c.title)
                    Text(
                        "BOTS",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 1.sp),
                        color = c.textMuted,
                    )
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(color = c.accent, size = 8.dp)
                        Spacer(Modifier.width(7.dp))
                        Text("Automated & Bots", style = MaterialTheme.typography.labelLarge, color = c.title)
                    }
                    Text("$bots", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(color = c.bucketRang, size = 8.dp)
                        Spacer(Modifier.width(7.dp))
                        Text("Direct Humans", style = MaterialTheme.typography.labelLarge, color = c.title)
                    }
                    Text("$humans", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = c.bucketRang)
                }
            }
        }
        Text(
            if (judged == 0) "Nothing judged yet, so there is no share to report."
            else "Automated apps triggered $botsPct% of judged pings, with ${stats.noiseRatioPercent?.let { "$it%" } ?: "—"} of all arrivals kept quiet.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.textMuted,
        )
    }
}

@Composable
fun TopAppsCard(stats: AskStats) {
    val c = LocalWallColors.current
    val tints = listOf(c.accent, c.bucketSilenced, c.bucketDropped)
    HushInsightCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Top Quieted Apps", style = MaterialTheme.typography.titleLarge, color = c.title)
            Text("Past 7 days", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
        }
        if (stats.topOffenders.isEmpty()) {
            Text("Nothing has been silenced yet.", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        } else {
            stats.topOffenders.take(3).forEachIndexed { i, app ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(c.background),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            app.appLabel.firstOrNull()?.uppercase() ?: "•",
                            style = MaterialTheme.typography.titleMedium,
                            color = tints[i % tints.size],
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        app.appLabel,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                        color = c.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${app.count}", style = MaterialTheme.typography.titleMedium, color = c.title)
                }
                if (i < 2 && stats.topOffenders.size > i + 1) Spacer(Modifier.height(6.dp))
            }
        }
    }
}
