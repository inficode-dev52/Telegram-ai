package com.aditya.ai.ai
import android.app.*
import android.content.*
import androidx.core.app.*
import com.aditya.ai.*
import com.aditya.ai.data.Store
import com.aditya.ai.td.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class Intelligence(private val app: AdityaApp, private val store: Store, private val tg: Telegram, private val ai: Ai) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val urgent = listOf("urgent", "asap", "important", "deadline", "immediately", "emergency", "tomorrow", "today", "tonight", "by friday", "eod")
    private val taskRe = Regex("remind me|don't forget|do not forget|please (send|share|review|check|call|confirm|reply|submit)|can you|could you|need you to|need to", RegexOption.IGNORE_CASE)
    init { app.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("messages", "Messages", NotificationManager.IMPORTANCE_DEFAULT)) }

    fun start() {
        scope.launch { tg.incoming.collect { analyze(it); notify(it) } }
        scope.launch { tg.sentByMe.collect { store.resolveQuestions(it) } }
        scope.launch { tg.auth.collect { if (it == Auth.READY) store.log("sync", "Telegram session ready") } }
        scope.launch { tg.connection.drop(1).distinctUntilChanged().collect { store.log("network", "Telegram: $it") } }
        scope.launch { tg.online.drop(1).distinctUntilChanged().collect { store.log("network", if (it) "Device back online" else "Device offline") } }
    }
    private fun analyze(m: Incoming) {
        if (!m.isText || m.muted || m.chatKind == "Channel" || m.chatKind == "Bot" || m.chatKind == "Saved") return
        val t = m.text.lowercase(); val personal = m.chatKind == "Private"
        val isQ = t.contains("?") && personal
        if (urgent.any { t.contains(it) } && (personal || t.length < 400)) store.addFlag(m.chatId, m.chatTitle, m.text.take(200), "Important")
        else if (isQ) store.addFlag(m.chatId, m.chatTitle, m.text.take(200), "Question")
        // Tasks are no longer created silently: suggestions are produced by IntelEngine and need the user's approval.
    }
    private fun notify(m: Incoming) {
        if (!store.bool("notif", true) || m.muted || app.visible) return
        val nm = NotificationManagerCompat.from(app); if (!nm.areNotificationsEnabled()) return
        val pi = PendingIntent.getActivity(app, m.chatId.hashCode(), Intent(app, MainActivity::class.java).putExtra("chat_id", m.chatId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(app, "messages").setSmallIcon(com.aditya.ai.R.drawable.ic_notification).setAutoCancel(true).setContentIntent(pi)
            .setContentTitle(if (store.bool("preview", true)) m.chatTitle else "ADITYA AI").setContentText(if (store.bool("preview", true)) m.text.take(120) else "New message").build()
        try { nm.notify(m.chatId.hashCode(), n); store.log("notify", "Notified: ${m.chatTitle}") } catch (_: SecurityException) {}
    }
    suspend fun summarize(chatId: Long, title: String): Result<String> {
        val hist = tg.fetchHistory(chatId, 60).filter { it.kind != "other" }
        if (hist.isEmpty()) return Result.failure(Exception("No messages to summarize"))
        val tr = hist.joinToString("\n") { it.sender.ifBlank { "?" } + ": " + it.text.ifBlank { "[${it.kind}]" } }
        return ai.chat("Summarize this Telegram conversation as short bullet points: key points, decisions, open questions, and tasks. Use the conversation's language.", listOf("user" to tr)).onSuccess {
            store.addNote(chatId, title, "summary", it); store.log("summary", "Summarized $title") }
    }
    suspend fun briefing(): Result<String> {
        val since = System.currentTimeMillis() / 1000 - 86400
        val sb = StringBuilder()
        for (c in tg.chats.value.filter { !it.archived && !it.muted && it.kind != "Channel" }.take(15)) {
            val inc = tg.fetchHistory(c.id, 30).filter { !it.out && it.date >= since && it.kind != "other" && it.text.isNotBlank() }
            if (inc.isNotEmpty()) sb.append("## ${c.title}\n").append(inc.joinToString("\n") { "- " + it.text.take(200) }).append("\n")
        }
        val open = store.tasks.value.filter { !it.done }.joinToString("\n") { "- ${it.title}: ${it.text}" }
        if (sb.isEmpty() && open.isEmpty()) return Result.failure(Exception("No messages in the last 24 hours and no open tasks"))
        return ai.chat("Write a concise daily briefing from the user's Telegram activity: what happened, what needs a reply or action, and priorities. Use short bullets.",
            listOf("user" to "Messages from the last 24h:\n$sb\nOpen tasks:\n$open")).onSuccess { store.addNote(0, "Daily briefing", "briefing", it); store.log("summary", "Daily briefing generated") }
    }
}
