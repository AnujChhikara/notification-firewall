// ui/theme/Theme.kt
package com.anuj.notificationfirewall.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

private val NfShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private fun materialScheme(c: WallColors) = if (c.isLight) {
    lightColorScheme(
        primary = c.accent, onPrimary = c.surfaceElevated,
        secondary = c.accent,
        background = c.background, onBackground = c.text,
        surface = c.background, onSurface = c.text,
        surfaceVariant = c.surface, onSurfaceVariant = c.textMuted,
        outline = c.border, outlineVariant = c.borderSubtle,
        error = c.danger, onError = c.surfaceElevated,
        errorContainer = c.dangerSurface, onErrorContainer = c.danger,
    )
} else {
    darkColorScheme(
        primary = c.accent, onPrimary = c.title,
        secondary = c.accent,
        background = c.background, onBackground = c.text,
        surface = c.background, onSurface = c.text,
        surfaceVariant = c.surface, onSurfaceVariant = c.textMuted,
        outline = c.border, outlineVariant = c.borderSubtle,
        error = c.danger, onError = c.title,
        errorContainer = c.dangerSurface, onErrorContainer = c.text,
    )
}

/** Resolves the semantic [WallColors] palette from [mode] and provides it via
 *  [LocalWallColors], then feeds the same colours into Material3's colour
 *  scheme so components using either layer stay in sync. */
@Composable
fun NfTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val light = when (mode) {
        ThemeMode.LIGHT -> true
        ThemeMode.DARK -> false
        ThemeMode.SYSTEM -> !isSystemInDarkTheme()
    }
    val colors = if (light) LightWallColors else DarkWallColors

    CompositionLocalProvider(LocalWallColors provides colors) {
        MaterialTheme(
            colorScheme = materialScheme(colors),
            typography = NfTypography,
            shapes = NfShapes,
            content = content,
        )
    }
}
