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

@Composable fun ActivityScreen(app: AdityaApp, filter: String) {
    val all by app.store.events.collectAsState(); val ev = if (filter == "all") all else all.filter { it.type == filter }
    val fmt = SimpleDateFormat("EEE d MMM", Locale.getDefault()); val tf = SimpleDateFormat("HH:mm", Locale.getDefault())
    Column {
        Title(if (filter == "all") "Activity" else "Notifications") { if (filter == "all" && ev.isNotEmpty()) TextButton({ app.store.clearEvents() }) { Text("Clear") } }
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
@Composable fun SettingsScreen(app: AdityaApp) {
    val ctx = LocalContext.current; val st = app.store; val scope = rememberCoroutineScope(); val conn by app.tg.connection.collectAsState(); val human by app.engine.humanMode.collectAsState(); val theme by app.theme.collectAsState()
    var key by remember { mutableStateOf(app.ai.apiKey) }; var model by remember { mutableStateOf(app.ai.model) }; var test by remember { mutableStateOf<String?>(null) }
    var notif by remember { mutableStateOf(st.bool("notif", true)) }; var prev by remember { mutableStateOf(st.bool("preview", true)) }
    var lock by remember { mutableStateOf(st.bool("lock", false)) }; var sec by remember { mutableStateOf(st.bool("secure", false)) }; var confirmOut by remember { mutableStateOf(false) }
    @Composable fun Sw(l: String, v: Boolean, f: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { Text(l, Modifier.weight(1f)); Switch(v, f) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Title("Settings")
        Text("Telegram: $conn", color = MaterialTheme.colorScheme.onSurfaceVariant); TextButton({ confirmOut = true }) { Text("Log out of Telegram") }
        Section("Appearance"); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("system", "light", "dark").forEach { t -> FilterChip(theme == t, { app.theme.value = t; st.setStr("theme", t) }, { Text(t.replaceFirstChar { it.uppercase() }) }) } }
        Section("AI (OpenRouter)")
        OutlinedTextField(key, { key = it; app.ai.apiKey = it.trim() }, Modifier.fillMaxWidth(), label = { Text("API key") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        OutlinedTextField(model, { model = it; app.ai.model = it.trim() }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Model, e.g. openrouter/auto") }, singleLine = true)
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton({ scope.launch { test = "Testing…"; test = app.ai.chat("Reply with OK.", listOf("user" to "ping")).fold({ "Connected: " + it.take(40) }, { it.message }) } }) { Text("Test connection") }; Text(test ?: "", style = MaterialTheme.typography.labelMedium) }
        Sw("Human mode (stops all AI replies)", human) { app.engine.setHuman(it); st.log("mode", "Human mode ${if (it) "on" else "off"}") }
        Section("Notifications"); Sw("Message notifications (when app is closed)", notif) { notif = it; st.setBool("notif", it) }; Sw("Show message previews", prev) { prev = it; st.setBool("preview", it) }
        Section("Privacy")
        Sw("App lock (biometrics / device credential)", lock) { on -> if (on && BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) != BiometricManager.BIOMETRIC_SUCCESS) Toast.makeText(ctx, "Set up a screen lock or biometrics in Android settings first", Toast.LENGTH_LONG).show() else { lock = on; st.setBool("lock", on) } }
        Sw("Hide content in recents and block screenshots", sec) { sec = it; st.setBool("secure", it); ctx.findActivity()?.window?.let { w -> if (it) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) } }
        Section("Data"); TextButton({ st.clearEvents() }) { Text("Clear activity history") }; TextButton({ st.clearMemory() }) { Text("Clear AI memory") }
        Spacer(Modifier.height(24.dp))
    }
    if (confirmOut) AlertDialog({ confirmOut = false }, { TextButton({ confirmOut = false; app.tg.logout() }) { Text("Log out") } }, dismissButton = { TextButton({ confirmOut = false }) { Text("Cancel") } }, title = { Text("Log out?") }, text = { Text("You will need to sign in again with your phone number.") })
}
