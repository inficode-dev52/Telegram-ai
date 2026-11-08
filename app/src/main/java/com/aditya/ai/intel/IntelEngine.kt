package com.aditya.ai.intel

import com.aditya.ai.ai.Ai
import com.aditya.ai.data.*
import com.aditya.ai.td.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Message intelligence over real Telegram data. Everything is opt-in and limited to chats the user allows.
 * Reads only through the existing [Telegram] wrapper (cached history); it never touches authentication or TDLib setup.
 */
class IntelEngine(private val v2: V2Store, private val tg: Telegram, private val ai: Ai) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val scanning = MutableStateFlow(false)
    val scanError = MutableStateFlow<String?>(null)
    val lastScan = MutableStateFlow(v2.long("last_scan", 0L))
    val enabled = MutableStateFlow(v2.bool("intel_on", false))
    val aiEnabled = MutableStateFlow(v2.bool("intel_ai", false))
    private val day = 86_400_000L

    fun setEnabled(b: Boolean) { v2.setBool("intel_on", b); enabled.value = b }
    fun setAi(b: Boolean) { v2.setBool("intel_ai", b); aiEnabled.value = b }

    /** Default: private chats and non-muted groups. Channels, bots and Saved Messages are off. The user can override per chat. */
    fun isChatAllowed(c: ChatItem): Boolean = when (v2.str("chat_${c.id}", "")) {
        "on" -> true
        "off" -> false
        else -> (c.kind == "Private" || c.kind == "Group") && !c.muted
    }
    fun setChatAllowed(id: Long, on: Boolean) = v2.setStr("chat_$id", if (on) "on" else "off")

    fun start() {
        scope.launch { tg.incoming.collect { runCatching { live(it) } } }
        scope.launch { tg.sentByMe.collect { v2.clearReplyFlag(it) } }
    }

    private fun live(m: Incoming) {
        if (!enabled.value || !m.isText || m.text.isBlank()) return
        val chat = tg.chats.value.firstOrNull { it.id == m.chatId }
        if (chat != null) { if (!isChatAllowed(chat)) return }
        else if (m.muted || m.chatKind == "Channel" || m.chatKind == "Bot" || m.chatKind == "Saved") return
        val isPrivate = m.chatKind == "Private"
        val ins = build(m.chatId, m.msgId, m.chatTitle, m.senderName.ifBlank { m.chatTitle }, m.text, System.currentTimeMillis(), isPrivate) ?: return
        v2.upsertInsight(ins)
        if (Category.REPLY in ins.categories) v2.supersedeReplies(m.chatId, m.msgId)
        suggestTask(ins, isPrivate)
    }

    private fun build(chatId: Long, msgId: Long, chatTitle: String, sender: String, text: String, tsMs: Long, isPrivate: Boolean): Insight? {
        val d = Detector.classify(text, isPrivate) ?: return null
        val date = Detector.parseDate(text, tsMs)
        val pri = Detector.priorityFor(d.categories, date?.dueMs ?: 0L, System.currentTimeMillis())
        return Insight(v2.newId(), chatId, msgId, chatTitle, sender, text.trim().take(300), tsMs, d.categories, pri, d.reason, false,
            date?.text ?: "", date?.dueMs ?: 0L, date?.uncertain ?: "")
    }

    /** A task is only ever SUGGESTED here; it becomes active when the user approves it. */
    private fun suggestTask(i: Insight, isPrivate: Boolean) {
        if (i.sender == "You") return
        val wants = Category.REQUEST in i.categories || Category.DEADLINE in i.categories
        if (!wants || !(isPrivate || Category.DEADLINE in i.categories)) return
        v2.suggestTask(TaskV2(v2.newId(), i.chatId, i.msgId, i.chatTitle, i.sender, TaskLogic.suggestionTitle(i.excerpt), i.excerpt, i.ts, i.dateText, i.dueMs, TaskStatus.SUGGESTED))
    }

    suspend fun scan(): Result<Int> {
        if (!enabled.value) return Result.failure(IllegalStateException("Message intelligence is off"))
        if (!scanning.compareAndSet(false, true)) return Result.failure(IllegalStateException("A scan is already running"))
        scanError.value = null
        return try {
            withTimeout(120_000) { withContext(Dispatchers.IO) { doScan() } }
        } catch (e: TimeoutCancellationException) {
            scanError.value = "The scan timed out. Check your connection and try again."; Result.failure(e)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            scanError.value = e.message ?: "Scan failed"; Result.failure(e)
        } finally { scanning.value = false }
    }

    private suspend fun doScan(): Result<Int> {
        val now = System.currentTimeMillis(); val windowStart = now - 3 * day
        val chats = tg.chats.value.filter { !it.archived && isChatAllowed(it) }.sortedByDescending { it.lastDate }.take(25)
        val cands = ArrayList<Insight>(); val scanned = HashSet<Long>(); val lastOut = HashMap<Long, Long>(); val privateChats = HashSet<Long>()
        for (c in chats) {
            val hist = tg.fetchHistory(c.id, 40)
            scanned.add(c.id)
            val isPrivate = c.kind == "Private"
            if (isPrivate) privateChats.add(c.id)
            lastOut[c.id] = hist.lastOrNull { it.out }?.id ?: 0L
            var latestUndetected: Insight? = null
            for (m in hist) {
                if (m.out || m.kind == "other") continue
                val tsMs = m.date * 1000L
                if (tsMs < windowStart) continue
                if (m.kind == "doc") {
                    val name = m.text.lines().firstOrNull().orEmpty().ifBlank { "a document" }
                    cands.add(Insight(v2.newId(), c.id, m.id, c.title, m.sender.ifBlank { c.title }, name, tsMs, setOf(Category.DOCUMENT), Priority.LOW, "Document received in this chat", false))
                    continue
                }
                if (m.text.isBlank()) continue
                val ins = build(c.id, m.id, c.title, m.sender.ifBlank { c.title }, m.text, tsMs, isPrivate)
                if (ins != null) cands.add(ins)
                else if (m.kind == "text") latestUndetected = Insight(v2.newId(), c.id, m.id, c.title, m.sender.ifBlank { c.title }, m.text.trim().take(300), tsMs, emptySet(), Priority.LOW, "", false)
            }
            // An unread message the rules did not flag may still be worth an AI look (only used when AI classification is on).
            if (c.unread > 0) latestUndetected?.let { cands.add(it) }
            // Follow-up opportunity: my own question/request in a private chat that has had no answer for a day.
            val last = hist.lastOrNull()
            if (isPrivate && last != null && last.out && last.kind == "text" && Detector.looksLikeAsk(last.text)) {
                val ts = last.date * 1000L; val hours = (now - ts) / 3_600_000L
                if (hours >= 24 && ts > now - 7 * day) cands.add(Insight(v2.newId(), c.id, last.id, c.title, "You", last.text.trim().take(300), ts,
                    setOf(Category.FOLLOW_UP), Priority.LOW, "No reply received $hours hours after your message", false))
            }
        }

        var list: List<Insight> = cands
        if (aiEnabled.value && ai.apiKey.isNotBlank()) {
            val targets = list.filter { Category.DOCUMENT !in it.categories && Category.FOLLOW_UP !in it.categories }.sortedByDescending { it.ts }.take(25)
            if (targets.isNotEmpty()) {
                val byKey = targets.withIndex().associate { (n, i) -> i.key to n }
                val sources = targets.withIndex().associate { (n, i) -> n to i.excerpt }
                val payload = AiClassifier.buildPayload(targets.mapIndexed { n, i -> Candidate(n, i.chatTitle, i.sender, i.excerpt) })
                val res = ai.chat(AiClassifier.systemPrompt, listOf("user" to payload)).mapCatching { raw -> AiClassifier.parse(raw, sources).getOrThrow() }
                res.fold({ aiMap ->
                    list = list.map { i ->
                        val r = byKey[i.key]?.let { aiMap[it] } ?: return@map i
                        val rank = if (i.priority.ordinal <= r.priority.ordinal) i.priority else r.priority
                        i.copy(categories = r.categories, priority = rank, reason = r.reason, viaAi = true,
                            dateText = i.dateText.ifEmpty { r.dateText }, uncertain = i.uncertain.ifEmpty { r.uncertain })
                    }
                }, { e -> scanError.value = "AI classification unavailable (${e.message}); used rule-based detection instead." })
            }
        }
        list = list.filter { it.categories.isNotEmpty() }
        list = Detector.pruneReplies(list, lastOut)
        v2.replaceInsights(scanned, list)
        list.forEach { suggestTask(it, it.chatId in privateChats) }
        v2.setLong("last_scan", now); lastScan.value = now
        return Result.success(list.size)
    }
}
