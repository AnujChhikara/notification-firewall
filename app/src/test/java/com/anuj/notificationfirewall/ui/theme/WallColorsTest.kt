package com.anuj.notificationfirewall.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class WallColorsTest {

    /** WCAG relative-contrast ratio between two opaque colours. */
    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return max(la, lb) / min(la, lb)
    }

    @Test
    fun darkBodyTextMeetsAaOnItsBackground() {
        assertTrue(
            "body text on dark background must reach 4.5:1",
            contrast(DarkWallColors.text, DarkWallColors.background) >= 4.5f,
        )
    }

    @Test
    fun lightBodyTextMeetsAaOnItsBackground() {
        assertTrue(
            "body text on light background must reach 4.5:1",
            contrast(LightWallColors.text, LightWallColors.background) >= 4.5f,
        )
    }

    @Test
    fun mutedTextMeetsLargeTextContrastInBothThemes() {
        assertTrue(contrast(DarkWallColors.textMuted, DarkWallColors.background) >= 3.0f)
        assertTrue(contrast(LightWallColors.textMuted, LightWallColors.background) >= 3.0f)
    }

    @Test
    fun statusColoursAreDistinguishableFromSurfaceInBothThemes() {
        listOf(DarkWallColors, LightWallColors).forEach { palette ->
            listOf(palette.bucketRang, palette.bucketSilenced, palette.bucketDropped).forEach { status ->
                assertTrue(
                    "status dot must be visible against its surface",
                    contrast(status, palette.surface) >= 2.0f,
                )
            }
        }
    }

    @Test
    fun statusColoursAreDistinguishableFromEachOther() {
        listOf(DarkWallColors, LightWallColors).forEach { palette ->
            val rang = palette.bucketRang.luminance()
            val silenced = palette.bucketSilenced.luminance()
            val dropped = palette.bucketDropped.luminance()
            assertTrue(abs(rang - silenced) > 0.05f)
            assertTrue(abs(silenced - dropped) > 0.05f)
        }
    }

    @Test
    fun lightAndDarkAreGenuinelyDifferentPalettes() {
        assertNotEquals(LightWallColors.background, DarkWallColors.background)
        assertTrue(LightWallColors.isLight)
        assertTrue(!DarkWallColors.isLight)
    }

    @Test
    fun lightBackgroundIsLighterThanItsSurfaceContrastPartner() {
        assertTrue(LightWallColors.background.luminance() > 0.7f)
        assertTrue(DarkWallColors.background.luminance() < 0.1f)
    }

    @Test
    fun dangerIsLegibleOnItsOwnSurface() {
        listOf(DarkWallColors, LightWallColors).forEach { palette ->
            assertTrue(contrast(palette.danger, palette.dangerSurface) >= 3.0f)
        }
    }
}
