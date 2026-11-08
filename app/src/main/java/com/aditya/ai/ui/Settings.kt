package com.aditya.ai.ui

import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.view.WindowManager
import com.aditya.ai.AdityaApp
import kotlinx.coroutines.launch

@Composable private fun Sw(l: String, v: Boolean, sub: String? = null, f: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) { Text(l); if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(v, f) }

@Composable private fun Chips(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(options) { (label, v) -> FilterChip(selected == v, { onPick(v) }, { Text(label) }) } }
}

@Composable fun SettingsScreen(app: AdityaApp) {
    val ctx = LocalContext.current; val st = app.store; val v2 = app.v2; val scope = rememberCoroutineScope()
    val conn by app.tg.connection.collectAsStateWithLifecycle(); val human by app.engine.humanMode.collectAsStateWithLifecycle(); val theme by app.theme.collectAsStateWithLifecycle()
    val intelOn by app.intel.enabled.collectAsStateWithLifecycle(); val aiOn by app.intel.aiEnabled.collectAsStateWithLifecycle()
    val chats by app.tg.chats.collectAsStateWithLifecycle(); val voices by app.voice.voices.collectAsStateWithLifecycle(); val vStatus by app.voice.status.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf(app.ai.apiKey) }; var model by remember { mutableStateOf(app.ai.model) }; var test by remember { mutableStateOf<String?>(null) }
    var notif by remember { mutableStateOf(st.bool("notif", true)) }; var prev by remember { mutableStateOf(st.bool("preview", true)) }
    var lock by remember { mutableStateOf(st.bool("lock", false)) }; var sec by remember { mutableStateOf(st.bool("secure", false)) }; var confirmOut by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(v2.str("assistant_name", "Aditya")) }; var tone by remember { mutableStateOf(v2.str("tone", "professional")) }
    var lang by remember { mutableStateOf(v2.str("lang", "")) }; var intro by remember { mutableStateOf(v2.str("intro_mode", "auto")) }
    var reduce by remember { mutableStateOf(v2.bool("reduce_motion", false)) }; var tts by remember { mutableStateOf(v2.bool("tts_on", false)) }
    var rate by remember { mutableFloatStateOf(v2.float("tts_rate", 1f)) }; var vol by remember { mutableFloatStateOf(v2.float("tts_volume", 1f)) }
    var voiceName by remember { mutableStateOf(v2.str("tts_voice", "")) }; var showVoices by remember { mutableStateOf(false) }; var showChats by remember { mutableStateOf(false) }
    var mem by remember { mutableStateOf(v2.bool("mem_on", true)) }
    LaunchedEffect(Unit) { app.voice.prepare() }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Title("Settings")
        Text("Telegram: $conn", color = MaterialTheme.colorScheme.onSurfaceVariant); TextButton({ confirmOut = true }) { Text("Log out of Telegram") }

        Section("Assistant")
        OutlinedTextField(name, { name = it; v2.setStr("assistant_name", it.trim()) }, Modifier.fillMaxWidth(), label = { Text("Assistant name") }, singleLine = true)
        Spacer(Modifier.height(8.dp)); Text("Tone", style = MaterialTheme.typography.labelMedium)
        Chips(listOf("Professional" to "professional", "Friendly" to "friendly", "Concise" to "concise"), tone) { tone = it; v2.setStr("tone", it) }
        Spacer(Modifier.height(8.dp)); Text("Language (replies and voice)", style = MaterialTheme.typography.labelMedium)
        Chips(listOf("Device" to "", "English" to "en-US", "हिन्दी" to "hi-IN", "मराठी" to "mr-IN", "Español" to "es-ES", "Français" to "fr-FR"), lang) { lang = it; v2.setStr("lang", it) }

        Section("Voice")
        Sw("Speak assistant replies", tts) { tts = it; v2.setBool("tts_on", it) }
        Text("Speech speed ${"%.1f".format(rate)}×", style = MaterialTheme.typography.labelMedium); Slider(rate, { rate = it }, valueRange = 0.5f..2f, onValueChangeFinished = { v2.setFloat("tts_rate", rate) })
        Text("Volume ${(vol * 100).toInt()}%", style = MaterialTheme.typography.labelMedium); Slider(vol, { vol = it }, valueRange = 0.2f..1f, onValueChangeFinished = { v2.setFloat("tts_volume", vol) })
        Text("Voice: " + (voiceName.ifBlank { "device default" }), style = MaterialTheme.typography.bodySmall)
        Row { TextButton({ showVoices = true }, enabled = voices.isNotEmpty()) { Text("Choose voice") }
            TextButton({ app.voice.speak("Hello, I am ${name.ifBlank { "Aditya" }}. How can I help?") }) { Text("Test voice") } }
        if (voices.isEmpty()) Text("Voices appear here once the device's text-to-speech engine is ready. Available voices depend on your device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        vStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Text("The microphone is only used after you tap the mic button. There is no always-on listening.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Section("Appearance and startup")
        Chips(listOf("System" to "system", "Light" to "light", "Dark" to "dark"), theme) { app.theme.value = it; st.setStr("theme", it) }
        Spacer(Modifier.height(8.dp)); Text("Startup animation", style = MaterialTheme.typography.labelMedium)
        Chips(listOf("Full once, then short" to "auto", "Always full" to "full", "Always short" to "short", "Off" to "off"), intro) { intro = it; v2.setStr("intro_mode", it) }
        Sw("Reduce motion", reduce, "Stills the orb and skips animations. Also follows the system animation setting.") { reduce = it; v2.setBool("reduce_motion", it); app.reduceMotion.value = it }

        Section("AI (OpenRouter)")
        OutlinedTextField(key, { key = it; app.ai.apiKey = it.trim() }, Modifier.fillMaxWidth(), label = { Text("API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(model, { model = it; app.ai.model = it.trim() }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Model, e.g. openrouter/auto") }, singleLine = true)
        Row(verticalAlignment = Alignment.CenterVertically) { TextButton({ scope.launch { test = "Testing…"; test = app.ai.chat("Reply with OK.", listOf("user" to "ping")).fold({ "Connected: " + it.take(40) }, { it.message }) } }) { Text("Test connection") }; Text(test ?: "", style = MaterialTheme.typography.labelMedium) }

        Section("Message intelligence")
        Sw("Analyse my chats", intelOn, "Detects important messages, replies you owe, dates and requests from recent messages in allowed chats. Runs on this device.") { app.intel.setEnabled(it); if (it) scope.launch { app.intel.scan() } }
        Sw("Use AI classification", aiOn, "Sends short excerpts of allowed chats to OpenRouter for better detection. Off by default; rules are used otherwise.") { app.intel.setAi(it) }
        TextButton({ showChats = true }) { Text("Choose which chats are analysed") }
        TextButton({ v2.clearInsights() }) { Text("Clear detected items") }

        Section("Memory")
        Sw("Assistant memory", mem, "Stores only notes you add or approve. Manage them in Intelligence → Memory.") { mem = it; v2.setBool("mem_on", it) }

        Section("Automation")
        Sw("Human mode (stops all AI replies)", human) { app.engine.setHuman(it); st.log("mode", "Human mode ${if (it) "on" else "off"}") }
        Text("Per-chat OFF / AUTO / APPROVAL is set on the Chats tab (tap the “AI …” label). AI replies are never sent from suggestions without your tap on Send.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Section("Notifications")
        Sw("Message notifications (when app is closed)", notif) { notif = it; st.setBool("notif", it) }
        Sw("Show message previews", prev, "Also controls whether reminders show task text.") { prev = it; st.setBool("preview", it) }

        Section("Privacy")
        Sw("App lock (biometrics / device credential)", lock) { on -> if (on && BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) != BiometricManager.BIOMETRIC_SUCCESS) Toast.makeText(ctx, "Set up a screen lock or biometrics in Android settings first", Toast.LENGTH_LONG).show() else { lock = on; st.setBool("lock", on) } }
        Sw("Hide content in recents and block screenshots", sec) { sec = it; st.setBool("secure", it); ctx.findActivity()?.window?.let { w -> if (it) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) } }
        Text("Your data stays on this device in encrypted storage. Message text is sent to OpenRouter only when you use the assistant, reply drafts, summaries, or enable AI classification. The OpenRouter key is stored encrypted and never logged.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Section("Data"); TextButton({ st.clearEvents() }) { Text("Clear activity history") }; TextButton({ st.clearMemory() }) { Text("Clear reply memory") }

        Section("About")
        Text("ADITYA AI ${com.aditya.ai.BuildConfig.VERSION_NAME} (${com.aditya.ai.BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodyMedium)
        Text("YOUR PERSONAL INTELLIGENCE SYSTEM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
    }
    if (confirmOut) AlertDialog({ confirmOut = false }, { TextButton({ confirmOut = false; app.tg.logout() }) { Text("Log out") } }, dismissButton = { TextButton({ confirmOut = false }) { Text("Cancel") } }, title = { Text("Log out?") }, text = { Text("You will need to sign in again with your phone number.") })
    if (showVoices) AlertDialog({ showVoices = false }, { TextButton({ showVoices = false }) { Text("Close") } }, title = { Text("Voice") }, text = {
        LazyColumn { item { TextButton({ voiceName = ""; v2.setStr("tts_voice", ""); showVoices = false }) { Text("Device default") } }
            items(voices) { v -> TextButton({ voiceName = v.name; v2.setStr("tts_voice", v.name); app.voice.speak("This is how I sound."); showVoices = false }) { Text("${v.locale} · ${v.name}" + if (v.needsNetwork) " (online)" else "") } } } })
    if (showChats) AlertDialog({ showChats = false }, { TextButton({ showChats = false }) { Text("Done") } }, title = { Text("Analysed chats") }, text = {
        LazyColumn { items(chats.filter { !it.archived }, key = { it.id }) { c -> var on by remember(c.id) { mutableStateOf(app.intel.isChatAllowed(c)) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(c.title, maxLines = 1); Text(c.kind, style = MaterialTheme.typography.labelSmall) }
                Switch(on, { on = it; app.intel.setChatAllowed(c.id, it) }) } } } })
}
