package com.aditya.ai.ui
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import android.widget.Toast
import com.aditya.ai.AdityaApp
import com.aditya.ai.ai.*
import com.aditya.ai.td.Msg
import kotlinx.coroutines.*

@OptIn(ExperimentalFoundationApi::class)
@Composable fun ChatScreen(app: AdityaApp, chatId: Long, back: () -> Unit) {
    val tg = app.tg; val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val chats by tg.chats.collectAsState(); val chat = chats.firstOrNull { it.id == chatId }; val msgs by tg.messages.collectAsState(); val err by tg.error.collectAsState()
    var text by remember { mutableStateOf("") }; var reply by remember { mutableStateOf<Msg?>(null) }; var editing by remember { mutableStateOf<Msg?>(null) }
    var fwd by remember { mutableStateOf<Msg?>(null) }; var menu by remember { mutableStateOf<Long?>(null) }; var topMenu by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<String?>(null) }; var working by remember { mutableStateOf(false) }; var mode by remember { mutableStateOf(app.engine.chatMode(chatId)) }
    DisposableEffect(chatId) { tg.openChat(chatId); onDispose { tg.closeChat() } }
    val list = rememberLazyListState(); LaunchedEffect(msgs.lastOrNull()?.id) { if (msgs.isNotEmpty()) list.scrollToItem(msgs.size) }
    val title = chat?.title ?: ""
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { scope.launch {
        val f = withContext(Dispatchers.IO) { copyUri(ctx, it) }; val img = (ctx.contentResolver.getType(it) ?: "").startsWith("image/")
        tg.sendFile(chatId, f.absolutePath, img, text.trim(), reply?.id ?: 0L); text = ""; reply = null } } }
    fun send() { val t = text.trim(); if (t.isEmpty()) return; val e = editing
        if (e != null) { tg.edit(chatId, e.id, t); editing = null } else tg.sendText(chatId, t, reply?.id ?: 0L); text = ""; reply = null }
    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(back) { Icon(Icons.Default.ArrowBack, "Back") }; Avatar(title, chat?.photoId ?: 0, tg, 38.dp); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(chat?.kind ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Box { IconButton({ topMenu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                DropdownMenu(topMenu, { topMenu = false }) {
                    DropdownMenuItem({ Text("AI mode: ${mode.name.lowercase()}") }, { mode = Mode.values()[(mode.ordinal + 1) % 3]; app.engine.setChatMode(chatId, mode, title) }, leadingIcon = { Icon(Icons.Default.AutoAwesome, null) })
                    DropdownMenuItem({ Text("Summarize chat") }, { topMenu = false; scope.launch { working = true; app.brain.summarize(chatId, title).fold({ summary = it }, { toast(it.message ?: "Failed") }); working = false } }, leadingIcon = { Icon(Icons.Default.Summarize, null) })
                    DropdownMenuItem({ Text("Draft reply with AI") }, { topMenu = false; scope.launch { working = true; app.engine.generate(chatId, title).fold({ text = it + SIGNATURE }, { toast(it.message ?: "Failed") }); working = false } }, leadingIcon = { Icon(Icons.Default.EditNote, null) }) } }
        }
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = list, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { TextButton({ tg.loadOlder() }, Modifier.fillMaxWidth()) { Text("Load earlier messages") } }
            items(msgs, key = { it.id }) { m ->
                val quoted = if (m.replyTo != 0L) msgs.firstOrNull { it.id == m.replyTo } else null
                Box(Modifier.fillMaxWidth(), contentAlignment = if (m.out) Alignment.CenterEnd else Alignment.CenterStart) {
                    Column(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp)).background(if (m.out) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                        .combinedClickable(onClick = {}, onLongClick = { menu = m.id }).padding(10.dp)) {
                        if (!m.out && chat?.kind == "Group") Text(m.sender, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        quoted?.let { Text(it.text.ifBlank { "[${it.kind}]" }, Modifier.padding(bottom = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                        if (m.kind == "photo") { val files by tg.files.collectAsState(); LaunchedEffect(m.fileId) { tg.ensureFile(m.fileId) }
                            files[m.fileId]?.let { FileImage(it, Modifier.size(220.dp).clip(RoundedCornerShape(10.dp))) } ?: Box(Modifier.size(220.dp, 140.dp).shimmer()) }
                        if (m.kind == "doc") Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Description, null); Spacer(Modifier.width(8.dp)); Text(m.text.lines().first()) }
                        else if (m.text.isNotBlank()) Text(m.text)
                        Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                            if (m.edited) Text("edited ", style = MaterialTheme.typography.labelSmall); Text(fmtSec(m.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (m.out) Icon(when (m.status) { "sending" -> Icons.Default.Schedule; "read" -> Icons.Default.DoneAll; "failed" -> Icons.Default.ErrorOutline; else -> Icons.Default.Check }, m.status,
                                Modifier.padding(start = 4.dp).size(14.dp), tint = if (m.status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                    }
                    DropdownMenu(menu == m.id, { menu = null }) {
                        DropdownMenuItem({ Text("Reply") }, { reply = m; editing = null; menu = null })
                        DropdownMenuItem({ Text("Forward") }, { fwd = m; menu = null })
                        if (m.out && m.kind == "text") DropdownMenuItem({ Text("Edit") }, { editing = m; reply = null; text = m.text; menu = null })
                        DropdownMenuItem({ Text("Delete") }, { tg.delete(chatId, m.id); menu = null }) } }
            }
        }
        err?.let { Text(it, Modifier.padding(horizontal = 16.dp).clickable { tg.error.value = null }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) }
        (reply ?: editing)?.let { r -> Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text((if (editing != null) "Editing: " else "Replying: ") + r.text.take(60), Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            IconButton({ reply = null; editing = null; text = "" }) { Icon(Icons.Default.Close, "Cancel") } } }
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Bottom) {
            IconButton({ app.suppressLock = true; pick.launch("*/*") }) { Icon(Icons.Default.AttachFile, "Attach") }
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Message") }, maxLines = 5, shape = RoundedCornerShape(24.dp))
            IconButton(::send, enabled = text.isNotBlank()) { Icon(Icons.Default.Send, "Send", tint = MaterialTheme.colorScheme.primary) } }
    }
    summary?.let { AlertDialog({ summary = null }, { TextButton({ summary = null }) { Text("Close") } }, title = { Text("Summary") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(it); Text("Saved to Brain memory.", style = MaterialTheme.typography.labelSmall) } }) }
    fwd?.let { m -> AlertDialog({ fwd = null }, { TextButton({ fwd = null }) { Text("Cancel") } }, title = { Text("Forward to") }, text = {
        LazyColumn { items(chats.filter { !it.archived && it.kind != "Channel" }.take(40)) { c -> ListItem({ Text(c.title, maxLines = 1) }, Modifier.clickable { tg.forward(c.id, chatId, m.id); fwd = null }, leadingContent = { Avatar(c.title, c.photoId, tg, 36.dp) }) } } }) }
}
