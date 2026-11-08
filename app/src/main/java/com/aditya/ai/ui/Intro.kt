package com.aditya.ai.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

enum class IntroMode { FULL, SHORT, OFF }

private class Particle(val angle: Float, val dist: Float, val size: Float, val delay: Float)
private fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
private fun seg(p: Float, a: Float, b: Float) = ((p - a) / (b - a)).coerceIn(0f, 1f)

@Composable fun BrandLockup(alpha: Float = 1f) {
    Column(Modifier.graphicsLayer { this.alpha = alpha }, horizontalAlignment = Alignment.CenterHorizontally) {
        AdityaMark(120.dp); Spacer(Modifier.height(20.dp))
        Text("ADITYA AI", color = Ax.Ice, style = MaterialTheme.typography.titleLarge, letterSpacing = 8.sp)
        Spacer(Modifier.height(6.dp))
        Text("YOUR PERSONAL INTELLIGENCE SYSTEM", color = Ax.Cyan.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall, letterSpacing = 3.sp, textAlign = TextAlign.Center)
    }
}

/**
 * Cinematic startup: particles converge into the orb, the core activates, rings rotate in, a scan light travels
 * the circumference, an energy waveform pulses, then the name and tagline reveal. One Animatable drives everything
 * (a single Canvas, no per-frame allocations beyond a small path), so it stays smooth on mid-range devices.
 * FULL ≈ 5 s on first launch, SHORT ≈ 2 s afterwards; tap anywhere or "Skip" ends it; reduced motion shows a still brand frame.
 */
@Composable fun StartupIntro(mode: IntroMode, onDone: () -> Unit) {
    val reduce = LocalReduceMotion.current
    var finished by remember { mutableStateOf(false) }
    fun finish() { if (!finished) { finished = true; onDone() } }
    val bg = Modifier.fillMaxSize().background(Ax.Midnight)
    if (mode == IntroMode.OFF) { LaunchedEffect(Unit) { finish() }; Box(bg); return }
    if (reduce) {
        LaunchedEffect(Unit) { delay(700); finish() }
        Box(bg, Alignment.Center) { BrandLockup() }
        return
    }
    val p = remember { Animatable(0f) }
    val parts = remember { val rnd = Random(7); List(72) { Particle(rnd.nextFloat() * 2f * PI.toFloat(), 0.55f + rnd.nextFloat() * 0.5f, 1f + rnd.nextFloat() * 2.2f, rnd.nextFloat()) } }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(if (mode == IntroMode.FULL) 5000 else 2000, easing = LinearEasing)); delay(120); finish() }
    Box(bg.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() }) {
        Canvas(Modifier.fillMaxSize()) {
            val pr = p.value
            val c = Offset(size.width / 2f, size.height / 2f - 60.dp.toPx())
            val R = size.minDimension * 0.30f
            val core = R * 0.36f
            // 1) particles appear and converge
            val conv = seg(pr, 0f, 0.34f)
            parts.forEach { q ->
                val t = smooth(((conv - q.delay * 0.45f) / 0.55f))
                val d = q.dist * R * 2.4f * (1f - t)
                val a = (seg(pr, 0f, 0.10f)) * (1f - seg(pr, 0.30f, 0.40f)) * (0.35f + 0.65f * t)
                if (a > 0.01f) drawCircle(Ax.Cyan.copy(alpha = a), q.size.dp.toPx(), Offset(c.x + cos(q.angle) * d, c.y + sin(q.angle) * d))
            }
            // 2) orb activation
            val act = smooth(seg(pr, 0.28f, 0.52f))
            if (act > 0f) {
                drawCircle(Brush.radialGradient(listOf(Ax.Cyan.copy(alpha = 0.45f * act), Ax.Cyan.copy(alpha = 0.08f * act), Color.Transparent), center = c, radius = R * 1.5f), R * 1.5f, c)
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = act), Ax.Cyan, Ax.Deep), center = Offset(c.x - core * 0.25f, c.y - core * 0.3f), radius = core * 1.5f), core * act, c)
            }
            // 3) holographic rings rotate in
            val ringT = smooth(seg(pr, 0.40f, 0.66f)); val ringR = core * 1.95f
            if (ringT > 0f) listOf(0f, 60f, 120f).forEachIndexed { i, a ->
                rotate(a + pr * 540f * (if (i % 2 == 0) 1f else -1f), c) {
                    drawOval(Ax.Cyan.copy(alpha = (0.62f - i * 0.12f) * ringT), Offset(c.x - ringR, c.y - ringR * 0.36f), Size(ringR * 2f, ringR * 0.72f), style = Stroke(1.6.dp.toPx()))
                }
            }
            // 4) scan light travels the circumference
            val scan = seg(pr, 0.52f, 0.80f)
            if (scan > 0f && scan < 1f) rotate(scan * 540f, c) {
                val sr = ringR * 1.18f
                drawArc(Ax.CyanSoft.copy(alpha = 0.9f * (1f - kotlin.math.abs(scan - 0.5f) * 1.2f).coerceIn(0f, 1f)), 0f, 60f, false, Offset(c.x - sr, c.y - sr), Size(sr * 2f, sr * 2f), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            }
            // 5) energy waveform / pulse
            val wave = seg(pr, 0.58f, 0.86f)
            if (wave > 0f) {
                val path = Path(); val w = core * 1.6f
                for (s in 0..40) {
                    val f = s / 40f
                    val y = c.y + sin(f * 4f * PI.toFloat() + pr * 20f) * core * 0.32f * sin(f * PI.toFloat()) * sin(wave * PI.toFloat())
                    val x = c.x - w / 2f + w * f
                    if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, Color.White.copy(alpha = 0.9f * sin(wave * PI.toFloat())), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                val pulseR = ringR * (0.9f + 1.4f * wave)
                drawCircle(Ax.Cyan.copy(alpha = 0.35f * (1f - wave)), pulseR, c, style = Stroke(2.dp.toPx()))
            }
        }
        Column(Modifier.align(Alignment.Center).offset(y = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("ADITYA AI", Modifier.graphicsLayer { alpha = smooth(seg(p.value, 0.68f, 0.84f)); translationY = (1f - alpha) * 12f },
                color = Ax.Ice, style = MaterialTheme.typography.titleLarge, letterSpacing = 8.sp)
            Spacer(Modifier.height(8.dp))
            Text("YOUR PERSONAL INTELLIGENCE SYSTEM", Modifier.graphicsLayer { alpha = smooth(seg(p.value, 0.80f, 0.95f)) },
                color = Ax.Cyan.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall, letterSpacing = 3.sp, textAlign = TextAlign.Center)
        }
        TextButton(::finish, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) { Text("Skip", color = Ax.Mist) }
    }
}
