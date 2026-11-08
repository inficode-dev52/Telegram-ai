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
