package com.aditya.ai.data

/** Pure data models for the v2 intelligence layer. No Android dependencies so they can be unit-tested on the JVM. */

enum class Category(val label: String) {
    IMPORTANT("Important"), URGENT("Urgent"), REPLY("Needs reply"), MEETING("Meeting"), DEADLINE("Deadline"),
    EVENT("Event"), REQUEST("Request"), DOCUMENT("Document"), FOLLOW_UP("Follow-up")
}

enum class Priority(val label: String) { HIGH("High"), MEDIUM("Medium"), LOW("Low") }

enum class TaskStatus { SUGGESTED, ACTIVE, DONE, DISMISSED }

/**
 * One detected item. Provenance rules (shown separately in the UI):
 *  - CONFIRMED: chatTitle, sender, excerpt, ts, dateText (all copied verbatim from Telegram data)
 *  - INTERPRETATION: categories, priority, reason (rule-based, or AI when [viaAi])
 *  - UNCERTAIN: [uncertain] (e.g. a relative date that was resolved from the message time)
 * [dueMs] is 0 unless a date was explicitly present in the message text.
 */
data class Insight(
    val id: Long, val chatId: Long, val msgId: Long, val chatTitle: String, val sender: String, val excerpt: String, val ts: Long,
    val categories: Set<Category>, val priority: Priority, val reason: String, val viaAi: Boolean,
    val dateText: String = "", val dueMs: Long = 0L, val uncertain: String = "", val dismissed: Boolean = false
) { val key: String get() = "$chatId:$msgId" }

data class TaskV2(
    val id: Long, val chatId: Long, val msgId: Long, val chatTitle: String, val sender: String, val title: String,
    val original: String, val msgTs: Long, val dueText: String, val dueMs: Long, val status: TaskStatus
)

data class AssistantMsg(val id: Long, val role: String, val text: String, val ts: Long, val error: Boolean = false)

data class MemoryItem(val id: Long, val ts: Long, val text: String)

data class Reminder(val id: Long, val taskId: Long, val title: String, val whenMs: Long)

/** Persistence hook so the in-memory approval queue can survive restarts without changing its semantics. */
interface DraftPersistence {
    fun load(): List<Pair<com.aditya.ai.td.Incoming, String>>
    fun save(list: List<Pair<com.aditya.ai.td.Incoming, String>>)
}
