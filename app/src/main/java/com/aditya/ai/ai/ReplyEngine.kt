package com.aditya.ai.ai
import com.aditya.ai.data.Store
import com.aditya.ai.td.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class Mode { OFF, AUTO, APPROVAL }
const val SIGNATURE = "\n\n— Aditya"

class ReplyEngine(private val store: Store, private val tg: Telegram, private val ai: Ai) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seen = HashSet<Long>()
    val pending = MutableStateFlow<List<Pair<Incoming, String>>>(emptyList())
    val humanMode = MutableStateFlow(store.bool("human", false))

    /** Only ever changed by the explicit toggle in Settings. Manual sending never touches it. */
    fun setHuman(b: Boolean) { store.setBool("human", b); humanMode.value = b }
    fun chatMode(id: Long) = runCatching { Mode.valueOf(store.str("mode_$id", "OFF")) }.getOrDefault(Mode.OFF)
    fun setChatMode(id: Long, m: Mode, title: String = "") { store.setStr("mode_$id", m.name); store.log("mode", "AI mode for ${title.ifBlank { "chat" }}: ${m.name}") }
    fun start() { scope.launch { tg.incoming.collect { handle(it) } } }

    suspend fun generate(chatId: Long, title: String): Result<String> {
        val hist = tg.fetchHistory(chatId, 14).filter { it.kind != "other" }
        if (hist.isEmpty()) return Result.failure(Exception("No messages to reply to"))
        val mem = if (store.bool("memory", true)) store.notes.value.filter { (it.chatId == chatId || it.chatId == 0L) && it.kind != "briefing" }.take(6).joinToString("\n") { "- " + it.text.take(400) } else ""
        val sys = "You write replies on behalf of the account owner in a Telegram chat with \"$title\". Match the language, tone and length of the conversation; be natural and concise. Never mention being an AI and never add a signature. Reply with the message text only." +
            if (mem.isNotEmpty()) "\nKnown context and user-provided facts:\n$mem" else ""
        val tr = hist.joinToString("\n") { (if (it.out) "Me" else it.sender.ifBlank { "Them" }) + ": " + it.text.replace("— Aditya", "").trim().ifBlank { "[${it.kind}]" } }
        return ai.chat(sys, listOf("user" to "Conversation:\n$tr\n\nWrite my next reply."))
    }
    private suspend fun handle(m: Incoming) {
        if (seen.size > 5000) seen.clear()
        if (!seen.add(m.msgId) || humanMode.value || !m.isText || m.chatKind == "Channel") return
        val mode = chatMode(m.chatId); if (mode == Mode.OFF) return
        generate(m.chatId, m.chatTitle).fold({ reply ->
            if (mode == Mode.AUTO) { tg.sendText(m.chatId, reply + SIGNATURE); store.log("ai_reply", "Auto-replied in ${m.chatTitle}") }
            else { pending.value = pending.value + (m to reply); store.log("draft", "Draft awaiting approval in ${m.chatTitle}") }
        }, { store.log("error", "AI reply failed: ${it.message}") })
    }
    fun approve(i: Int, edited: String? = null) {
        val (m, r) = pending.value.getOrNull(i) ?: return
        tg.sendText(m.chatId, (edited?.ifBlank { null } ?: r) + SIGNATURE); store.log("ai_reply", "Approved reply sent in ${m.chatTitle}")
        pending.value = pending.value.filterIndexed { x, _ -> x != i }
    }
    fun reject(i: Int) { pending.value = pending.value.filterIndexed { x, _ -> x != i } }
}
