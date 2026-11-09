package com.aditya.ai.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aditya.ai.td.Incoming
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Storage for everything added by the v2 upgrade. It lives in its OWN encrypted preferences file ("aditya_v2_secure"),
 * so the existing store ("aditya_secure"), the TDLib directories and every existing key stay untouched.
 */
class V2Store(ctx: Context) {
    val prefs = EncryptedSharedPreferences.create(ctx, "aditya_v2_secure",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    private val lock = Any()
    private var seq = System.currentTimeMillis() * 1000
    private fun nextId(): Long = synchronized(lock) { seq += 1; seq }

    private fun <T> load(k: String, f: (JSONObject) -> T): List<T> = runCatching {
        val a = JSONArray(prefs.getString(k, "[]")); (0 until a.length()).mapNotNull { runCatching { f(a.getJSONObject(it)) }.getOrNull() } }.getOrDefault(emptyList())
    private fun <T> save(k: String, l: List<T>, f: (T) -> JSONObject) {
        prefs.edit().putString(k, JSONArray().apply { l.forEach { put(f(it)) } }.toString()).apply() }

    // ------------------------------------------------------------------ settings (new keys only)
    fun str(k: String, d: String) = prefs.getString(k, d) ?: d
    fun setStr(k: String, v: String) = prefs.edit().putString(k, v).apply()
    fun bool(k: String, d: Boolean) = prefs.getBoolean(k, d)
    fun setBool(k: String, v: Boolean) = prefs.edit().putBoolean(k, v).apply()
    fun float(k: String, d: Float) = prefs.getFloat(k, d)
    fun setFloat(k: String, v: Float) = prefs.edit().putFloat(k, v).apply()
    fun long(k: String, d: Long) = prefs.getLong(k, d)
    fun setLong(k: String, v: Long) = prefs.edit().putLong(k, v).apply()

    // ------------------------------------------------------------------ insights
    val insights = MutableStateFlow(load("insights") { o ->
        Insight(o.getLong("id"), o.getLong("chat"), o.getLong("msg"), o.getString("title"), o.getString("sender"), o.getString("excerpt"), o.getLong("ts"),
            o.getString("cats").split(",").mapNotNull { n -> Category.values().firstOrNull { it.name == n } }.toSet(),
            Priority.values().firstOrNull { it.name == o.getString("pri") } ?: Priority.LOW, o.getString("reason"), o.getBoolean("ai"),
            o.optString("dateText"), o.optLong("due"), o.optString("unc"), o.optBoolean("dis"))
    })
    private fun saveInsights() = save("insights", insights.value) { i -> JSONObject().put("id", i.id).put("chat", i.chatId).put("msg", i.msgId).put("title", i.chatTitle)
        .put("sender", i.sender).put("excerpt", i.excerpt).put("ts", i.ts).put("cats", i.categories.joinToString(",") { it.name }).put("pri", i.priority.name)
        .put("reason", i.reason).put("ai", i.viaAi).put("dateText", i.dateText).put("due", i.dueMs).put("unc", i.uncertain).put("dis", i.dismissed) }

    fun newId() = nextId()

    /** Replaces insights of the scanned chats. Items the user dismissed stay dismissed and are never resurfaced. */
    fun replaceInsights(scanned: Set<Long>, fresh: List<Insight>) = synchronized(lock) {
        val old = insights.value; val oldByKey = old.associateBy { it.key }; val freshKeys = HashSet<String>()
        val merged = fresh.map { f -> freshKeys.add(f.key); val o = oldByKey[f.key]; f.copy(id = o?.id ?: f.id, dismissed = o?.dismissed ?: false) }
        val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
        val keep = old.filter { it.chatId !in scanned || (it.dismissed && it.key !in freshKeys && it.ts > weekAgo) }
        insights.value = (merged + keep).sortedByDescending { it.ts }.take(300); saveInsights()
    }
    fun upsertInsight(i: Insight) {
        synchronized(lock) {
            val o = insights.value.firstOrNull { it.key == i.key }
            if (o?.dismissed == true) return
            insights.value = (listOf(i.copy(id = o?.id ?: i.id)) + insights.value.filter { it.key != i.key }).sortedByDescending { it.ts }.take(300); saveInsights()
        }
    }
    /** A newer unanswered message in the same chat replaces the older "needs reply" flag. */
    fun supersedeReplies(chatId: Long, keepMsgId: Long) {
        synchronized(lock) {
            if (insights.value.none { it.chatId == chatId && it.msgId != keepMsgId && Category.REPLY in it.categories }) return
            insights.value = insights.value.mapNotNull { i ->
                if (i.chatId != chatId || i.msgId == keepMsgId || Category.REPLY !in i.categories) i else (i.categories - Category.REPLY).let { r -> if (r.isEmpty()) null else i.copy(categories = r) } }
            saveInsights()
        }
    }
    fun dismissInsight(id: Long) = synchronized(lock) { insights.value = insights.value.map { if (it.id == id) it.copy(dismissed = true) else it }; saveInsights() }
    /** Called when the user sends a message in a chat: that chat no longer has an unanswered incoming message. */
    fun clearReplyFlag(chatId: Long) {
        synchronized(lock) {
            if (insights.value.none { it.chatId == chatId && Category.REPLY in it.categories }) return
            insights.value = insights.value.mapNotNull { i ->
                if (i.chatId != chatId || Category.REPLY !in i.categories) i else (i.categories - Category.REPLY).let { r -> if (r.isEmpty()) null else i.copy(categories = r) } }
            saveInsights()
        }
    }
    fun clearInsights() = synchronized(lock) { insights.value = emptyList(); saveInsights() }

    // ------------------------------------------------------------------ tasks (suggested -> approved by the user)
    val tasks = MutableStateFlow(load("tasks") { o ->
        TaskV2(o.getLong("id"), o.getLong("chat"), o.getLong("msg"), o.getString("title"), o.getString("sender"), o.getString("task"), o.getString("orig"), o.getLong("ts"),
            o.optString("dueText"), o.optLong("due"), TaskStatus.values().firstOrNull { it.name == o.getString("st") } ?: TaskStatus.SUGGESTED)
    })
    private fun saveTasks() = save("tasks", tasks.value) { t -> JSONObject().put("id", t.id).put("chat", t.chatId).put("msg", t.msgId).put("title", t.chatTitle).put("sender", t.sender)
        .put("task", t.title).put("orig", t.original).put("ts", t.msgTs).put("dueText", t.dueText).put("due", t.dueMs).put("st", t.status.name) }
    fun suggestTask(t: TaskV2) {
        synchronized(lock) {
            if (t.msgId != 0L && tasks.value.any { it.chatId == t.chatId && it.msgId == t.msgId }) return
            tasks.value = (listOf(t) + tasks.value).take(300); saveTasks()
        }
    }
    fun addManualTask(title: String) = synchronized(lock) {
        tasks.value = (listOf(TaskV2(nextId(), 0L, 0L, "Me", "Me", title, title, System.currentTimeMillis(), "", 0L, TaskStatus.ACTIVE)) + tasks.value).take(300); saveTasks()
    }
    fun editTask(id: Long, f: (TaskV2) -> TaskV2) = synchronized(lock) { tasks.value = tasks.value.map { if (it.id == id) f(it) else it }; saveTasks() }
    fun deleteTask(id: Long) = synchronized(lock) { tasks.value = tasks.value.filter { it.id != id }; saveTasks() }

    // ------------------------------------------------------------------ assistant chat history
    val chat = MutableStateFlow(load("chat") { AssistantMsg(it.getLong("id"), it.getString("role"), it.getString("text"), it.getLong("ts"), it.optBoolean("err")) })
    private fun saveChat() = save("chat", chat.value) { JSONObject().put("id", it.id).put("role", it.role).put("text", it.text).put("ts", it.ts).put("err", it.error) }
    fun addChat(role: String, text: String, error: Boolean = false) = synchronized(lock) {
        chat.value = (chat.value + AssistantMsg(nextId(), role, text, System.currentTimeMillis(), error)).takeLast(200); saveChat() }
    fun removeTrailingErrors() = synchronized(lock) { chat.value = chat.value.dropLastWhile { it.error }; saveChat() }
    fun clearChat() = synchronized(lock) { chat.value = emptyList(); saveChat() }

    // ------------------------------------------------------------------ assistant memory (explicitly user-approved only)
    val memory = MutableStateFlow(load("memory") { MemoryItem(it.getLong("id"), it.getLong("ts"), it.getString("text")) })
    private fun saveMemory() = save("memory", memory.value) { JSONObject().put("id", it.id).put("ts", it.ts).put("text", it.text) }
    fun addMemory(text: String) = synchronized(lock) { memory.value = (listOf(MemoryItem(nextId(), System.currentTimeMillis(), text.trim())) + memory.value).take(200); saveMemory() }
    fun editMemory(id: Long, text: String) = synchronized(lock) { memory.value = memory.value.map { if (it.id == id) it.copy(text = text.trim()) else it }; saveMemory() }
    fun deleteMemory(id: Long) = synchronized(lock) { memory.value = memory.value.filter { it.id != id }; saveMemory() }
    fun clearMemory() = synchronized(lock) { memory.value = emptyList(); saveMemory() }

    // ------------------------------------------------------------------ reminders (created only after explicit confirmation)
    val reminders = MutableStateFlow(load("reminders") { Reminder(it.getLong("id"), it.getLong("task"), it.getString("title"), it.getLong("at")) })
    private fun saveReminders() = save("reminders", reminders.value) { JSONObject().put("id", it.id).put("task", it.taskId).put("title", it.title).put("at", it.whenMs) }
    fun addReminder(taskId: Long, title: String, whenMs: Long): Reminder = synchronized(lock) {
        val r = Reminder(nextId(), taskId, title, whenMs); reminders.value = reminders.value + r; saveReminders(); r }
    fun removeReminder(id: Long) = synchronized(lock) { reminders.value = reminders.value.filter { it.id != id }; saveReminders() }

    // ------------------------------------------------------------------ approval-queue drafts survive restarts
    val drafts: DraftPersistence = object : DraftPersistence {
        override fun load(): List<Pair<Incoming, String>> = this@V2Store.load("drafts") { o ->
            Incoming(o.getLong("c"), o.getLong("m"), o.getString("t"), o.getString("sn"), o.getString("ct"), o.getString("ck"), o.getBoolean("it"), o.getBoolean("mu")) to o.getString("r") }
        override fun save(list: List<Pair<Incoming, String>>) = synchronized(lock) {
            this@V2Store.save("drafts", list) { (m, r) -> JSONObject().put("c", m.chatId).put("m", m.msgId).put("t", m.text).put("sn", m.senderName).put("ct", m.chatTitle)
                .put("ck", m.chatKind).put("it", m.isText).put("mu", m.muted).put("r", r) } }
    }
}
