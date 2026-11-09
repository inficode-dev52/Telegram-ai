package com.aditya.ai.ui

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aditya.ai.AdityaApp
import com.aditya.ai.data.*
import com.aditya.ai.intel.*
import com.aditya.ai.svc.Reminders
import com.aditya.ai.td.Incoming
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

private val segs = listOf("Inbox", "Briefing", "Tasks", "Replies", "Memory", "Activity")

@Composable fun IntelligenceScreen(app: AdityaApp, initial: Int, open: (Long) -> Unit) {
    var seg by remember(initial) { mutableIntStateOf(initial) }
    Column(Modifier.fillMaxSize()) {
        Title("Intelligence")
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(segs.withIndex().toList()) { (i, n) -> FilterChip(seg == i, { seg = i }, { Text(n) }) } }
        Spacer(Modifier.height(8.dp))
        AnimatedContent(seg, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "seg") { s ->
            when (s) { 0 -> InboxPane(app, open); 1 -> BriefingPane(app, open); 2 -> TasksPane(app); 3 -> RepliesPane(app, open); 4 -> MemoryPane(app); else -> ActivityScreen(app, "all", false) }
        }
    }
}

private fun fmt(ms: Long) = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(ms))

@Composable private fun IntelHeader(app: AdityaApp) {
    val on by app.intel.enabled.collectAsStateWithLifecycle(); val scanning by app.intel.scanning.collectAsStateWithLifecycle()
    val last by app.intel.lastScan.collectAsStateWithLifecycle(); val err by app.intel.scanError.collectAsStateWithLifecycle(); val scope = rememberCoroutineScope()
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!on) StateCard(Icons.Default.Shield, "Message intelligence is off", "Enable it to analyse the chats you allow. Nothing leaves your device unless you also enable AI classification.",
            actionLabel = "Turn on", onAction = { app.intel.setEnabled(true); scope.launch { app.intel.scan() } })
        else Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (last > 0) "Last scan ${fmt(last)}" else "Not scanned yet", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button({ scope.launch { app.intel.scan() } }, enabled = !scanning) { Text(if (scanning) "Scanning…" else "Scan now") }
        }
        if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        err?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Ax.Warn) }
    }
}

// ------------------------------------------------------------------ Inbox
@Composable private fun InboxPane(app: AdityaApp, open: (Long) -> Unit) {
    val all by app.v2.insights.collectAsStateWithLifecycle(); val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf<Category?>(null) }
    val f = filter
    val list = BriefingBuilder.active(all).filter { f == null || f in it.categories }.sortedWith(compareBy({ it.priority.ordinal }, { -it.ts }))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { IntelHeader(app) }
        item { LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(filter == null, { filter = null }, { Text("All") }) }
            items(listOf(Category.IMPORTANT, Category.URGENT, Category.REPLY, Category.MEETING, Category.DEADLINE, Category.REQUEST, Category.EVENT, Category.DOCUMENT, Category.FOLLOW_UP)) { c ->
                FilterChip(filter == c, { filter = c }, { Text(c.label) }) } } }
        if (list.isEmpty()) item { StateCard(Icons.Default.Inbox, "Nothing detected", "No items match. Detections come from messages in the last 3 days in chats you allow.", Modifier.padding(horizontal = 20.dp)) }
        items(list, key = { it.id }) { i ->
            GlassCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tag(i.priority.label + " priority", priorityColor(i.priority)); i.categories.take(3).forEach { Tag(it.label) } }
                Spacer(Modifier.height(8.dp))
                Text("CONFIRMED (from Telegram)", style = MaterialTheme.typography.labelSmall, color = Ax.Ok)
                Text("${i.sender} · ${i.chatTitle} · ${fmt(i.ts)}", style = MaterialTheme.typography.labelMedium)
                Text("“${i.excerpt}”", style = MaterialTheme.typography.bodyMedium, maxLines = 5)
                if (i.dateText.isNotEmpty()) Text("Date in message: ${i.dateText}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(if (i.viaAi) "AI INTERPRETATION" else "RULE-BASED INTERPRETATION", style = MaterialTheme.typography.labelSmall, color = Ax.Blue)
                Text(i.reason, style = MaterialTheme.typography.bodySmall)
                if (i.uncertain.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text("UNCERTAIN", style = MaterialTheme.typography.labelSmall, color = Ax.Warn); Text(i.uncertain, style = MaterialTheme.typography.bodySmall) }
                Row {
                    TextButton({ open(i.chatId) }) { Text("Open chat") }
                    if (Category.REPLY in i.categories) TextButton({ scope.launch { suggestReply(app, i, ctx) } }) { Text("Suggest reply") }
                    Spacer(Modifier.weight(1f)); TextButton({ app.v2.dismissInsight(i.id) }) { Text("Dismiss") }
                }
            }
        }
    }
}

