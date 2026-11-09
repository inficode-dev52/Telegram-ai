package com.aditya.ai.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.Typography
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** White-first visual system with restrained violet/blue accents. */
object Ax {
    val Midnight = Color(0xFF101329); val Navy = Color(0xFF191D3A); val Deep = Color(0xFF29264F)
    val Cyan = Color(0xFF7657E8); val CyanSoft = Color(0xFF9B8AF4); val Ice = Color(0xFFF8F9FF); val Mist = Color(0xFF777C96)
    val Ok = Color(0xFF269B72); val Warn = Color(0xFFE69B32); val Danger = Color(0xFFE35D78); val Blue = Color(0xFF4387E8)
}

/** Spacing scale. */
object Sp { val xs = 4.dp; val sm = 8.dp; val md = 16.dp; val lg = 24.dp }

/** True when the system animation scale is 0 or the user enabled "Reduce motion" in Settings. */
val LocalReduceMotion = staticCompositionLocalOf { false }

private val Sans = FontFamily.SansSerif
val AdityaTypography = Typography(
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Light, fontSize = 34.sp, letterSpacing = 0.sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Light, fontSize = 28.sp, letterSpacing = 0.5.sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 22.sp, letterSpacing = 0.3.sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 20.sp, letterSpacing = 1.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 16.sp, letterSpacing = 0.15.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.8.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.6.sp)
)
val AdityaShapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp))

@Composable fun AdityaTheme(dark: Boolean, content: @Composable () -> Unit) {
    val scheme = if (dark) darkColorScheme(
        primary = Ax.Cyan, onPrimary = Ax.Midnight, secondary = Ax.CyanSoft, onSecondary = Ax.Midnight, tertiary = Ax.Blue,
        background = Ax.Midnight, onBackground = Ax.Ice, surface = Ax.Midnight, onSurface = Ax.Ice, surfaceVariant = Ax.Navy, onSurfaceVariant = Ax.Mist,
        primaryContainer = Ax.Deep, onPrimaryContainer = Ax.Ice, error = Ax.Danger, outline = Color(0xFF2B4A7A),
        surfaceContainer = Ax.Navy, surfaceContainerHigh = Ax.Deep, surfaceContainerHighest = Ax.Deep, surfaceContainerLow = Ax.Navy)
    else lightColorScheme(
        primary = Color(0xFF7054D8), onPrimary = Color.White, secondary = Color(0xFF397FD7), onSecondary = Color.White,
        tertiary = Color(0xFF4387E8), background = Color(0xFFF8F9FE), onBackground = Color(0xFF20233A),
        surface = Color(0xFFFFFFFF), onSurface = Color(0xFF20233A), surfaceVariant = Color(0xFFF0F1FA), onSurfaceVariant = Color(0xFF666B85),
        primaryContainer = Color(0xFFE9E3FF), onPrimaryContainer = Color(0xFF30245F), secondaryContainer = Color(0xFFE4EFFF),
        onSecondaryContainer = Color(0xFF193B69), outline = Color(0xFFD7D9E8), error = Color(0xFFC74661))
    MaterialTheme(colorScheme = scheme, typography = AdityaTypography, shapes = AdityaShapes, content = content)
}

/** Translucent "glass" surface with a restrained luminous border and press feedback. */
@Composable fun GlassCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val reduce = LocalReduceMotion.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !reduce) 0.98f else 1f, tween(120), label = "glass")
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val base = modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(shape)
        .background(Brush.verticalGradient(listOf(cs.surface, cs.surfaceVariant.copy(alpha = 0.72f))))
        .border(1.dp, Brush.linearGradient(listOf(cs.primary.copy(alpha = 0.22f), cs.tertiary.copy(alpha = 0.08f))), shape)
    val m = if (onClick != null) base.clickable(interactionSource = src, indication = LocalIndication.current, onClick = onClick) else base
    Column(m.padding(16.dp), content = content)
}

/** Staggered fade/slide-in; skipped entirely under reduced motion. */
@Composable fun Appear(index: Int = 0, content: @Composable () -> Unit) {
    val reduce = LocalReduceMotion.current
    val a = remember { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduce) { delay(index * 60L); a.animateTo(1f, tween(380)) } }
    Box(Modifier.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 24f }) { content() }
}

/** Empty / error / offline state. */
@Composable fun StateCard(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary,
                          actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    GlassCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(28.dp)); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (actionLabel != null && onAction != null) { Spacer(Modifier.height(8.dp)); TextButton(onAction) { Text(actionLabel) } }
    }
}

@Composable fun Tag(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Text(text, Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f)).padding(horizontal = 9.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall, color = color)
}

fun priorityColor(p: com.aditya.ai.data.Priority) = when (p) { com.aditya.ai.data.Priority.HIGH -> Ax.Danger; com.aditya.ai.data.Priority.MEDIUM -> Ax.Warn; else -> Ax.Mist }
