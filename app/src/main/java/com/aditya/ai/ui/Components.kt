package com.aditya.ai.ui
import android.content.*
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.aditya.ai.td.Telegram
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

val Lav = Color(0xFF7C6BD6)
@Composable fun AdityaTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFFB4A8F0), onPrimary = Color(0xFF1B1530), background = Color(0xFF121117), surface = Color(0xFF121117),
        surfaceVariant = Color(0xFF25232F), onSurface = Color(0xFFE9E6F2), onSurfaceVariant = Color(0xFFA9A5B8), primaryContainer = Color(0xFF3A3160), onPrimaryContainer = Color(0xFFE9E6F2))
    else lightColorScheme(primary = Lav, background = Color(0xFFFAF9FD), surface = Color(0xFFFAF9FD), surfaceVariant = Color(0xFFF0EDF9), onSurface = Color(0xFF1B1A22),
        onSurfaceVariant = Color(0xFF6B6879), primaryContainer = Color(0xFFE7E2FA), onPrimaryContainer = Color(0xFF1B1A22)), content = content)
}
@Composable fun Modifier.shimmer(): Modifier {
    val x by rememberInfiniteTransition(label = "sh").animateFloat(0f, 1000f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "x")
    val c = MaterialTheme.colorScheme.surfaceVariant; val h = MaterialTheme.colorScheme.background
    return clip(RoundedCornerShape(8.dp)).background(Brush.linearGradient(listOf(c, h, c), Offset(x - 300f, 0f), Offset(x, 0f)))
}
@Composable fun SkeletonRows(n: Int = 6) = Column(Modifier.padding(horizontal = 20.dp)) { repeat(n) {
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(46.dp).clip(CircleShape).shimmer()); Spacer(Modifier.width(14.dp))
        Column { Box(Modifier.height(14.dp).width(150.dp).shimmer()); Spacer(Modifier.height(8.dp)); Box(Modifier.height(12.dp).width(220.dp).shimmer()) } } } }
@Composable fun AnimatedCount(n: Int, style: TextStyle, color: Color = Color.Unspecified) { val v by animateIntAsState(n, tween(700), label = "n"); Text("$v", style = style, color = color) }
@Composable fun FileImage(path: String, modifier: Modifier, crop: Boolean = true) {
    val bmp by produceState<ImageBitmap?>(null, path) { value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap() }.getOrNull() } }
    bmp?.let { Image(it, null, modifier, contentScale = if (crop) ContentScale.Crop else ContentScale.Fit) } ?: Box(modifier.shimmer())
}
@Composable fun Avatar(title: String, fileId: Int, tg: Telegram, size: Dp = 46.dp) {
    val files by tg.files.collectAsState(); val p = files[fileId]
    LaunchedEffect(fileId) { tg.ensureFile(fileId) }
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), Alignment.Center) {
        if (p != null) FileImage(p, Modifier.fillMaxSize()) else Text(title.trim().take(1).uppercase(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
    }
}
@Composable fun Section(t: String) = Text(t.uppercase(), Modifier.padding(horizontal = 24.dp, vertical = 10.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.5.sp)
@Composable fun Pill(t: String, ok: Boolean) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) Color(0xFF4CAF7A) else Color(0xFFE0A030))); Spacer(Modifier.width(6.dp))
    Text(t, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable fun Title(t: String, trailing: @Composable RowScope.() -> Unit = {}) = Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(t, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis); trailing() }
fun fmtSec(s: Int): String { val d = Date(s * 1000L); val same = SimpleDateFormat("yMd", Locale.US).format(d) == SimpleDateFormat("yMd", Locale.US).format(Date())
    return SimpleDateFormat(if (same) "HH:mm" else "d MMM", Locale.getDefault()).format(d) }
fun fmtMs(ms: Long) = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(ms))
fun copyUri(ctx: Context, uri: Uri): File {
    var name = "file"; ctx.contentResolver.query(uri, null, null, null, null)?.use { if (it.moveToFirst()) { val i = it.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) name = it.getString(i) } }
    val f = File(ctx.cacheDir, "up_${System.currentTimeMillis()}_$name"); ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }; return f
}
fun Context.findActivity(): android.app.Activity? { var c = this; while (c is ContextWrapper) { if (c is android.app.Activity) return c; c = c.baseContext }; return null }
