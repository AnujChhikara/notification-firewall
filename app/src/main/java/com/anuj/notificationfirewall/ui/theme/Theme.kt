// ui/theme/Theme.kt
package com.anuj.notificationfirewall.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val NfColors = darkColorScheme(
    primary = NfAccent,
    onPrimary = NfTitle,
    secondary = NfAccent,
    background = NfBackground,
    onBackground = NfText,
    surface = NfBackground,
    onSurface = NfText,
    surfaceVariant = NfSurface,
    onSurfaceVariant = NfTextMuted,
    outline = NfBorder,
    outlineVariant = NfBorderSubtle,
    error = NfDanger,
    onError = NfTitle,
    errorContainer = NfDangerSurface,
    onErrorContainer = NfText,
)

private val NfShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Dark-only, on purpose: the identity is a single calm black surface that the
 *  system bars blend into. */
@Composable
fun NfTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NfColors,
        typography = NfTypography,
        shapes = NfShapes,
        content = content,
    )
}
