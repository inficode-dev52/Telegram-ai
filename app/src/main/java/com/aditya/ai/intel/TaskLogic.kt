package com.aditya.ai.intel

import com.aditya.ai.data.*
import java.text.SimpleDateFormat
import java.util.Locale

/** State transitions for suggested tasks and the reminder options offered to the user. Pure, unit-tested. */
object TaskLogic {
    fun suggestionTitle(text: String): String {
        val one = text.trim().replace(Regex("\\s+"), " ")
        val first = one.split(Regex("(?<=[.!?])\\s")).firstOrNull()?.trim().orEmpty().ifBlank { one }
        return if (first.length <= 90) first else first.take(87).trimEnd() + "…"
    }
    fun approve(t: TaskV2) = if (t.status == TaskStatus.SUGGESTED) t.copy(status = TaskStatus.ACTIVE) else t
    fun dismiss(t: TaskV2) = if (t.status == TaskStatus.SUGGESTED) t.copy(status = TaskStatus.DISMISSED) else t
    fun setDone(t: TaskV2, done: Boolean) = when {
        t.status == TaskStatus.ACTIVE && done -> t.copy(status = TaskStatus.DONE)
        t.status == TaskStatus.DONE && !done -> t.copy(status = TaskStatus.ACTIVE)
        else -> t
    }

    const val MIN_LEAD_MS = 60_000L
    fun isValidReminderTime(whenMs: Long, now: Long) = whenMs >= now + MIN_LEAD_MS

    /** Candidate reminder times. Only future times are returned; nothing is scheduled until the user confirms one. */
    fun reminderOptions(now: Long, dueMs: Long): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>()
        if (dueMs > 0) {
            out.add("At the due time" to dueMs); out.add("1 hour before" to dueMs - 3_600_000L); out.add("1 day before" to dueMs - 86_400_000L)
        }
        out.add("In 1 hour" to now + 3_600_000L)
        out.add("In 3 hours" to now + 3 * 3_600_000L)
        return out.filter { isValidReminderTime(it.second, now) }.distinctBy { it.second }
    }

    /** Parses "yyyy-MM-dd" or "yyyy-MM-dd HH:mm" typed by the user; null when invalid. */
    fun parseUserDue(s: String): Long? {
        val t = s.trim(); if (t.isEmpty()) return null
        for (p in listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd")) {
            val f = SimpleDateFormat(p, Locale.US); f.isLenient = false
            val d = runCatching { f.parse(t) }.getOrNull()
            if (d != null && f.format(d) == t) return d.time
        }
        return null
    }
}
