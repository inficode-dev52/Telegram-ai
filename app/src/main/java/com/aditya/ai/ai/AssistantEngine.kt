package com.aditya.ai.ai

import com.aditya.ai.data.*
import com.aditya.ai.intel.IntelEngine
import com.aditya.ai.td.Telegram
import com.aditya.ai.voice.VoiceIO
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class OrbState { IDLE, THINKING, LISTENING, SPEAKING, PROCESSING, ERROR }

/**
 * The conversational assistant. Answers are grounded in a context block built from real, accessible app data
 * (only chats the user allows). The assistant cannot send messages, create tasks or create reminders.
 */
class AssistantEngine(
    private val v2: V2Store, private val legacyEvents: StateFlow<List<Evt>>, private val legacyTasks: StateFlow<List<TaskItem>>,
    private val tg: Telegram, private val ai: Ai, private val intel: IntelEngine, private val voice: VoiceIO
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val streamer = AiStream()
    private val base = MutableStateFlow(OrbState.IDLE)
    val orb: StateFlow<OrbState> = combine(base, voice.listening, voice.speaking) { b, l, s ->
        when { b == OrbState.ERROR -> OrbState.ERROR; l -> OrbState.LISTENING; s -> OrbState.SPEAKING; else -> b }
    }.stateIn(scope, SharingStarted.Eagerly, OrbState.IDLE)
    val messages: StateFlow<List<AssistantMsg>> get() = v2.chat
    val partial = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val pendingMemory = MutableStateFlow<String?>(null)
    private var job: Job? = null
    private val weakWords = setOf("the", "and", "for", "with", "group", "chat", "team", "new", "all")
    private val rememberRe = Regex("^(please\\s+)?remember(\\s+that)?[:,]?\\s+(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun ask(query: String) { val q = query.trim(); if (q.isNotEmpty() && !busy.value) run(q, true) }
    fun retry() {
        if (busy.value) return
        val last = v2.chat.value.lastOrNull { it.role == "user" } ?: return
        v2.removeTrailingErrors(); run(last.text, false)
    }
    fun stop() { job?.cancel() }
    fun clear() { stop(); v2.clearChat(); pendingMemory.value = null }
    fun confirmMemory() { val t = pendingMemory.value ?: return; v2.addMemory(t); pendingMemory.value = null; v2.addChat("assistant", "Saved to your memory.") }
    fun declineMemory() { pendingMemory.value = null; v2.addChat("assistant", "Okay, I won't save that.") }

    private fun persona() = v2.str("assistant_name", "Aditya").ifBlank { "Aditya" }

    private fun run(q: String, addUser: Boolean) {
        busy.value = true
        job = scope.launch {
            try {
                if (addUser) v2.addChat("user", q)
                val rem = rememberRe.find(q)
                if (rem != null) { handleRemember(rem.groupValues[3].trim()); return@launch }
                base.value = OrbState.PROCESSING
                val data = buildContext(q)
                if (ai.apiKey.isBlank()) {
                    finish(LocalAnswers.answer(q, snapshot()) + "\n\n(AI is not configured, so this answer comes directly from your stored data. Add an OpenRouter key in Settings for full conversation.)")
                    return@launch
                }
                base.value = OrbState.THINKING
                partial.value = ""
                val sys = systemPrompt(data)
                val hist = v2.chat.value.filter { !it.error }.takeLast(12).map { it.role to it.text }
                var got = false
                var res = streamer.stream(ai, sys, hist) { d -> got = true; partial.value = (partial.value ?: "") + d }
                ensureActive()
                // Streaming failed before any text arrived (e.g. the model/provider does not stream): try a normal request once.
                if (res.isFailure && !got) { res = ai.chat(sys, hist); ensureActive() }
                res.fold({ finish(it) }, { e ->
                    v2.addChat("assistant", friendly(e), error = true)
                    base.value = OrbState.ERROR
                    scope.launch { delay(4000); if (base.value == OrbState.ERROR) base.value = OrbState.IDLE }
                })
            } catch (e: CancellationException) {
                partial.value?.takeIf { it.isNotBlank() }?.let { v2.addChat("assistant", it) }
                throw e
            } catch (e: Exception) {
                v2.addChat("assistant", friendly(e), error = true); base.value = OrbState.ERROR
                scope.launch { delay(4000); if (base.value == OrbState.ERROR) base.value = OrbState.IDLE }
            } finally {
                partial.value = null; busy.value = false
                if (base.value != OrbState.ERROR) base.value = OrbState.IDLE
            }
        }
    }

    private fun finish(text: String) {
        v2.addChat("assistant", text)
        if (v2.bool("tts_on", false)) voice.speak(text)
    }

    private fun handleRemember(text: String) {
        if (!v2.bool("mem_on", true)) { v2.addChat("assistant", "Memory is turned off in Settings, so I haven't saved anything."); return }
        if (text.isBlank()) { v2.addChat("assistant", "What would you like me to remember?"); return }
        pendingMemory.value = text
        v2.addChat("assistant", "I can save this to your memory: “$text”. Nothing is stored unless you tap Save.")
    }

    private fun friendly(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            e is java.net.UnknownHostException || e is java.net.SocketTimeoutException || e is java.net.ConnectException -> "I couldn't reach the AI service. Check your internet connection and retry."
            m.isBlank() -> "Something went wrong while contacting the AI service."
            else -> m
        }
    }

    private fun systemPrompt(data: String): String {
        val tone = when (v2.str("tone", "professional")) { "friendly" -> "warm and friendly"; "concise" -> "extremely concise"; else -> "professional, intelligent and helpful" }
        val lang = v2.str("lang", "").let { if (it.isBlank()) "the language the user writes in" else Locale.forLanguageTag(it).displayLanguage }
        return """You are ${persona()}, the user's personal intelligence assistant inside their Telegram client. Tone: $tone. Reply in $lang.
Answer ONLY from the <data> block below. If something is not in the data, say you don't have it. Never invent conversations, senders, meetings, dates or deadlines.
Clearly separate what a message explicitly says from your own interpretation, and mark uncertain items as uncertain.
You cannot send messages, create tasks or create reminders; never claim that you did. Keep answers short and scannable.
<data>
$data
</data>"""
    }

    // ---------------------------------------------------------------- context from real data

    private fun fmt(ms: Long) = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(ms))
    private fun insightLine(i: Insight): String = "- [${i.priority.label}] ${i.categories.joinToString("/") { it.label }} | ${i.sender} in ${i.chatTitle}, ${fmt(i.ts)}: \"${i.excerpt.take(160)}\"" +
        (if (i.dateText.isNotEmpty()) " | date in text: ${i.dateText}" else "") + (if (i.uncertain.isNotEmpty()) " (uncertain: ${i.uncertain})" else "")

    fun snapshot(): AssistantSnapshot {
        val chats = tg.chats.value.filter { !it.archived && !it.muted && it.unread > 0 && intel.isChatAllowed(it) }
        val a = if (intel.enabled.value) v2.insights.value.filter { !it.dismissed } else emptyList()
        val now = System.currentTimeMillis()
        return AssistantSnapshot(
            unreadTotal = tg.chats.value.filter { !it.archived && !it.muted }.sumOf { it.unread }, unreadChats = chats.map { it.title to it.unread },
            important = a.filter { Category.IMPORTANT in it.categories || Category.URGENT in it.categories }, replies = a.filter { Category.REPLY in it.categories },
            upcoming = a.filter { (Category.MEETING in it.categories || Category.EVENT in it.categories) && (it.dueMs == 0L || it.dueMs >= now) },
            tasks = v2.tasks.value.filter { it.status == TaskStatus.ACTIVE }, legacyTasks = legacyTasks.value.filter { !it.done }.map { it.text },
            intelEnabled = intel.enabled.value, notifications = legacyEvents.value.filter { it.type == "notify" }.map { it.text })
    }

    private suspend fun buildContext(q: String): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder(); val now = System.currentTimeMillis(); val s = snapshot()
        sb.append("Now: ${SimpleDateFormat("EEEE d MMM yyyy HH:mm", Locale.getDefault()).format(Date(now))}\n")
        sb.append("Unread messages (non-muted chats): ${s.unreadTotal}\n")
        s.unreadChats.take(8).forEach { sb.append("- ${it.first}: ${it.second} unread\n") }
        if (!s.intelEnabled) sb.append("Message intelligence is OFF: no analysis of message content is available.\n")
        else {
            sb.append("Important/urgent messages:\n"); s.important.take(8).forEach { sb.append(insightLine(it)).append('\n') }
            sb.append("Messages waiting for the user's reply:\n"); s.replies.take(8).forEach { sb.append(insightLine(it)).append('\n') }
            sb.append("Detected meetings/events:\n"); s.upcoming.take(6).forEach { sb.append(insightLine(it)).append('\n') }
        }
        sb.append("Active tasks:\n"); (s.tasks.map { it.title } + s.legacyTasks).take(10).forEach { sb.append("- $it\n") }
        if (s.notifications.isNotEmpty()) { sb.append("Recent notifications:\n"); s.notifications.take(6).forEach { sb.append("- $it\n") } }
        if (v2.bool("mem_on", true)) v2.memory.value.take(10).takeIf { it.isNotEmpty() }?.let { m -> sb.append("User-approved memory:\n"); m.forEach { sb.append("- ${it.text}\n") } }

        // Targeted retrieval: a chat the user names, or messages matching their keywords (only chats the user allows).
        val lower = q.lowercase(Locale.ROOT)
        val named = tg.chats.value.filter { c -> intel.isChatAllowed(c) && c.title.isNotBlank() && (lower.contains(c.title.lowercase(Locale.ROOT)) ||
            c.title.lowercase(Locale.ROOT).split(Regex("\\s+")).any { it.length >= 3 && it !in weakWords && Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(lower) }) }.take(2)
        for (c in named) {
            val h = tg.fetchHistory(c.id, 30).filter { it.kind != "other" && it.text.isNotBlank() }
            sb.append("Recent messages in \"${c.title}\":\n")
            h.takeLast(25).forEach { sb.append("- [${fmt(it.date * 1000L)}] ${it.sender.ifBlank { "?" }}: ${it.text.take(200)}\n") }
        }
        val stop = setOf("what", "which", "about", "find", "show", "message", "messages", "tomorrow", "today", "have", "from", "with", "that", "this", "when", "where", "summarize", "conversation", "pending", "important")
        val tokens = lower.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 && it !in stop }.distinct().take(3)
        if (tokens.isNotEmpty() && s.intelEnabled && named.isEmpty() && (lower.contains("find") || lower.contains("message about") || lower.contains("search") || lower.contains("about"))) {
            tg.warmCache()
            val allowed = tg.chats.value.filter { intel.isChatAllowed(it) }.map { it.id }.toSet()
            val hits = tokens.flatMap { tg.searchMessages(it) }.filter { it.chatId in allowed }.distinctBy { it.chatId to it.id }.sortedByDescending { it.date }.take(8)
            if (hits.isNotEmpty()) {
                sb.append("Messages matching the user's keywords:\n")
                hits.forEach { h -> sb.append("- [${fmt(h.date * 1000L)}] ${tg.chats.value.firstOrNull { it.id == h.chatId }?.title ?: "?"} / ${h.sender.ifBlank { "?" }}: ${h.text.take(200)}\n") }
            } else sb.append("A search for the user's keywords found no matching messages in recent history.\n")
        }
        sb.toString().take(7000)
    }
}
