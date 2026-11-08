package com.aditya.ai.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aditya.ai.ai.OrbState
import kotlin.math.PI
import kotlin.math.sin

/** Brand-consistent rings: three ellipses 60 degrees apart (same silhouette as the launcher icon). */
private val ringAngles = listOf(0f, 60f, 120f)

private fun stateColor(s: OrbState) = when (s) {
    OrbState.IDLE -> Ax.Cyan; OrbState.THINKING -> Ax.Blue; OrbState.PROCESSING -> Ax.CyanSoft
    OrbState.LISTENING -> Ax.Ok; OrbState.SPEAKING -> Ax.Cyan; OrbState.ERROR -> Ax.Danger
}
fun orbLabel(s: OrbState) = when (s) {
    OrbState.IDLE -> "Standing by"; OrbState.THINKING -> "Thinking…"; OrbState.PROCESSING -> "Processing your data…"
    OrbState.LISTENING -> "Listening…"; OrbState.SPEAKING -> "Speaking…"; OrbState.ERROR -> "Something went wrong"
}

/** Animated AI orb. All six states are driven by [state]; with reduced motion it renders a still frame. */
@Composable fun AiOrb(state: OrbState, modifier: Modifier = Modifier, size: Dp = 220.dp, level: Float = 0f) {
    val color by animateColorAsState(stateColor(state), tween(500), label = "orbColor")
    val reduce = LocalReduceMotion.current
    if (reduce) {
        val still = remember { mutableStateOf(0f) }
        OrbCanvas(modifier.size(size), color, state, still, still, level)
    } else {
        val tr = rememberInfiniteTransition(label = "orb")
        val spin = tr.animateFloat(0f, 360f, infiniteRepeatable(tween(12000, easing = LinearEasing)), label = "spin")
        val pulse = tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "pulse")
        OrbCanvas(modifier.size(size), color, state, spin, pulse, level)
    }
}

@Composable private fun OrbCanvas(modifier: Modifier, color: Color, state: OrbState, spin: State<Float>, pulse: State<Float>, level: Float) {
    Canvas(modifier) {
        val c = center; val r = size.minDimension / 2f; val core = r * 0.36f
        val sp = spin.value; val pl = pulse.value
        val speed = when (state) { OrbState.THINKING -> 3f; OrbState.PROCESSING -> 2f; OrbState.LISTENING, OrbState.SPEAKING -> 1.5f; OrbState.ERROR -> 0f; else -> 1f }
        val breathe = 1f + 0.045f * sin(pl * 2f * PI.toFloat()) * (if (state == OrbState.IDLE) 1f else 2f)
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.40f), color.copy(alpha = 0.10f), Color.Transparent), center = c, radius = r), r, c)
        val active = state == OrbState.LISTENING || state == OrbState.SPEAKING
        for (k in 0..1) {
            val t = (pl + k * 0.5f) % 1f
            drawCircle(color.copy(alpha = (1f - t) * (if (active) 0.40f else 0.16f)), core * 1.25f + (r - core * 1.25f) * t, c, style = Stroke(2.dp.toPx()))
        }
        val ringR = core * 1.95f
        ringAngles.forEachIndexed { i, a ->
            rotate(sp * speed * (if (i % 2 == 0) 1f else -1f) + a, c) {
                drawOval(color.copy(alpha = 0.62f - i * 0.12f), Offset(c.x - ringR, c.y - ringR * 0.36f), Size(ringR * 2f, ringR * 0.72f), style = Stroke(1.6.dp.toPx()))
            }
        }
        val scanA = when (state) { OrbState.THINKING, OrbState.PROCESSING -> 0.9f; OrbState.IDLE -> 0.22f; else -> 0.0f }
        if (scanA > 0f) rotate(sp * speed * 2f, c) {
            val sr = ringR * 1.16f
            drawArc(color.copy(alpha = scanA), 0f, 70f, false, Offset(c.x - sr, c.y - sr), Size(sr * 2f, sr * 2f), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.95f), color, Ax.Deep), center = Offset(c.x - core * 0.25f, c.y - core * 0.3f), radius = core * 1.5f), core * breathe, c)
        if (active) drawWave(c, core, pl, if (state == OrbState.SPEAKING) 0.75f else 0.25f + level * 0.75f)
    }
}

private fun DrawScope.drawWave(c: Offset, core: Float, phase: Float, energy: Float) {
    val w = core * 1.6f; val steps = 40; val path = Path()
    for (s in 0..steps) {
        val f = s / steps.toFloat()
        val y = c.y + sin(f * 4f * PI.toFloat() + phase * 4f * PI.toFloat()) * core * 0.34f * energy * sin(f * PI.toFloat())
        val x = c.x - w / 2f + w * f
        if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, Color.White.copy(alpha = 0.92f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
}

/** Static brand mark (logo): used in splash, login and headers. */
@Composable fun AdityaMark(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val c = center; val r = this.size.minDimension / 2f; val core = r * 0.30f; val ringR = r * 0.80f
        drawCircle(Brush.radialGradient(listOf(Ax.Cyan.copy(alpha = 0.40f), Ax.Cyan.copy(alpha = 0.08f), Color.Transparent), center = c, radius = r), r, c)
        ringAngles.forEachIndexed { i, a ->
            rotate(a, c) { drawOval(Ax.Cyan.copy(alpha = 0.85f - i * 0.2f), Offset(c.x - ringR, c.y - ringR * 0.36f), Size(ringR * 2f, ringR * 0.72f), style = Stroke((r * 0.035f).coerceAtLeast(1f))) }
        }
        drawCircle(Brush.radialGradient(listOf(Color.White, Ax.Cyan, Color(0xFF0E6FA8)), center = Offset(c.x - core * 0.25f, c.y - core * 0.3f), radius = core * 1.5f), core, c)
    }
}
