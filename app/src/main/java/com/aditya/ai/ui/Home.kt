package com.aditya.ai.ui
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.aditya.ai.AdityaApp
import com.aditya.ai.ai.Mode
import java.util.*

@Composable fun HomeScreen(app: AdityaApp, open: (Long) -> Unit, go: (String) -> Unit) {
    val tg = app.tg; val chats by tg.chats.collectAsState(); val loaded by tg.loaded.collectAsState(); val conn by tg.connection.collectAsState(); val online by tg.online.collectAsState()
    val events by app.store.events.collectAsState(); val tasks by app.store.tasks.collectAsState(); val flags by app.store.flags.collectAsState()
    val drafts by app.engine.pending.collectAsState(); val human by app.engine.humanMode.collectAsState()
    val cal = Calendar.getInstance(); val hour = cal.get(Calendar.HOUR_OF_DAY)
    val day = cal.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }.timeInMillis
    val unread = chats.filter { !it.archived && !it.muted }.sumOf { it.unread }; val aiToday = events.count { it.type == "ai_reply" && it.ts >= day }
    val open_ = tasks.count { !it.done } + drafts.size + flags.size; val activeChats = chats.count { app.engine.chatMode(it.id) != Mode.OFF }
    Column(Modifier.fillMaxSize()) {
        Title(when { hour < 12 -> "Good morning"; hour < 18 -> "Good afternoon"; else -> "Good evening" }) {
            IconButton({ go("search") }) { Icon(Icons.Default.Search, "Search") }; IconButton({ go("notifs") }) { Icon(Icons.Default.Notifications, "Notifications") } }
        Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Pill(if (online) conn else "Offline", online && conn == "Connected")
            Pill(if (human) "Human mode" else "AI active in $activeChats chats", !human && activeChats > 0) }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.padding(horizontal = 24.dp)) { Stat("Unread", unread, Modifier.weight(1f)); Stat("Chats", chats.count { !it.archived }, Modifier.weight(1f)) }
        Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) { Stat("AI replies today", aiToday, Modifier.weight(1f)); Stat("Open items", open_, Modifier.weight(1f)) }
        Section("Needs attention")
        if (flags.isEmpty()) Text("Nothing needs your attention.", Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        flags.take(3).forEach { f -> ListItem({ Text(f.title, maxLines = 1) }, Modifier.clickable { open(f.chatId) }, supportingContent = { Text(f.text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            overlineContent = { Text(f.reason) }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background)) }
        Section("Recent")
        if (!loaded && chats.isEmpty()) SkeletonRows(3) else chats.filter { !it.archived }.take(3).forEach { c ->
            ListItem({ Text(c.title, maxLines = 1) }, Modifier.clickable { open(c.id) }, supportingContent = { Text(c.lastText, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingContent = { Avatar(c.title, c.photoId, tg, 40.dp) }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background)) }
    }
}
@Composable private fun Stat(label: String, n: Int, m: Modifier) = Column(m) {
    AnimatedCount(n, MaterialTheme.typography.displaySmall, MaterialTheme.colorScheme.primary); Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable fun ChatsScreen(app: AdityaApp, open: (Long) -> Unit, go: (String) -> Unit) {
    val tg = app.tg; val chats by tg.chats.collectAsState(); val loaded by tg.loaded.collectAsState(); var f by remember { mutableStateOf("All") }
    val list = chats.filter { c -> if (f == "Archive") c.archived else !c.archived && when (f) { "Unread" -> c.unread > 0; "Groups" -> c.kind == "Group"; "Channels" -> c.kind == "Channel"; "Bots" -> c.kind == "Bot"; "Saved" -> c.kind == "Saved"; else -> true } }
    Column {
        Title("Chats") { IconButton({ go("search") }) { Icon(Icons.Default.Search, "Search") } }
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("All", "Unread", "Groups", "Channels", "Bots", "Saved", "Archive")) { n -> FilterChip(f == n, { f = n }, { Text(n) }) } }
        if (!loaded && chats.isEmpty()) SkeletonRows() else if (list.isEmpty()) Text("No chats here.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn { items(list, key = { it.id }) { c ->
            var m by remember(c.id) { mutableStateOf(app.engine.chatMode(c.id)) }
            ListItem({ Row(verticalAlignment = Alignment.CenterVertically) { Text(c.title, maxLines = 1, modifier = Modifier.weight(1f, false))
                    if (c.pinned) Icon(Icons.Default.PushPin, "Pinned", Modifier.padding(start = 6.dp).size(14.dp)); if (c.muted) Icon(Icons.Default.NotificationsOff, "Muted", Modifier.padding(start = 6.dp).size(14.dp)) } },
                Modifier.clickable { open(c.id) }, supportingContent = { Text(c.lastText, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingContent = { Avatar(c.title, c.photoId, tg) },
                trailingContent = { Column(horizontalAlignment = Alignment.End) { Text(fmtSec(c.lastDate), style = MaterialTheme.typography.labelSmall)
                    if (c.unread > 0) Badge { Text("${c.unread}") }
                    if (c.kind != "Channel") Text("AI ${m.name.lowercase()}", Modifier.clickable { m = Mode.values()[(m.ordinal + 1) % 3]; app.engine.setChatMode(c.id, m, c.title) }.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) } },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background)) } }
    }
}
