package com.aditya.ai.data
import android.content.Context
import androidx.security.crypto.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.*

data class Evt(val ts: Long, val type: String, val text: String)
data class TaskItem(val id: Long, val chatId: Long, val title: String, val text: String, val done: Boolean)
data class MemNote(val id: Long, val ts: Long, val chatId: Long, val title: String, val kind: String, val text: String)
data class Attn(val id: Long, val ts: Long, val chatId: Long, val title: String, val text: String, val reason: String)

/** All app data lives in encrypted preferences. Lists are newest-first. */
class Store(ctx: Context) {
    val prefs = EncryptedSharedPreferences.create(ctx, "aditya_secure",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    private fun <T> load(k: String, f: (JSONObject) -> T): List<T> = runCatching {
        val a = JSONArray(prefs.getString(k, "[]")); (0 until a.length()).map { f(a.getJSONObject(it)) } }.getOrDefault(emptyList())
    private fun <T> save(k: String, l: List<T>, f: (T) -> JSONObject) {
        prefs.edit().putString(k, JSONArray().apply { l.forEach { put(f(it)) } }.toString()).apply() }
    private val lock = Any()
    private fun id() = System.nanoTime()

    val events = MutableStateFlow(load("events") { Evt(it.getLong("ts"), it.getString("type"), it.getString("text")) })
    val tasks = MutableStateFlow(load("tasks") { TaskItem(it.getLong("id"), it.getLong("chat"), it.getString("title"), it.getString("text"), it.getBoolean("done")) })
    val notes = MutableStateFlow(load("notes") { MemNote(it.getLong("id"), it.getLong("ts"), it.getLong("chat"), it.getString("title"), it.getString("kind"), it.getString("text")) })
    val flags = MutableStateFlow(load("flags") { Attn(it.getLong("id"), it.getLong("ts"), it.getLong("chat"), it.getString("title"), it.getString("text"), it.getString("reason")) })

    fun log(type: String, text: String) = synchronized(lock) {
        events.value = (listOf(Evt(System.currentTimeMillis(), type, text)) + events.value).take(300)
        save("events", events.value) { JSONObject().put("ts", it.ts).put("type", it.type).put("text", it.text) } }
    fun clearEvents() = synchronized(lock) { events.value = emptyList(); save("events", events.value) { JSONObject() } }

    private fun saveTasks() = save("tasks", tasks.value) { JSONObject().put("id", it.id).put("chat", it.chatId).put("title", it.title).put("text", it.text).put("done", it.done) }
    fun addTask(chatId: Long, title: String, text: String) = synchronized(lock) {
        if (tasks.value.any { it.chatId == chatId && it.text == text && !it.done }) return
        tasks.value = (listOf(TaskItem(id(), chatId, title, text, false)) + tasks.value).take(200); saveTasks(); log("task", "Task added: ${text.take(60)}") }
    fun toggleTask(id: Long) = synchronized(lock) { tasks.value = tasks.value.map { if (it.id == id) it.copy(done = !it.done) else it }; saveTasks() }
    fun deleteTask(id: Long) = synchronized(lock) { tasks.value = tasks.value.filter { it.id != id }; saveTasks() }

    private fun saveNotes() = save("notes", notes.value) { JSONObject().put("id", it.id).put("ts", it.ts).put("chat", it.chatId).put("title", it.title).put("kind", it.kind).put("text", it.text) }
    fun addNote(chatId: Long, title: String, kind: String, text: String) = synchronized(lock) {
        notes.value = (listOf(MemNote(id(), System.currentTimeMillis(), chatId, title, kind, text)) + notes.value).take(200); saveNotes() }
    fun deleteNote(id: Long) = synchronized(lock) { notes.value = notes.value.filter { it.id != id }; saveNotes() }
    fun clearMemory() = synchronized(lock) { notes.value = notes.value.filter { it.kind == "briefing" }; saveNotes() }

    private fun saveFlags() = save("flags", flags.value) { JSONObject().put("id", it.id).put("ts", it.ts).put("chat", it.chatId).put("title", it.title).put("text", it.text).put("reason", it.reason) }
    fun addFlag(chatId: Long, title: String, text: String, reason: String) = synchronized(lock) {
        if (flags.value.any { it.chatId == chatId && it.text == text }) return
        flags.value = (listOf(Attn(id(), System.currentTimeMillis(), chatId, title, text, reason)) + flags.value).take(100); saveFlags() }
    fun dismissFlag(id: Long) = synchronized(lock) { flags.value = flags.value.filter { it.id != id }; saveFlags() }
    fun resolveQuestions(chatId: Long) = synchronized(lock) {
        if (flags.value.any { it.chatId == chatId && it.reason == "Question" }) { flags.value = flags.value.filterNot { it.chatId == chatId && it.reason == "Question" }; saveFlags() } }

    fun bool(k: String, d: Boolean) = prefs.getBoolean(k, d)
    fun setBool(k: String, v: Boolean) = prefs.edit().putBoolean(k, v).apply()
    fun str(k: String, d: String) = prefs.getString(k, d) ?: d
    fun setStr(k: String, v: String) = prefs.edit().putString(k, v).apply()
}
