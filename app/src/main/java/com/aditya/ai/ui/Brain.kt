package com.aditya.ai.ui
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.*
import com.aditya.ai.AdityaApp
import kotlinx.coroutines.launch

@Composable fun BrainScreen(app: AdityaApp, open: (Long) -> Unit) {
    var seg by remember { mutableIntStateOf(0) }
    Column {
        Title("Brain")
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("Briefing", "Tasks", "Inbox", "Drafts", "Memory").withIndex().toList()) { (i, n) -> FilterChip(seg == i, { seg = i }, { Text(n) }) } }
        Spacer(Modifier.height(8.dp))
        AnimatedContent(seg, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "seg") { s -> when (s) { 0 -> BriefingPane(app); 1 -> TasksPane(app); 2 -> InboxPane(app, open); 3 -> DraftsPane(app); else -> MemoryPane(app) } }
    }
}
@Composable private fun BriefingPane(app: AdityaApp) {
    val notes by app.store.notes.collectAsState(); var busy by remember { mutableStateOf(false) }; var err by remember { mutableStateOf<String?>(null) }; val scope = rememberCoroutineScope()
    val last = notes.firstOrNull { it.kind == "briefing" }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Button({ scope.launch { busy = true; err = null; app.brain.briefing().onFailure { err = it.message }; busy = false } }, enabled = !busy) { Text(if (busy) "Generating…" else "Generate daily briefing") }
        if (busy) { Spacer(Modifier.height(16.dp)); repeat(4) { Box(Modifier.fillMaxWidth().height(14.dp).shimmer()); Spacer(Modifier.height(10.dp)) } }
        err?.let { Text(it, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(16.dp))
        if (last != null) { Text(fmtMs(last.ts), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(6.dp)); Text(last.text) }
        else Text("No briefing yet. It summarizes the last 24 hours of your chats, open tasks and priorities using OpenRouter.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun TasksPane(app: AdityaApp) {
    val tasks by app.store.tasks.collectAsState(); var t by remember { mutableStateOf("") }
    Column {
        Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(t, { t = it }, Modifier.weight(1f), placeholder = { Text("Add a task") }, singleLine = true)
            IconButton({ if (t.isNotBlank()) { app.store.addTask(0, "Me", t.trim()); t = "" } }) { Icon(Icons.Default.Add, "Add") } }
        if (tasks.isEmpty()) Text("No tasks. Requests detected in private chats appear here.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn { items(tasks, key = { it.id }) { k -> ListItem({ Text(k.text, maxLines = 2) }, supportingContent = { Text(k.title) }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
            leadingContent = { Checkbox(k.done, { app.store.toggleTask(k.id) }) }, trailingContent = { IconButton({ app.store.deleteTask(k.id) }) { Icon(Icons.Default.Delete, "Delete") } }) } }
    }
}
@Composable private fun InboxPane(app: AdityaApp, open: (Long) -> Unit) {
    val flags by app.store.flags.collectAsState()
    if (flags.isEmpty()) Text("No important messages or pending questions.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    LazyColumn { items(flags, key = { it.id }) { f -> ListItem({ Text(f.title) }, Modifier.clickable { open(f.chatId) }, overlineContent = { Text(f.reason + " · " + fmtMs(f.ts)) }, supportingContent = { Text(f.text, maxLines = 3) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background), trailingContent = { IconButton({ app.store.dismissFlag(f.id) }) { Icon(Icons.Default.Close, "Dismiss") } }) } }
}
@Composable private fun DraftsPane(app: AdityaApp) {
    val d by app.engine.pending.collectAsState()
    if (d.isEmpty()) Text("No AI drafts waiting. Set a chat to AI approval mode to get drafts here.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    LazyColumn(Modifier.padding(horizontal = 24.dp)) { itemsIndexed(d) { i, (m, r) -> var e by remember(r) { mutableStateOf(r) }
        Column(Modifier.padding(vertical = 10.dp)) { Text(m.chatTitle + ": " + m.text, maxLines = 3, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(e, { e = it }, Modifier.fillMaxWidth().padding(top = 6.dp)); Text("\"— Aditya\" is appended on send.", style = MaterialTheme.typography.labelSmall)
            Row { TextButton({ app.engine.approve(i, e) }) { Text("Send") }; TextButton({ app.engine.reject(i) }) { Text("Discard") } } } } }
}
@Composable private fun MemoryPane(app: AdityaApp) {
    val notes by app.store.notes.collectAsState(); var on by remember { mutableStateOf(app.store.bool("memory", true)) }; var t by remember { mutableStateOf("") }
    Column {
        Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) { Text("Use memory in AI replies", Modifier.weight(1f)); Switch(on, { on = it; app.store.setBool("memory", it) }) }
        Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(t, { t = it }, Modifier.weight(1f), placeholder = { Text("Add a fact about you") }, singleLine = true)
            IconButton({ if (t.isNotBlank()) { app.store.addNote(0, "You", "fact", t.trim()); t = "" } }) { Icon(Icons.Default.Add, "Add") } }
        val mem = notes.filter { it.kind != "briefing" }
        LazyColumn(Modifier.weight(1f, false)) { items(mem, key = { it.id }) { n -> ListItem({ Text(n.text, maxLines = 6) }, overlineContent = { Text(n.kind + " · " + n.title) }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
            trailingContent = { IconButton({ app.store.deleteNote(n.id) }) { Icon(Icons.Default.Delete, "Delete") } }) } }
        if (mem.isEmpty()) Text("Memory is empty. Chat summaries and facts you add are stored here, encrypted on this device.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else TextButton({ app.store.clearMemory() }, Modifier.padding(horizontal = 16.dp)) { Text("Clear all memory") }
    }
}
