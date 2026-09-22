// ui/wall/WallScreen.kt
//
// Hush "Wall" home screen. UI-only pass: the layout mirrors the Hush mock
// (health pill, armed-shield hero, outcome tri-cards, break-glass card,
// digest card) while every control still calls the existing ViewModel and
// navigation hooks -- no filtering logic lives here.
package com.anuj.notificationfirewall.ui.wall

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.anuj.notificationfirewall.ai.PersistedDigest
import com.anuj.notificationfirewall.domain.wall.WallBucket
import com.anuj.notificationfirewall.service.WallState
import com.anuj.notificationfirewall.ui.BoltIcon
import com.anuj.notificationfirewall.ui.HourglassIcon
import com.anuj.notificationfirewall.ui.Routes
import com.anuj.notificationfirewall.ui.StatusDot
import com.anuj.notificationfirewall.ui.TuneIcon
import com.anuj.notificationfirewall.ui.bucketColor
import com.anuj.notificationfirewall.ui.permissions.Permissions
import com.anuj.notificationfirewall.ui.theme.LocalWallColors
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun WallScreen(nav: NavHostController) {
    val vm: WallViewModel = hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose {}
    }

    // The countdown text is derived from a fixed timestamp, so nothing else
    // recomposes it as time passes -- tick refresh() every few seconds while
    // a window is open, purely so "N min left" counts down and the row
    // disappears on its own the moment the window (or the alarm's re-arm)
    // ends.
    LaunchedEffect(ui.breakGlassUntilMs != null) {
        while (ui.breakGlassUntilMs != null) {
            delay(5_000)
            vm.refresh()
        }
    }

    val c = LocalWallColors.current
    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding(),
    ) {
        HushHeader(armed = ui.state == WallState.ARMED)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                // Clear the floating pill nav.
                .padding(top = 14.dp, bottom = 112.dp),
        ) {
            HealthPill(state = ui.state)
            Spacer(Modifier.height(18.dp))

            val breakGlassUntilMs = ui.breakGlassUntilMs
            // Blocked states are checked first, ahead of the break-glass
            // countdown: a listener disconnect mid-window is exactly the
            // condition that will make the pending re-arm fail (see
            // BreakGlassController.finishExpiry), so it is more urgent than
            // a cheerful countdown and must not be hidden behind one.
            when {
                ui.state == WallState.BLOCKED_NO_LISTENER -> {
                    HushBlockedCard(
                        message = "Notification access is off",
                        buttonLabel = "Open notification access settings",
                        onFix = { context.startActivity(Permissions.notificationAccessIntent()) },
                    )
                }
                ui.state == WallState.BLOCKED_NO_POLICY_ACCESS -> {
                    HushBlockedCard(
                        message = "Do Not Disturb access is off",
                        buttonLabel = "Open Do Not Disturb access settings",
                        onFix = { context.startActivity(Permissions.dndAccessIntent()) },
                    )
                }
                breakGlassUntilMs != null -> {
                    HushBreakGlassHero(untilMs = breakGlassUntilMs, onReArmNow = vm::cancelBreakGlass)
                }
                else -> {
                    ArmedHero(
                        state = ui.state,
                        inspectedToday = ui.counts.total,
                        onToggle = vm::toggle,
                        onTune = { nav.navigate(Routes.SETTINGS) },
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
            OutcomesSection(counts = ui.counts)

            if (breakGlassUntilMs == null) {
                Spacer(Modifier.height(18.dp))
                HushBreakGlassCard(
                    configuredMinutes = ui.breakGlassDurationMinutes,
                    enabled = ui.state == WallState.ARMED || ui.state == WallState.DISARMED,
                    onActivate = vm::breakGlassFor,
                )
            }

            // Spec §5.1: "the most recent digest as a card." Nothing was
            // computed here -- ui.digest is exactly what DigestWorker
            // persisted after building the notification (see
            // WallViewModel.loadDigestIfFresh), so this card can never show
            // a different headline than the one the user was actually
            // notified with.
            ui.digest?.let { digest ->
                Spacer(Modifier.height(18.dp))
                HushDigestCard(digest = digest, onOpenInbox = { nav.navigate(Routes.INBOX) })
            }
        }
    }
}

/** Minutes remaining, rounded up so the row never shows "0 min left". */
private fun minutesLeft(untilMs: Long, nowMs: Long = System.currentTimeMillis()): Int =
    ((untilMs - nowMs).coerceAtLeast(0) + 59_999L).let { (it / 60_000L).toInt() }

/**
 * "Let everything through for N" label text, driven by the actual configured
 * duration rather than a hardcoded "1 hour" -- the default is 15 minutes
 * ([com.anuj.notificationfirewall.data.prefs.WallSettings]), so a fixed
 * "1 hour" label would promise something the button did not do out of the box.
 */
private fun formatBreakGlassDuration(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> if (minutes == 60) "1 hour" else "${minutes / 60} hours"
    else -> "${minutes / 60}h ${minutes % 60}min"
}

/* ------------------------------------------------------------------ */
/* Header                                                              */
/* ------------------------------------------------------------------ */

@Composable
private fun HushHeader(armed: Boolean) {
    val c = LocalWallColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "hush",
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = c.title,
        )
        Spacer(Modifier.width(6.dp))
        StatusDot(color = if (armed) c.armed else c.textFaint, size = 7.dp)
        Spacer(Modifier.weight(1f))
        // Avatar: accent disc with a generic person mark (drawn -- no icon font).
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(c.accent),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(22.dp)) {
                val dark = c.onAccent
                drawCircle(dark, radius = size.minDimension * 0.17f, center = Offset(size.width / 2, size.height * 0.36f))
                val shoulders = Path().apply {
                    moveTo(size.width * 0.22f, size.height * 0.86f)
                    cubicTo(
                        size.width * 0.22f, size.height * 0.60f,
                        size.width * 0.78f, size.height * 0.60f,
                        size.width * 0.78f, size.height * 0.86f,
                    )
                }
                drawPath(shoulders, dark, style = Stroke(width = size.minDimension * 0.13f, cap = StrokeCap.Round))
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Health pill                                                         */
/* ------------------------------------------------------------------ */

@Composable
private fun HealthPill(state: WallState) {
    val c = LocalWallColors.current
    val blocked = state == WallState.BLOCKED_NO_LISTENER || state == WallState.BLOCKED_NO_POLICY_ACCESS
    val armed = state == WallState.ARMED
    val title = when {
        blocked -> "Attention at risk"
        armed -> "DND & Notification Access Active"
        else -> "Wall Disarmed"
    }
    val subtitle = when {
        blocked -> "PERMISSION NEEDED • TAP CARD BELOW"
        armed -> "ATTENTION PROTECTED • FAIL-SAFE ENABLED"
        else -> "NOTIFICATIONS PASSING THROUGH"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(c.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(color = if (armed) c.armed else c.bucketSilenced, size = 8.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    letterSpacing = 1.2.sp,
                ),
                color = c.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            if (blocked || !armed) "!" else "✓",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = if (armed) c.armed else c.bucketSilenced,
        )
    }
}

/* ------------------------------------------------------------------ */
/* Armed hero                                                          */
/* ------------------------------------------------------------------ */

@Composable
private fun ArmedHero(
    state: WallState,
    inspectedToday: Int,
    onToggle: () -> Unit,
    onTune: () -> Unit,
) {
    val c = LocalWallColors.current
    val armed = state == WallState.ARMED
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(c.surfaceElevated, c.background)))
            .padding(horizontal = 20.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ShieldEmblem(armed = armed)
        Spacer(Modifier.height(12.dp))
        // State chip.
        Row(
            Modifier
                .clip(CircleShape)
                .background(c.borderSubtle)
                .padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(color = if (armed) c.armed else c.textFaint, size = 6.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                if (armed) "ARMED" else "DISARMED",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.8.sp),
                color = if (armed) c.armed else c.textMuted,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (armed) "The Wall is Standing" else "The Wall is Down",
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp),
            color = c.title,
        )
        Spacer(Modifier.height(6.dp))
        val subtext = buildAnnotatedString {
            withStyle(SpanStyle(color = c.textMuted)) {
                append(if (armed) "Filtering active. " else "Filtering paused. ")
            }
            withStyle(SpanStyle(color = c.accent, fontWeight = FontWeight.Medium)) {
                append("$inspectedToday notifications")
            }
            withStyle(SpanStyle(color = c.textMuted)) {
                append(if (armed) " inspected today with zero acoustic leaks." else " waiting while everything passes through.")
            }
        }
        Text(
            subtext,
            style = MaterialTheme.typography.bodyMedium,
        )
        HushWave()
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Primary arm/disarm control.
            Row(
                Modifier
                    .weight(1f)
                    .height(50.dp)
                    .clip(CircleShape)
                    .background(c.surfaceElevated)
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                StatusDot(
                    color = if (armed) c.textMuted else c.bucketSilenced,
                    size = 8.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (armed) "Disarm Wall" else "Arm Wall",
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
                    color = c.text,
                )
            }
            // Tune shortcut (opens Settings -- the wall's control room).
            Box(
                Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(c.surfaceElevated)
                    .clickable(onClick = onTune),
                contentAlignment = Alignment.Center,
            ) {
                TuneIcon(Modifier.size(20.dp), track = c.textMuted, knob = c.text)
            }
        }
    }
}

