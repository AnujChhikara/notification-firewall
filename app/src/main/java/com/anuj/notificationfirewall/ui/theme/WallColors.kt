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
    val onAccent: Color,
    val accentSoft: Color,
    val bucketRang: Color,
    val bucketSilenced: Color,
    val bucketDropped: Color,
    /**
     * The "wall is armed" signal: a restrained green used only for the armed
     * shield, ARMED chip and presence dot so the on-state reads at a glance.
     * Everything else stays monochrome; status lists keep using the gray
     * bucket steps.
     */
    val armed: Color,
    val danger: Color,
    val dangerSurface: Color,
    val isLight: Boolean,
)

/** Pure monochrome dark palette: near-black canvas, gray surfaces, white
 *  accent. No chromatic color anywhere -- status reads through luminance
 *  steps (white rang, mid-gray silenced, dark-gray dropped) plus labels.
 *  See WallColorsTest for the enforced contrast/distinguishability bounds. */
val DarkWallColors = WallColors(
    background = Color(0xFF0A0A0B),
    surface = Color(0xFF141416),
    surfaceElevated = Color(0xFF1E1E21),
    border = Color(0xFF2A2A2E),
    borderSubtle = Color(0xFF1B1B1E),
    title = Color(0xFFF7F7F8),
    text = Color(0xFFE8E8EA),
    textMuted = Color(0xFFA3A3A8),
    textFaint = Color(0xFF6E6E74),
    accent = Color(0xFFF4F4F5),
    onAccent = Color(0xFF0A0A0B),
    accentSoft = Color(0x33F4F4F5),
    bucketRang = Color(0xFFF4F4F5),
    bucketSilenced = Color(0xFF9C9CA2),
    bucketDropped = Color(0xFF6E6E75),
    armed = Color(0xFF5FD38D),
    danger = Color(0xFF8E8E94),
    dangerSurface = Color(0xFF232326),
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
/**
 * The light monochrome palette: near-white canvas, black accent, gray status
 * steps (black rang, mid-gray silenced, light-gray dropped). Same role names
 * as dark, mirrored in luminance.
 */
val LightWallColors = WallColors(
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFF1F1F3),
    surfaceElevated = Color(0xFFFFFFFF),
    border = Color(0xFFD9D9DC),
    borderSubtle = Color(0xFFE7E7E9),
    title = Color(0xFF101013),
    text = Color(0xFF26262A),
    textMuted = Color(0xFF5E5E64),
    textFaint = Color(0xFF8E8E94),
    accent = Color(0xFF17171B),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0x1F17171B),
    bucketRang = Color(0xFF17171B),
    bucketSilenced = Color(0xFF5C5C62),
    bucketDropped = Color(0xFF8E8E94),
    armed = Color(0xFF1E7A4C),
    danger = Color(0xFF5C5C62),
    dangerSurface = Color(0xFFE9E9EB),
    isLight = true,
)

val LocalWallColors: ProvidableCompositionLocal<WallColors> =
    staticCompositionLocalOf { DarkWallColors }
