package com.aditya.ai.intel

import com.aditya.ai.data.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UnreadChat(val id: Long, val title: String, val unread: Int, val lastText: String, val lastDateSec: Int)
data class BriefingItem(val chatId: Long, val title: String, val detail: String, val ts: Long)
data class BriefingSection(val title: String, val items: List<BriefingItem>)
data class BriefingData(val generatedAt: Long, val sections: List<BriefingSection>) {
    val isEmpty get() = sections.all { it.items.isEmpty() }
    /** Plain text used as input for the optional AI narrative; contains only real data. */
    fun toPlainText(): String {
        val f = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
        return sections.filter { it.items.isNotEmpty() }.joinToString("\n\n") { s ->
            "## ${s.title}\n" + s.items.joinToString("\n") { "- ${it.title}: ${it.detail}" + if (it.ts > 0) " (${f.format(Date(it.ts))})" else "" }
        }
    }
}

data class Overview(val important: Int, val unread: Int, val pendingReplies: Int, val actionRequired: Int, val upcoming: Int, val pendingTasks: Int)

object BriefingBuilder {
    private const val DAY = 86_400_000L

    fun active(insights: List<Insight>) = insights.filter { !it.dismissed }

    /** Every number is a count over real stored data; nothing is estimated. */
    fun overview(insights: List<Insight>, tasks: List<TaskV2>, legacyOpenTasks: Int, unread: Int, now: Long): Overview {
        val a = active(insights)
        return Overview(
            important = a.count { Category.IMPORTANT in it.categories || Category.URGENT in it.categories },
            unread = unread,
            pendingReplies = a.count { Category.REPLY in it.categories },
            actionRequired = a.count { it.priority == Priority.HIGH || Category.REQUEST in it.categories || Category.DEADLINE in it.categories },
            upcoming = a.count { (Category.MEETING in it.categories || Category.EVENT in it.categories) && it.dueMs >= now },
            pendingTasks = tasks.count { it.status == TaskStatus.ACTIVE } + legacyOpenTasks
        )
    }

    fun build(now: Long, insights: List<Insight>, tasks: List<TaskV2>, unread: List<UnreadChat>): BriefingData {
        val a = active(insights)
        fun item(i: Insight, extra: String = "") = BriefingItem(i.chatId, i.chatTitle, "${i.sender}: ${i.excerpt.take(140)}$extra", i.ts)
        val important = a.filter { (Category.IMPORTANT in it.categories || Category.URGENT in it.categories) && it.ts >= now - DAY }.sortedByDescending { it.ts }.map { item(it) }
        val unreadItems = unread.filter { it.unread > 0 }.sortedByDescending { it.unread }.take(8)
            .map { BriefingItem(it.id, it.title, "${it.unread} unread · ${it.lastText.take(100)}", it.lastDateSec * 1000L) }
        val replies = a.filter { Category.REPLY in it.categories }.sortedByDescending { it.ts }.map { item(it) }
        val dates = a.filter { (it.dueMs > 0 || it.dateText.isNotEmpty()) && Category.MEETING !in it.categories }.sortedBy { if (it.dueMs > 0) it.dueMs else Long.MAX_VALUE }
            .map { item(it, " [${it.dateText.ifEmpty { "date" }}${if (it.uncertain.isNotEmpty()) ", uncertain" else ""}]") }
        val meetings = a.filter { Category.MEETING in it.categories }.sortedBy { if (it.dueMs > 0) it.dueMs else Long.MAX_VALUE }
            .map { item(it, if (it.dateText.isNotEmpty()) " [${it.dateText}${if (it.uncertain.isNotEmpty()) ", uncertain" else ""}]" else " [no date stated]") }
        val taskItems = tasks.filter { it.status == TaskStatus.ACTIVE }.map { BriefingItem(it.chatId, it.title, it.original.take(120) + if (it.dueText.isNotEmpty()) " [due: ${it.dueText}]" else "", it.msgTs) }
        val docs = a.filter { Category.DOCUMENT in it.categories && it.ts >= now - 2 * DAY }.sortedByDescending { it.ts }.map { BriefingItem(it.chatId, it.chatTitle, "${it.sender} sent ${it.excerpt}", it.ts) }
        return BriefingData(now, listOf(
            BriefingSection("Important conversations", important), BriefingSection("Unread messages", unreadItems),
            BriefingSection("Pending replies", replies), BriefingSection("Dates and deadlines", dates),
            BriefingSection("Meetings", meetings), BriefingSection("Outstanding tasks", taskItems), BriefingSection("Recent documents", docs)))
    }
}
