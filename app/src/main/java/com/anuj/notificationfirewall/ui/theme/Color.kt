// ui/theme/Color.kt
package com.anuj.notificationfirewall.ui.theme

import androidx.compose.ui.graphics.Color

// Canvas + surfaces — near-pure black, surfaces barely lifted (Linear-style).
val NfBackground = Color(0xFF08090A)
val NfSurface = Color(0xFF101113)
val NfSurfaceElevated = Color(0xFF17181B)
val NfBorder = Color(0xFF212227)
val NfBorderSubtle = Color(0xFF17181B)

// Text — titles near-white, body bright-gray, then two muted steps.
val NfTitle = Color(0xFFF7F8F8)
val NfText = Color(0xFFE6E7EA)
val NfTextMuted = Color(0xFF8A8F98)
val NfTextFaint = Color(0xFF585C64)

// One accent, used sparingly.
val NfAccent = Color(0xFF5E6AD2)
val NfAccentSoft = Color(0x335E6AD2)

// Status — the "traffic control" signature. Each maps to a notification's fate.
val NfRang = Color(0xFF48C78E)      // let through / rang
val NfSilenced = Color(0xFF7C8698)  // silenced (visible, no sound)
val NfCaptured = Color(0xFFE0A03A)  // captured (held in inbox)

val NfDanger = Color(0xFFE5484D)
val NfDangerSurface = Color(0xFF2A1416)
