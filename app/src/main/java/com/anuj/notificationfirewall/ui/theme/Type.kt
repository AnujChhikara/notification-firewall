// ui/theme/Type.kt
package com.anuj.notificationfirewall.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private val Sans = Inter

// Big, tightly-tracked bold titles; quiet, readable body. Tracking goes slightly
// negative on large text (the modern/Linear feel) and neutral on small text.
val NfTypography = Typography(
    // Screen titles ("Inbox", "Analytics").
    headlineLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 30.sp,
        lineHeight = 34.sp, letterSpacing = (-0.02).em,
    ),
    headlineMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 24.sp,
        lineHeight = 28.sp, letterSpacing = (-0.02).em,
    ),
    // Big stat numbers.
    displaySmall = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 34.sp,
        lineHeight = 38.sp, letterSpacing = (-0.02).em,
    ),
    titleLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
        lineHeight = 24.sp, letterSpacing = (-0.01).em,
    ),
    titleMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
        lineHeight = 20.sp, letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 15.sp,
        lineHeight = 21.sp, letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 13.5.sp,
        lineHeight = 19.sp, letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp,
        lineHeight = 16.sp, letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp,
        lineHeight = 15.sp, letterSpacing = 0.sp,
    ),
    // Eyebrows / captions.
    labelSmall = TextStyle(
        fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.5.sp,
        lineHeight = 14.sp, letterSpacing = 0.02.em,
    ),
)
