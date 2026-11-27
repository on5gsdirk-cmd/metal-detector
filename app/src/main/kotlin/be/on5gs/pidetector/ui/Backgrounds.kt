package be.on5gs.pidetector.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import be.on5gs.pidetector.data.AppTheme
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun ThemedBackground(theme: AppTheme, level: () -> Float) {
    when (theme) {
        AppTheme.STANDARD -> StandardBackground(level)
        AppTheme.COIN_HUNTERS -> PsychedelicBackground(level)
    }
}

/** Standaard: bijna zwart, rode gloed van onder en een vaag radarraster. */
@Composable
private fun StandardBackground(level: () -> Float) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Color(0xFF07080B))
        val l = level().coerceIn(0f, 1f)
        drawRect(
            Brush.radialGradient(
                listOf(Color(0xFFFF2D55).copy(alpha = 0.16f + 0.25f * l), Color.Transparent),
                center = Offset(size.width / 2, size.height * 1.02f),
                radius = size.height * 0.75f,
            )
        )
        val c = Offset(size.width / 2, size.height * 0.33f)
        val grid = Color(0x14FF2D55)
        for (i in 1..6) drawCircle(grid, radius = size.width * 0.16f * i, center = c, style = Stroke(1.2f))
        for (k in 0 until 12) {
            val a = k * Math.PI / 6
            drawLine(grid, c, Offset(c.x + cos(a).toFloat() * size.height, c.y + sin(a).toFloat() * size.height), 1f)
        }
    }
}

/**
 * Muntenshuppers: draaiende regenboog-spiraal, pulserende ringen in wisselende
 * kleuren en zwevende kleurbollen. Wordt wilder naarmate het signaal stijgt.
 */
@Composable
private fun PsychedelicBackground(level: () -> Float) {
    val tr = rememberInfiniteTransition(label = "psy")
    val spin by tr.animateFloat(0f, 360f, infiniteRepeatable(tween(14000, easing = LinearEasing)), label = "spin")
    val hue by tr.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "hue")
    val pulse by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "pulse")
    val wob by tr.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "wob")

    Canvas(Modifier.fillMaxSize()) {
        val l = level().coerceIn(0f, 1f)
        val c = Offset(size.width / 2, size.height * 0.38f)
        val maxR = size.height

        drawRect(Color(0xFF0A0014))

        // draaiende regenboog-sweep
        val rainbow = (0..6).map { Color.hsv((hue + it * 60f) % 360f, 0.95f, 0.75f) }
        rotate(spin * (1f + 2f * l), c) {
            drawCircle(Brush.sweepGradient(rainbow, c), radius = maxR, center = c, alpha = 0.55f)
        }

        // spiraalarmen
        for (arm in 0 until 6) {
            val base = arm * 60f + spin * 1.5f
            var prev: Offset? = null
            for (s in 0..60) {
                val r = s / 60f * maxR * 0.9f
                val a = Math.toRadians((base + s * 7f).toDouble())
                val p = Offset(c.x + cos(a).toFloat() * r, c.y + sin(a).toFloat() * r)
                prev?.let {
                    drawLine(Color.hsv((hue + arm * 60f + s * 4f) % 360f, 1f, 1f), it, p,
                        strokeWidth = 3f + s * 0.35f, alpha = 0.35f)
                }
                prev = p
            }
        }

        // pulserende ringen naar buiten
        for (i in 0 until 7) {
            val ph = (pulse + i / 7f) % 1f
            val r = ph * min(size.width, size.height) * (0.9f + 0.5f * l)
            drawCircle(
                Color.hsv((hue + i * 50f) % 360f, 1f, 1f),
                radius = r, center = c, alpha = (1f - ph) * (0.45f + 0.4f * l),
                style = Stroke(width = 6f + 18f * l),
            )
        }

        // zwevende bollen (lava-lamp)
        for (b in 0 until 5) {
            val bx = size.width * (0.5f + 0.38f * sin(wob + b * 1.3f))
            val by = size.height * (0.5f + 0.42f * cos(wob * 0.7f + b * 2.1f))
            val br = size.width * (0.25f + 0.08f * sin(wob * 2 + b))
            drawCircle(
                Brush.radialGradient(
                    listOf(Color.hsv((hue + 180f + b * 72f) % 360f, 0.9f, 1f).copy(alpha = 0.45f), Color.Transparent),
                    center = Offset(bx, by), radius = br,
                ),
                radius = br, center = Offset(bx, by),
            )
        }

        // donkere waas zodat tekst leesbaar blijft
        drawRect(Color.Black.copy(alpha = 0.35f - 0.15f * l))
    }
}
