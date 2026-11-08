package com.aditya.ai.ui
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import android.view.WindowManager
import com.aditya.ai.AdityaApp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable fun ActivityScreen(app: AdityaApp, filter: String, showTitle: Boolean = true) {
    val all by app.store.events.collectAsState(); val ev = if (filter == "all") all else all.filter { it.type == filter }
    val fmt = SimpleDateFormat("EEE d MMM", Locale.getDefault()); val tf = SimpleDateFormat("HH:mm", Locale.getDefault())
    Column {
        if (showTitle) Title(if (filter == "all") "Activity" else "Notifications") { if (filter == "all" && ev.isNotEmpty()) TextButton({ app.store.clearEvents() }) { Text("Clear") } }
        if (ev.isEmpty()) Text("No events yet. Replies, drafts, summaries, notifications and connection changes will appear here.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn { var last = ""; ev.forEach { e -> val d = fmt.format(Date(e.ts)); if (d != last) { last = d; item(key = "h$d") { Section(d) } }
            item(key = e.ts.toString() + e.text) { ListItem({ Text(e.text, maxLines = 2, overflow = TextOverflow.Ellipsis) }, overlineContent = { Text(tf.format(Date(e.ts))) }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                leadingContent = { Icon(when (e.type) { "ai_reply" -> Icons.Default.AutoAwesome; "draft" -> Icons.Default.EditNote; "notify" -> Icons.Default.Notifications; "error" -> Icons.Default.ErrorOutline
                    "network" -> Icons.Default.Wifi; "summary" -> Icons.Default.Summarize; "task" -> Icons.Default.CheckCircle; "mode" -> Icons.Default.Tune; else -> Icons.Default.Sync }, null, tint = MaterialTheme.colorScheme.primary) }) } } }
    }
}
@Composable fun SearchScreen(app: AdityaApp, close: () -> Unit, open: (Long) -> Unit) {
    val tg = app.tg; val chats by tg.chats.collectAsState(); var q by remember { mutableStateOf("") }; var warming by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { tg.warmCache(); warming = false }
    val cr = if (q.length >= 2) chats.filter { it.title.contains(q, true) }.take(8) else emptyList()
    val mr = remember(q, warming) { if (q.length >= 2) tg.searchMessages(q) else emptyList() }
    Column(Modifier.statusBarsPadding()) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(close) { Icon(Icons.Default.ArrowBack, "Back") }
            OutlinedTextField(q, { q = it }, Modifier.weight(1f).padding(end = 12.dp), placeholder = { Text("Search chats and messages") }, singleLine = true) }
        if (warming) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn { if (cr.isNotEmpty()) item { Section("Chats") }
            items(cr, key = { "c" + it.id }) { c -> ListItem({ Text(c.title) }, Modifier.clickable { open(c.id) }, leadingContent = { Avatar(c.title, c.photoId, tg, 38.dp) }) }
            if (mr.isNotEmpty()) item { Section("Messages (recent history)") }
            items(mr, key = { "m" + it.chatId + "_" + it.id }) { m -> ListItem({ Text(m.text.take(120), maxLines = 2) }, Modifier.clickable { open(m.chatId) }, overlineContent = { Text((chats.firstOrNull { it.id == m.chatId }?.title ?: "") + " · " + fmtSec(m.date)) }) }
            if (q.length >= 2 && cr.isEmpty() && mr.isEmpty() && !warming) item { Text("No results.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
}