/** Dashed orbit ring, disc, shield outline and check -- all drawn, no icon font. */
@Composable
private fun ShieldEmblem(armed: Boolean) {
    val c = LocalWallColors.current
    Canvas(Modifier.size(88.dp)) {
        val w = size.width
        val dashed = PathEffect.dashPathEffect(floatArrayOf(9f, 9f), 0f)
        drawArc(
            color = c.accent.copy(alpha = 0.45f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = 2f, pathEffect = dashed),
        )
        drawCircle(color = c.surfaceElevated, radius = w * 0.36f)
        // Shield outline in a 100x100 box mapped onto the disc.
        val s = w * 0.72f / 100f
        val ox = w * 0.5f - 50f * s
        val oy = w * 0.5f - 50f * s
        fun px(x: Float) = ox + x * s
        fun py(y: Float) = oy + y * s
        val shield = Path().apply {
            moveTo(px(50f), py(20f))
            lineTo(px(76f), py(29f))
            lineTo(px(76f), py(52f))
            cubicTo(px(76f), py(70f), px(64f), py(82f), px(50f), py(88f))
            cubicTo(px(36f), py(82f), px(24f), py(70f), px(24f), py(52f))
            lineTo(px(24f), py(29f))
            close()
        }
        drawPath(
            shield,
            color = if (armed) c.armed else c.textFaint,
            style = Stroke(width = 3.5f, join = StrokeJoin.Round, cap = StrokeCap.Round),
        )
        val check = Path().apply {
            moveTo(px(41f), py(53f))
            lineTo(px(48f), py(60f))
            lineTo(px(60f), py(45f))
        }
        drawPath(
            check,
            color = if (armed) c.armed else c.textFaint,
            style = Stroke(width = 3.5f, join = StrokeJoin.Round, cap = StrokeCap.Round),
        )
        // Presence dot, bottom-right.
        drawCircle(color = c.background, radius = w * 0.11f, center = Offset(w * 0.76f, w * 0.78f))
        drawCircle(
            color = if (armed) c.armed else c.textFaint,
            radius = w * 0.075f,
            center = Offset(w * 0.76f, w * 0.78f),
        )
    }
}

