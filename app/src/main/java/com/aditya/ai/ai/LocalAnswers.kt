package com.aditya.ai.ai

import com.aditya.ai.data.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AssistantSnapshot(
    val unreadTotal: Int, val unreadChats: List<Pair<String, Int>>, val important: List<Insight>, val replies: List<Insight>,
    val upcoming: List<Insight>, val tasks: List<TaskV2>, val legacyTasks: List<String>, val intelEnabled: Boolean, val notifications: List<String>
)

/** Rule-based answers from real stored data, used when no OpenRouter key is set or the AI is unreachable. */
object LocalAnswers {
    private fun line(i: Insight): String {
        val f = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
        return "• ${i.sender} in ${i.chatTitle} (${f.format(Date(i.ts))}): “${i.excerpt.take(120)}”" + if (i.dateText.isNotEmpty()) " — date mentioned: ${i.dateText}" + (if (i.uncertain.isNotEmpty()) " (uncertain)" else "") else ""
    }
    private const val OFF = "Message intelligence is turned off, so I haven't analysed your chats. You can enable it in Settings → Message intelligence."

    fun answer(q: String, s: AssistantSnapshot): String {
        val t = q.lowercase(Locale.ROOT)
        val parts = ArrayList<String>()
        if (listOf("important", "urgent", "priority").any { it in t }) parts.add(
            if (!s.intelEnabled) OFF else if (s.important.isEmpty()) "I found no important or urgent messages in the chats I'm allowed to analyse."
            else "Important messages:\n" + s.important.take(8).joinToString("\n") { line(it) })
        if (listOf("reply", "waiting", "respond").any { it in t }) parts.add(
            if (!s.intelEnabled) OFF else if (s.replies.isEmpty()) "Nobody appears to be waiting for a reply from you."
            else "Waiting for your reply:\n" + s.replies.take(8).joinToString("\n") { line(it) })
        if (listOf("meeting", "event", "tomorrow", "upcoming", "schedule").any { it in t }) parts.add(
            if (!s.intelEnabled) OFF else if (s.upcoming.isEmpty()) "I haven't detected any upcoming meetings or events with a date."
            else "Detected meetings and events (dates come from the message text):\n" + s.upcoming.take(8).joinToString("\n") { line(it) })
        if (listOf("task", "todo", "to-do", "pending").any { it in t }) {
            val all = s.tasks.map { "• ${it.title}" + if (it.dueText.isNotEmpty()) " (due: ${it.dueText})" else "" } + s.legacyTasks.map { "• $it" }
            parts.add(if (all.isEmpty()) "You have no pending tasks." else "Pending tasks:\n" + all.take(12).joinToString("\n"))
        }
        if ("unread" in t) parts.add(if (s.unreadTotal == 0) "You have no unread messages in non-muted chats." else "You have ${s.unreadTotal} unread messages:\n" + s.unreadChats.take(10).joinToString("\n") { "• ${it.first}: ${it.second}" })
        if ("notification" in t) parts.add(if (s.notifications.isEmpty()) "No recent notifications logged." else "Recent notifications:\n" + s.notifications.take(8).joinToString("\n") { "• $it" })
        if (parts.isEmpty()) return "I can answer from your Telegram data about important messages, replies you owe, tasks, unread messages, meetings and notifications. For free-form questions and summaries, add your OpenRouter API key in Settings."
        return parts.joinToString("\n\n")
    }
}