/** Generates an AI draft and puts it in the review queue. It is never sent from here. */
private suspend fun suggestReply(app: AdityaApp, i: Insight, ctx: android.content.Context) {
    app.engine.generate(i.chatId, i.chatTitle).fold({
        app.engine.addDraft(Incoming(i.chatId, i.msgId, i.excerpt, i.sender, i.chatTitle, "Private", true, false), it)
        Toast.makeText(ctx, "Draft saved to Replies for your review", Toast.LENGTH_LONG).show()
    }, { Toast.makeText(ctx, it.message ?: "Couldn't create a draft", Toast.LENGTH_LONG).show() })
}

// ------------------------------------------------------------------ Briefing
@Composable private fun BriefingPane(app: AdityaApp, open: (Long) -> Unit) {
    val insights by app.v2.insights.collectAsStateWithLifecycle(); val tasks by app.v2.tasks.collectAsStateWithLifecycle(); val chats by app.tg.chats.collectAsStateWithLifecycle()
    val scanning by app.intel.scanning.collectAsStateWithLifecycle(); val intelOn by app.intel.enabled.collectAsStateWithLifecycle(); val scope = rememberCoroutineScope()
    var aiText by remember { mutableStateOf(app.v2.str("briefing_ai", "")) }; var aiTs by remember { mutableStateOf(app.v2.long("briefing_ai_ts", 0L)) }
    var aiBusy by remember { mutableStateOf(false) }; var aiErr by remember { mutableStateOf<String?>(null) }
    val data = remember(insights, tasks, chats) {
        BriefingBuilder.build(System.currentTimeMillis(), insights, tasks, chats.filter { !it.archived && !it.muted && app.intel.isChatAllowed(it) }.map { UnreadChat(it.id, it.title, it.unread, it.lastText, it.lastDate) }) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Generated ${fmt(data.generatedAt)} from live data", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button({ if (intelOn) scope.launch { app.intel.scan() } }, enabled = intelOn && !scanning) { Icon(Icons.Default.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Refresh") }
        }
        if (!intelOn) Text("Message intelligence is off: only unread counts and tasks are shown.", style = MaterialTheme.typography.bodySmall, color = Ax.Warn)
        if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (data.isEmpty) StateCard(Icons.Default.WbSunny, "Nothing to brief yet", "No important items, unread messages, replies, dates or tasks right now.")
        data.sections.filter { it.items.isNotEmpty() }.forEach { s ->
            GlassCard(Modifier.fillMaxWidth()) {
                Text(s.title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                s.items.take(8).forEach { it2 ->
                    Column(Modifier.fillMaxWidth().clickable(enabled = it2.chatId != 0L) { open(it2.chatId) }.padding(vertical = 6.dp)) {
                        Text(it2.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(it2.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                        if (it2.ts > 0) Text(fmt(it2.ts), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (!data.isEmpty) GlassCard(Modifier.fillMaxWidth()) {
            Text("AI NARRATIVE (interpretation of the data above)", style = MaterialTheme.typography.labelMedium, color = Ax.Blue)
            if (aiText.isNotEmpty()) { Text(aiText); Text("Generated ${fmt(aiTs)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            aiErr?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            TextButton({ scope.launch {
                aiBusy = true; aiErr = null
                app.ai.chat("Write a concise daily briefing from this data only. Do not invent items. Do not say tasks or meetings were created. Use short bullets.", listOf("user" to data.toPlainText()))
                    .fold({ aiText = it; aiTs = System.currentTimeMillis(); app.v2.setStr("briefing_ai", it); app.v2.setLong("briefing_ai_ts", aiTs) }, { aiErr = it.message })
                aiBusy = false } }, enabled = !aiBusy) { Text(if (aiBusy) "Writing…" else "Write AI summary") }
        }
        Spacer(Modifier.height(16.dp))
    }
}

// ------------------------------------------------------------------ Tasks
@Composable private fun TasksPane(app: AdityaApp) {
    val tasks by app.v2.tasks.collectAsStateWithLifecycle(); val legacy by app.store.tasks.collectAsStateWithLifecycle(); val ctx = LocalContext.current
    var t by remember { mutableStateOf("") }; var edit by remember { mutableStateOf<TaskV2?>(null) }; var remind by remember { mutableStateOf<TaskV2?>(null) }
    val suggested = tasks.filter { it.status == TaskStatus.SUGGESTED }; val active = tasks.filter { it.status == TaskStatus.ACTIVE }; val done = tasks.filter { it.status == TaskStatus.DONE }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(t, { t = it }, Modifier.weight(1f), placeholder = { Text("Add a task") }, singleLine = true)
            IconButton({ if (t.isNotBlank()) { app.v2.addManualTask(t.trim()); t = "" } }) { Icon(Icons.Default.Add, "Add task") } } }
        if (suggested.isNotEmpty()) item { Section("Suggested — needs your approval") }
        items(suggested, key = { "s" + it.id }) { k ->
            GlassCard(Modifier.fillMaxWidth()) {
                TaskBody(k)
                Row { TextButton({ app.v2.editTask(k.id, TaskLogic::approve) }) { Text("Approve") }; TextButton({ edit = k }) { Text("Edit") }; TextButton({ app.v2.editTask(k.id, TaskLogic::dismiss) }) { Text("Dismiss") } } } }
        item { Section("Active") }
        if (active.isEmpty() && legacy.none { !it.done }) item { Text("No active tasks.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(active, key = { "a" + it.id }) { k ->
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.Top) { Checkbox(false, { app.v2.editTask(k.id) { x -> TaskLogic.setDone(x, true) } }); Column(Modifier.weight(1f)) { TaskBody(k) } }
                Row { TextButton({ edit = k }) { Text("Edit") }; TextButton({ remind = k }) { Text("Remind me") }; Spacer(Modifier.weight(1f)); TextButton({ app.v2.deleteTask(k.id) }) { Text("Delete") } } } }
        items(legacy.filter { !it.done }, key = { "l" + it.id }) { k ->
            GlassCard(Modifier.fillMaxWidth()) { Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(false, { app.store.toggleTask(k.id) }); Column(Modifier.weight(1f)) { Text(k.text); Text(k.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton({ app.store.deleteTask(k.id) }) { Icon(Icons.Default.Delete, "Delete") } } } }
        if (done.isNotEmpty()) item { Section("Completed") }
        items(done, key = { "d" + it.id }) { k -> Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(true, { app.v2.editTask(k.id) { x -> TaskLogic.setDone(x, false) } }); Text(k.title, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton({ app.v2.deleteTask(k.id) }) { Icon(Icons.Default.Delete, "Delete") } } }
    }
    edit?.let { k ->
        var title by remember(k.id) { mutableStateOf(k.title) }; var due by remember(k.id) { mutableStateOf(if (k.dueMs > 0) SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(k.dueMs)) else "") }
        val bad = due.isNotBlank() && TaskLogic.parseUserDue(due) == null
        AlertDialog({ edit = null }, { TextButton({ app.v2.editTask(k.id) { x -> val d = TaskLogic.parseUserDue(due); x.copy(title = title.trim().ifBlank { x.title }, dueMs = d ?: 0L, dueText = if (d != null) due else if (due.isBlank()) "" else x.dueText) }; edit = null }, enabled = !bad && title.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton({ edit = null }) { Text("Cancel") } }, title = { Text("Edit task") },
            text = { Column { OutlinedTextField(title, { title = it }, label = { Text("Title") }); Spacer(Modifier.height(8.dp))
                OutlinedTextField(due, { due = it }, label = { Text("Due (yyyy-MM-dd HH:mm, optional)") }, isError = bad, singleLine = true) } })
    }
    remind?.let { k ->
        val now = System.currentTimeMillis(); val opts = remember(k.id) { TaskLogic.reminderOptions(now, k.dueMs) }; var pick by remember(k.id) { mutableStateOf<Long?>(null) }
        AlertDialog({ remind = null }, { TextButton({ pick?.let { w ->
            val r = app.v2.addReminder(k.id, k.title, w); val exact = Reminders.schedule(ctx, r); Toast.makeText(ctx, if (exact) "Exact reminder set for ${fmt(w)}" else "Reminder saved for ${fmt(w)}. Android may deliver it a little late until exact-alarm access is enabled.", Toast.LENGTH_LONG).show() }; remind = null }, enabled = pick != null) { Text("Create reminder") } },
            dismissButton = { TextButton({ remind = null }) { Text("Cancel") } }, title = { Text("Create a reminder?") },
            text = { Column {
                Text("Nothing is scheduled until you confirm.", style = MaterialTheme.typography.bodySmall)
                if (!Reminders.notificationsAllowed(ctx)) Text("Notifications are disabled for this app, so the reminder would not be shown. Enable them in Android settings first.", color = Ax.Warn, style = MaterialTheme.typography.bodySmall)
                opts.forEach { (label, w) -> Row(Modifier.fillMaxWidth().clickable { pick = w }, verticalAlignment = Alignment.CenterVertically) { RadioButton(pick == w, { pick = w }); Text("$label · ${fmt(w)}") } } } })
    }
}

@Composable private fun TaskBody(k: TaskV2) {
    Text(k.title, style = MaterialTheme.typography.titleMedium)
    Text(if (k.chatId == 0L) "Added by you" else "${k.sender} · ${k.chatTitle} · ${fmt(k.msgTs)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (k.chatId != 0L) Text("“${k.original.take(200)}”", style = MaterialTheme.typography.bodySmall)
    if (k.dueMs > 0 || k.dueText.isNotEmpty()) Text("Due: " + (if (k.dueMs > 0) fmt(k.dueMs) else k.dueText), style = MaterialTheme.typography.labelMedium, color = Ax.Warn)
}

// ------------------------------------------------------------------ Replies & drafts
@Composable private fun RepliesPane(app: AdityaApp, open: (Long) -> Unit) {
    val insights by app.v2.insights.collectAsStateWithLifecycle(); val drafts by app.engine.pending.collectAsStateWithLifecycle(); val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val waiting = BriefingBuilder.active(insights).filter { Category.REPLY in it.categories }.sortedByDescending { it.ts }
    val follow = BriefingBuilder.active(insights).filter { Category.FOLLOW_UP in it.categories }.sortedByDescending { it.ts }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { IntelHeader(app) }
        item { Section("Waiting for your reply (${waiting.size})") }
        if (waiting.isEmpty()) item { Text("Nobody appears to be waiting for a reply.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(waiting, key = { "w" + it.id }) { i -> GlassCard(Modifier.fillMaxWidth()) {
            Text("${i.sender} · ${i.chatTitle} · ${fmt(i.ts)}", style = MaterialTheme.typography.labelMedium); Text("“${i.excerpt}”", maxLines = 4)
            Row { TextButton({ open(i.chatId) }) { Text("Open chat") }; TextButton({ scope.launch { suggestReply(app, i, ctx) } }) { Text("Suggest reply") } } } }
        if (follow.isNotEmpty()) item { Section("Follow-up opportunities") }
        items(follow, key = { "f" + it.id }) { i -> GlassCard(Modifier.fillMaxWidth()) {
            Text("${i.chatTitle} · your message ${fmt(i.ts)}", style = MaterialTheme.typography.labelMedium); Text("“${i.excerpt}”", maxLines = 3); Text(i.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({ open(i.chatId) }) { Text("Open chat") } } }
        item { Section("Drafts for review (${drafts.size})") }
        if (drafts.isEmpty()) item { Text("No drafts. Use “Suggest reply”, or set a chat to AI approval mode.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        itemsIndexed(drafts) { idx, pair ->
            val m = pair.first; val r = pair.second
            var e by remember(r) { mutableStateOf(r) }
            GlassCard(Modifier.fillMaxWidth()) {
                Text("${m.chatTitle}: ${m.text}", maxLines = 3, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(e, { e = it }, Modifier.fillMaxWidth().padding(top = 6.dp)); Text("“— Aditya” is appended when you send. Nothing is sent until you tap Send.", style = MaterialTheme.typography.labelSmall)
                Row { TextButton({ app.engine.approve(idx, e) }, enabled = e.isNotBlank()) { Text("Send") }; TextButton({ open(m.chatId) }) { Text("Open chat") }; Spacer(Modifier.weight(1f)); TextButton({ app.engine.reject(idx) }) { Text("Delete") } } }
        }
    }
}

// ------------------------------------------------------------------ Memory
@Composable private fun MemoryPane(app: AdityaApp) {
    val mem by app.v2.memory.collectAsStateWithLifecycle(); val legacy by app.store.notes.collectAsStateWithLifecycle()
    var on by remember { mutableStateOf(app.v2.bool("mem_on", true)) }; var t by remember { mutableStateOf("") }
    var edit by remember { mutableStateOf<MemoryItem?>(null) }; var confirm by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Assistant memory", Modifier.weight(1f)); Switch(on, { on = it; app.v2.setBool("mem_on", it) }) } }
        item { Text("Only things you add or approve are stored, encrypted on this device. Don't store passwords or other sensitive data.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(t, { t = it }, Modifier.weight(1f), placeholder = { Text("Add a note or preference") }, singleLine = true, enabled = on)
            IconButton({ if (t.isNotBlank()) { app.v2.addMemory(t); t = "" } }, enabled = on) { Icon(Icons.Default.Add, "Add") } } }
        if (mem.isEmpty()) item { Text("No notes yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(mem, key = { it.id }) { n -> GlassCard(Modifier.fillMaxWidth()) { Text(n.text); Row { Text(fmt(n.ts), Modifier.weight(1f).padding(top = 12.dp), style = MaterialTheme.typography.labelSmall)
            IconButton({ edit = n }) { Icon(Icons.Default.Edit, "Edit") }; IconButton({ app.v2.deleteMemory(n.id) }) { Icon(Icons.Default.Delete, "Delete") } } } }
        if (mem.isNotEmpty()) item { TextButton({ confirm = true }) { Text("Clear all assistant memory") } }
        val old = legacy.filter { it.kind != "briefing" }
        if (old.isNotEmpty()) { item { Section("Reply memory (used for AI replies)") }
            items(old, key = { "o" + it.id }) { n -> ListItem({ Text(n.text, maxLines = 4) }, overlineContent = { Text(n.kind + " · " + n.title) }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                trailingContent = { IconButton({ app.store.deleteNote(n.id) }) { Icon(Icons.Default.Delete, "Delete") } }) } }
    }
    edit?.let { n -> var s by remember(n.id) { mutableStateOf(n.text) }
        AlertDialog({ edit = null }, { TextButton({ app.v2.editMemory(n.id, s); edit = null }, enabled = s.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton({ edit = null }) { Text("Cancel") } },
            title = { Text("Edit note") }, text = { OutlinedTextField(s, { s = it }) }) }
    if (confirm) AlertDialog({ confirm = false }, { TextButton({ app.v2.clearMemory(); confirm = false }) { Text("Clear") } }, dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
        title = { Text("Clear assistant memory?") }, text = { Text("All notes saved by the assistant feature will be deleted from this device.") })
}
