package com.anuj.notificationfirewall.ui.theme

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Every colour the app is allowed to use, named by role rather than by hue.
 *
 * Components read these and nothing else. The previous build imported raw
 * `Color` constants directly into composables, which is what made it
 * structurally dark-only — a light palette had nowhere to attach. Naming by
 * role means a component says what it means ("this is a border") and the
 * palette decides what that looks like.
 */
data class WallColors(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val border: Color,
    val borderSubtle: Color,
    val title: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val accent: Color,
    val accentSoft: Color,
    val bucketRang: Color,
    val bucketSilenced: Color,
    val bucketDropped: Color,
    val danger: Color,
    val dangerSurface: Color,
    val isLight: Boolean,
)

/** The original near-black palette, kept as-is — it was the good part. */
val DarkWallColors = WallColors(
    background = Color(0xFF08090A),
    surface = Color(0xFF101113),
    surfaceElevated = Color(0xFF17181B),
    border = Color(0xFF212227),
    borderSubtle = Color(0xFF17181B),
    title = Color(0xFFF7F8F8),
    text = Color(0xFFE6E7EA),
    textMuted = Color(0xFF8A8F98),
    textFaint = Color(0xFF585C64),
    accent = Color(0xFF5E6AD2),
    accentSoft = Color(0x335E6AD2),
    bucketRang = Color(0xFF48C78E),
    bucketSilenced = Color(0xFF7C8698),
    bucketDropped = Color(0xFFE0A03A),
    danger = Color(0xFFE5484D),
    dangerSurface = Color(0xFF2A1416),
    isLight = false,
)

/**
 * The light palette, built to the same restraint: one near-white canvas, barely
 * separated surfaces, one accent.
 *
 * The status colours are NOT the dark ones reused, and are not the brief's
 * originally-proposed light values either: those (#127A50 / #6B7280 / #9A6511)
 * pass the individual contrast-vs-surface check but sit only 0.005-0.021 apart
 * in luminance, which fails the pairwise-distinguishability test. They are
 * darkened/spread instead, keeping the same hue family (green / neutral gray /
 * amber) but pushed apart in luminance so all three are told apart at a glance
 * on a light surface, not just individually legible. See WallColorsTest and
 * task-1-report.md for the measured luminances.
 */
val LightWallColors = WallColors(
    background = Color(0xFFFCFCFD),
    surface = Color(0xFFF4F5F7),
    surfaceElevated = Color(0xFFFFFFFF),
    border = Color(0xFFDCDEE3),
    borderSubtle = Color(0xFFEBECEF),
    title = Color(0xFF0D0E10),
    text = Color(0xFF26282D),
    textMuted = Color(0xFF61656E),
    textFaint = Color(0xFF8E939C),
    accent = Color(0xFF4F5BC4),
    accentSoft = Color(0x1F4F5BC4),
    bucketRang = Color(0xFF0A4A30),
    bucketSilenced = Color(0xFF646870),
    bucketDropped = Color(0xFFB5780A),
    danger = Color(0xFFC01C21),
    dangerSurface = Color(0xFFFDEBEC),
    isLight = true,
)

val LocalWallColors: ProvidableCompositionLocal<WallColors> =
    staticCompositionLocalOf { DarkWallColors }
