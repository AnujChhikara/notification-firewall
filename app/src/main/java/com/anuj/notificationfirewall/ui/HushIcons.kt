// ui/HushIcons.kt
package com.anuj.notificationfirewall.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin

/**
 * Small Canvas-drawn glyphs for the Hush screens. The project has no icon-font
 * dependency, so anything beyond Roboto's safe glyphs (✓ → • ×) is drawn here
 * as simple strokes/fills that stay legible in either theme.
 */

@Composable
fun BoltIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val p = Path().apply {
            moveTo(size.width * 0.55f, 0f)
            lineTo(size.width * 0.18f, size.height * 0.58f)
            lineTo(size.width * 0.46f, size.height * 0.58f)
            lineTo(size.width * 0.38f, size.height)
            lineTo(size.width * 0.80f, size.height * 0.40f)
            lineTo(size.width * 0.52f, size.height * 0.40f)
            close()
        }
        drawPath(p, color)
    }
}

@Composable
fun HourglassIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val p = Path().apply {
            moveTo(size.width * 0.22f, size.height * 0.08f)
            lineTo(size.width * 0.78f, size.height * 0.08f)
            lineTo(size.width * 0.60f, size.height * 0.42f)
            lineTo(size.width * 0.78f, size.height * 0.84f)
            lineTo(size.width * 0.22f, size.height * 0.84f)
            lineTo(size.width * 0.40f, size.height * 0.42f)
            close()
        }
        drawPath(p, color, style = Stroke(width = 2.5f, join = StrokeJoin.Round, cap = StrokeCap.Round))
        drawLine(
            color,
            Offset(size.width * 0.28f, size.height * 0.92f),
            Offset(size.width * 0.72f, size.height * 0.92f),
            strokeWidth = 2.5f,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun StarIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cx = size.width / 2
        val cy = size.height / 2
        val rOut = size.minDimension / 2
        val rIn = rOut * 0.45f
        val p = Path()
        repeat(10) { i ->
            val r = if (i % 2 == 0) rOut else rIn
            val a = -Math.PI / 2 + i * Math.PI / 5
            val x = (cx + r * cos(a)).toFloat()
            val y = (cy + r * sin(a)).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        drawPath(p, color)
    }
}

@Composable
fun BlockIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.minDimension * 0.10f
        drawCircle(color, radius = size.minDimension * 0.42f, style = Stroke(width = w))
        val r = size.minDimension * 0.30f
        drawLine(
            color,
            Offset(size.width / 2 + r, size.height / 2 - r),
            Offset(size.width / 2 - r, size.height / 2 + r),
            strokeWidth = w,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun ClockIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.minDimension * 0.10f
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(color, radius = size.minDimension * 0.40f, center = c, style = Stroke(width = w))
        drawLine(color, c, Offset(c.x, c.y - size.minDimension * 0.26f), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(color, c, Offset(c.x + size.minDimension * 0.18f, c.y + size.minDimension * 0.10f), strokeWidth = w, cap = StrokeCap.Round)
    }
}

@Composable
fun TrashIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.minDimension * 0.10f
        val top = size.height * 0.26f
        val bottom = size.height * 0.88f
        // Lid + handle.
        drawLine(
            color,
            Offset(size.width * 0.20f, top),
            Offset(size.width * 0.80f, top),
            strokeWidth = w,
            cap = StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(size.width * 0.40f, top),
            Offset(size.width * 0.40f, size.height * 0.16f),
            strokeWidth = w,
            cap = StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(size.width * 0.40f, size.height * 0.16f),
            Offset(size.width * 0.60f, size.height * 0.16f),
            strokeWidth = w,
            cap = StrokeCap.Round,
        )
        // Body.
        val body = Path().apply {
            moveTo(size.width * 0.28f, top)
            lineTo(size.width * 0.72f, top)
            lineTo(size.width * 0.64f, bottom)
            lineTo(size.width * 0.36f, bottom)
            close()
        }
        drawPath(body, color, style = Stroke(width = w, join = StrokeJoin.Round))
        // Slats.
        drawLine(
            color,
            Offset(size.width * 0.44f, top + w * 1.6f),
            Offset(size.width * 0.44f, bottom - w * 1.2f),
            strokeWidth = w * 0.8f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(size.width * 0.56f, top + w * 1.6f),
            Offset(size.width * 0.56f, bottom - w * 1.2f),
            strokeWidth = w * 0.8f,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun TuneIcon(
    modifier: Modifier = Modifier,
    track: Color = Color(0xFFA8B0B1),
    knob: Color = Color(0xFFDAE4EA),
) {
    Canvas(modifier) {
        val knobs = listOf(0.30f, 0.62f, 0.42f)
        knobs.forEachIndexed { i, kx ->
            val y = size.height * (0.22f + 0.28f * i)
            drawLine(track, Offset(0f, y), Offset(size.width, y), strokeWidth = 2f, cap = StrokeCap.Round)
            drawCircle(knob, radius = 4.5f, center = Offset(size.width * kx, y))
        }
    }
}

@Composable
fun SearchIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.minDimension * 0.11f
        val c = Offset(size.width * 0.42f, size.height * 0.42f)
        drawCircle(color, radius = size.minDimension * 0.30f, center = c, style = Stroke(width = w))
        drawLine(
            color,
            Offset(c.x + size.minDimension * 0.21f, c.y + size.minDimension * 0.21f),
            Offset(size.width * 0.88f, size.height * 0.88f),
            strokeWidth = w,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun SparkleIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            moveTo(w * 0.50f, 0f)
            cubicTo(w * 0.55f, h * 0.32f, w * 0.68f, h * 0.45f, w, h * 0.50f)
            cubicTo(w * 0.68f, h * 0.55f, w * 0.55f, h * 0.68f, w * 0.50f, h)
            cubicTo(w * 0.45f, h * 0.68f, w * 0.32f, h * 0.55f, 0f, h * 0.50f)
            cubicTo(w * 0.32f, h * 0.45f, w * 0.45f, h * 0.32f, w * 0.50f, 0f)
            close()
        }
        drawPath(p, color)
    }
}

@Composable
fun ShieldIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = 3.5f
        val shield = Path().apply {
            moveTo(size.width * 0.50f, size.height * 0.06f)
            lineTo(size.width * 0.84f, size.height * 0.20f)
            lineTo(size.width * 0.84f, size.height * 0.52f)
            cubicTo(
                size.width * 0.84f, size.height * 0.74f,
                size.width * 0.68f, size.height * 0.86f,
                size.width * 0.50f, size.height * 0.94f,
            )
            cubicTo(
                size.width * 0.32f, size.height * 0.86f,
                size.width * 0.16f, size.height * 0.74f,
                size.width * 0.16f, size.height * 0.52f,
            )
            lineTo(size.width * 0.16f, size.height * 0.20f)
            close()
        }
        drawPath(shield, color, style = Stroke(width = w, join = StrokeJoin.Round))
        val check = Path().apply {
            moveTo(size.width * 0.38f, size.height * 0.52f)
            lineTo(size.width * 0.47f, size.height * 0.61f)
            lineTo(size.width * 0.63f, size.height * 0.42f)
        }
        drawPath(check, color, style = Stroke(width = w, join = StrokeJoin.Round, cap = StrokeCap.Round))
    }
}
