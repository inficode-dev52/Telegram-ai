package com.aditya.ai.ui

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aditya.ai.AdityaApp
import com.aditya.ai.ai.OrbState
import com.aditya.ai.data.AssistantMsg

@Composable fun AssistantScreen(app: AdityaApp) {
    val eng = app.assistant; val voice = app.voice; val ctx = LocalContext.current; val clip = LocalClipboardManager.current
    val msgs by eng.messages.collectAsStateWithLifecycle(); val partial by eng.partial.collectAsStateWithLifecycle(); val busy by eng.busy.collectAsStateWithLifecycle()
    val orb by eng.orb.collectAsStateWithLifecycle(); val pendingMem by eng.pendingMemory.collectAsStateWithLifecycle()
    val level by voice.level.collectAsStateWithLifecycle(); val status by voice.status.collectAsStateWithLifecycle(); val listening by voice.listening.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }; var confirmClear by remember { mutableStateOf(false) }
    var speak by remember { mutableStateOf(app.v2.bool("tts_on", false)) }
    val name = app.v2.str("assistant_name", "Aditya").ifBlank { "Aditya" }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size, partial?.length, busy) { val n = msgs.size + (if (partial != null) 1 else 0) + (if (busy && partial.isNullOrEmpty()) 1 else 0); if (n > 0) list.animateScrollToItem(n - 1) }
    fun startMic() { app.voice.startListening { eng.ask(it) } }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) startMic() else voice.status.value = "Microphone permission was denied, so voice input is unavailable." }
    fun send() { val t = text.trim(); if (t.isNotEmpty()) { eng.ask(t); text = "" } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(name, style = MaterialTheme.typography.headlineSmall); Text(orbLabel(orb), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            IconButton({ speak = !speak; app.v2.setBool("tts_on", speak); if (!speak) voice.stopSpeaking() }) { Icon(if (speak) Icons.Default.VolumeUp else Icons.Default.VolumeOff, if (speak) "Spoken replies on" else "Spoken replies off") }
            IconButton({ confirmClear = true }, enabled = msgs.isNotEmpty()) { Icon(Icons.Default.DeleteSweep, "Clear conversation") }
        }
        Box(Modifier.fillMaxWidth(), Alignment.Center) { AiOrb(orb, size = 150.dp, level = level) }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (msgs.isEmpty() && partial == null) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ask me about your Telegram data:", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf("What are my important messages today?", "Who is waiting for my reply?", "What tasks are pending?", "Find the message about tomorrow's meeting", "Show my recent important notifications").forEach { q ->
                        AssistChip({ eng.ask(q) }, { Text(q) }) }
                }
            }
            items(msgs, key = { it.id }) { m -> Bubble(m, onCopy = { clip.setText(AnnotatedString(m.text)); Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show() }, onRetry = { eng.retry() }, canRetry = !busy && m.id == msgs.last().id) }
            partial?.let { p -> if (p.isNotEmpty()) item { Bubble(AssistantMsg(-1, "assistant", p, 0), {}, {}, false) } else item { Thinking() } }
            if (busy && partial == null) item { Thinking() }
        }
        pendingMem?.let { GlassCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()) {
            Text("Save to memory?", style = MaterialTheme.typography.titleMedium); Text(it, style = MaterialTheme.typography.bodySmall)
            Row { TextButton({ eng.confirmMemory() }) { Text("Save") }; TextButton({ eng.declineMemory() }) { Text("Don't save") } } } }
        status?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) }
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Bottom) {
            IconButton({
                if (listening) voice.stopListening()
                else if (!voice.recognitionAvailable()) voice.status.value = "Speech recognition isn't available on this device."
                else if (!voice.hasMicPermission()) { app.suppressLock = true; perm.launch(Manifest.permission.RECORD_AUDIO) }
                else startMic()
            }) { Icon(if (listening) Icons.Default.MicOff else Icons.Default.Mic, if (listening) "Stop listening" else "Tap to talk", tint = if (listening) Ax.Ok else MaterialTheme.colorScheme.primary) }
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Ask $name…") }, maxLines = 4, shape = RoundedCornerShape(24.dp))
            if (busy) IconButton({ eng.stop() }) { Icon(Icons.Default.Stop, "Stop") }
            else IconButton(::send, enabled = text.isNotBlank()) { Icon(Icons.Default.Send, "Send", tint = MaterialTheme.colorScheme.primary) }
        }
    }
    if (confirmClear) AlertDialog({ confirmClear = false },
        { TextButton({ confirmClear = false; eng.clear() }) { Text("Clear") } }, dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } },
        title = { Text("Clear conversation?") }, text = { Text("This deletes the assistant chat history on this device. Your Telegram chats and saved memory are not affected.") })
}

@Composable private fun Bubble(m: AssistantMsg, onCopy: () -> Unit, onRetry: () -> Unit, canRetry: Boolean) {
    val user = m.role == "user"
    Box(Modifier.fillMaxWidth(), if (user) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(18.dp))
            .background(when { user -> MaterialTheme.colorScheme.primaryContainer; m.error -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f); else -> MaterialTheme.colorScheme.surfaceVariant }).padding(12.dp)) {
            Text(m.text, color = if (m.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (!user && m.id != -1L) Row {
                if (m.error) { if (canRetry) TextButton(onRetry) { Icon(Icons.Default.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Retry") } }
                else IconButton(onCopy, Modifier.size(32.dp)) { Icon(Icons.Default.ContentCopy, "Copy response", Modifier.size(16.dp)) }
            }
        }
    }
}

@Composable private fun Thinking() {
    val reduce = LocalReduceMotion.current
    val t = rememberInfiniteTransition(label = "dots")
    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 160), RepeatMode.Reverse), label = "d$i")
            Box(Modifier.size(8.dp).alpha(if (reduce) 0.7f else a).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary))
        }
    }
}