/** Two overlapping attention waves; the front one drifts slowly. */
@Composable
private fun HushWave() {
    val c = LocalWallColors.current
    val transition = rememberInfiniteTransition(label = "hush-wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .padding(horizontal = 8.dp),
    ) {
        fun wavePath(phaseShift: Float, amplitude: Float): Path {
            val p = Path()
            val steps = 60
            for (i in 0..steps) {
                val x = size.width * i / steps
                val y = size.height / 2 +
                    amplitude * sin(2 * PI * 2 * i / steps + phaseShift).toFloat() * size.height / 2
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            return p
        }
        drawPath(wavePath(phase + PI.toFloat(), 0.55f), c.border, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
        drawPath(wavePath(phase, 0.42f), c.accent.copy(alpha = 0.85f), style = Stroke(width = 3f, cap = StrokeCap.Round))
    }
}

/* ------------------------------------------------------------------ */
/* Outcomes                                                            */
/* ------------------------------------------------------------------ */

@Composable
private fun OutcomesSection(counts: TodayCounts) {
    val c = LocalWallColors.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "TODAY'S OUTCOMES",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
            color = c.textMuted,
        )
        Spacer(Modifier.weight(1f))
        Text("Live Tally", style = MaterialTheme.typography.labelMedium, color = c.accent)
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutcomeCard(
            modifier = Modifier.weight(1f),
            count = counts.rang,
            label = "RANG",
            caption = "VIPs, OTPs",
            bucket = WallBucket.RING,
        )
        OutcomeCard(
            modifier = Modifier.weight(1f),
            count = counts.silenced,
            label = "SILENCED",
            caption = "Held quietly",
            bucket = WallBucket.SILENCE,
        )
        OutcomeCard(
            modifier = Modifier.weight(1f),
            count = counts.dropped,
            label = "DROPPED",
            caption = "Blacklisted",
            bucket = WallBucket.DROP,
        )
    }
    if (counts.total > 0) {
        Spacer(Modifier.height(10.dp))
        // OTP fast-path note. The exact OTP count is not tracked separately
        // today, so the row states the capability rather than a number.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(c.surface)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(c.accent.copy(alpha = 0.16f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text("OTP", style = MaterialTheme.typography.labelSmall, color = c.accent)
            }
            Spacer(Modifier.width(9.dp))
            Text(
                "Local OTP bypass handled instantly",
                style = MaterialTheme.typography.bodyMedium,
                color = c.text,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BoltIcon(color = c.textMuted, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun OutcomeCard(count: Int, label: String, caption: String, bucket: WallBucket, modifier: Modifier = Modifier) {
    val c = LocalWallColors.current
    val status = bucketColor(bucket)
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(c.surface)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color = status, size = 9.dp)
            Spacer(Modifier.weight(1f))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.8.sp),
                color = status,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(count.toString(), style = MaterialTheme.typography.headlineMedium, color = c.title)
        Text(
            caption,
            style = MaterialTheme.typography.bodySmall,
            color = c.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/* ------------------------------------------------------------------ */
/* Break-glass                                                         */
/* ------------------------------------------------------------------ */

private val BreakGlassPresets = listOf(15, 30, 60)
private val BreakGlassPresetLabels = listOf("15m", "30m", "1h")

@Composable
private fun HushBreakGlassCard(configuredMinutes: Int, enabled: Boolean, onActivate: (Int) -> Unit) {
    val c = LocalWallColors.current
    // Preselect the chip nearest the configured default; tapping a chip
    // starts a window for exactly that duration (see breakGlassFor).
    // No "Custom" chip: a free-form duration would need a picker we don't
    // have, so it was dropped rather than shipped as a dead control.
    var chip by rememberSaveable {
        mutableIntStateOf(
            when (configuredMinutes) {
                15 -> 0
                30 -> 1
                60 -> 2
                else -> 0
            },
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(c.surface)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(c.bucketSilenced.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                HourglassIcon(color = c.bucketSilenced, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Break-Glass Pass-through", style = MaterialTheme.typography.titleMedium, color = c.title)
                Text(
                    "Allow all calls & alerts if awaiting urgent arrival",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textMuted,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(c.background)
                .padding(4.dp),
        ) {
            BreakGlassPresetLabels.forEachIndexed { i, label ->
                val selected = i == chip
                Box(
                    Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(if (selected) c.accent else Color.Transparent)
                        .clickable { chip = i }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = if (selected) c.onAccent else c.textMuted,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(CircleShape)
                .background(if (enabled) c.accent else c.surfaceElevated)
                .clickable(enabled = enabled, onClick = { onActivate(BreakGlassPresets[chip]) })
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            BoltIcon(
                color = if (enabled) c.onAccent else c.textFaint,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Activate Break-Glass (${BreakGlassPresetLabels[chip]})",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = if (enabled) c.onAccent else c.textFaint,
            )
        }
    }
}

@Composable
private fun HushBreakGlassHero(untilMs: Long, onReArmNow: () -> Unit) {
    val c = LocalWallColors.current
    val minutes = minutesLeft(untilMs)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(c.surface)
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color = c.bucketSilenced, size = 8.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                "Everything is getting through · $minutes min left",
                style = MaterialTheme.typography.titleMedium,
                color = c.title,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Break-glass is open. The wall re-arms itself automatically.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.textMuted,
        )
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(CircleShape)
                .background(c.accent)
                .clickable(onClick = onReArmNow)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                "Re-arm now",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = c.onAccent,
            )
        }
    }
}

@Composable
private fun HushBlockedCard(message: String, buttonLabel: String, onFix: () -> Unit) {
    val c = LocalWallColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(c.surface)
            .padding(22.dp),
    ) {
        Text(message, style = MaterialTheme.typography.titleLarge, color = c.title)
        Spacer(Modifier.height(6.dp))
        Text(
            "The wall can't protect you until this is granted.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.textMuted,
        )
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(CircleShape)
                .background(c.accent)
                .clickable(onClick = onFix)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                buttonLabel,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = c.onAccent,
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* Digest                                                              */
/* ------------------------------------------------------------------ */

@Composable
private fun HushDigestCard(digest: PersistedDigest, onOpenInbox: () -> Unit) {
    val c = LocalWallColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(c.surface)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BoltIcon(color = c.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
            Text("Daily Digest", style = MaterialTheme.typography.titleMedium, color = c.title)
            Spacer(Modifier.weight(1f))
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(c.background)
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(color = c.accent, size = 5.dp)
                Spacer(Modifier.width(5.dp))
                Text(
                    "ON-DEVICE",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 1.sp),
                    color = c.accent,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(digest.headline, style = MaterialTheme.typography.bodyMedium, color = c.text)
        if (digest.worthALook.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "WORTH A LOOK",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 1.4.sp),
                color = c.textMuted,
            )
            Spacer(Modifier.height(8.dp))
            digest.worthALook.take(2).forEach { line ->
                DigestPreviewRow(line = line)
                Spacer(Modifier.height(8.dp))
            }
        }
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onOpenInbox)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Review full history in Inbox", style = MaterialTheme.typography.labelLarge, color = c.accent)
            Spacer(Modifier.width(6.dp))
            Text("→", style = MaterialTheme.typography.labelLarge, color = c.accent)
        }
    }
}

/** A "Sender: message" line rendered as an avatar-initial row. */
@Composable
private fun DigestPreviewRow(line: String) {
    val c = LocalWallColors.current
    val sender = line.substringBefore(": ", missingDelimiterValue = "").ifBlank { line }
    val message = if (line.contains(": ")) line.substringAfter(": ") else ""
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.surfaceElevated)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(c.background),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                sender.firstOrNull()?.uppercase() ?: "•",
                style = MaterialTheme.typography.titleMedium,
                color = c.bucketRang,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                sender,
                style = MaterialTheme.typography.labelLarge,
                color = c.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (message.isNotEmpty()) {
                Text(
                    "\"$message\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Status copy (covered by WallHomeContentTest -- keep strings stable)  */
/* ------------------------------------------------------------------ */

internal data class WallStatusContent(
    val label: String,
    val summary: String,
    val actionHint: String,
)

internal fun wallStatusContent(state: WallState): WallStatusContent = if (state == WallState.ARMED) {
    WallStatusContent(
        label = "ARMED",
        summary = "Protection is active",
        actionHint = "Tap to let notifications through",
    )
} else {
    WallStatusContent(
        label = "DISARMED",
        summary = "Notifications pass through",
        actionHint = "Tap to arm the wall",
    )
}
