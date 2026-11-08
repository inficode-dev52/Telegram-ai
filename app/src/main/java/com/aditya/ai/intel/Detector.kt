package com.aditya.ai.intel

import com.aditya.ai.data.*
import java.util.Calendar
import java.util.Locale

data class Detection(val categories: Set<Category>, val priority: Priority, val reason: String)

/** [text] is copied verbatim from the message; [dueMs] is 0 when no usable date could be derived. */
data class ParsedDate(val text: String, val dueMs: Long, val uncertain: String)

/**
 * Deterministic keyword/rule classifier and explicit-date extractor. Used on its own when AI is off or unavailable,
 * and as the fallback when AI output is invalid. Nothing here invents data: reasons quote the words that matched,
 * and dates are only resolved from text that is actually present in the message.
 */
object Detector {
    private val ic = RegexOption.IGNORE_CASE
    private val urgentRe = Regex("""\b(urgent|urgently|asap|immediately|emergency|right now|as soon as possible|eod|high priority)\b""", ic)
    private val importantRe = Regex("""\b(important|priority|critical|mandatory|must|don't forget|do not forget)\b""", ic)
    private val meetingRe = Regex("""\b(meeting|zoom|google meet|meet at|stand-?up|appointment|interview|conference call|let's meet|call at|sync up)\b""", ic)
    private val deadlineRe = Regex("""\b(deadline|due (on|by|date|today|tomorrow|next|this)|submit by|last date|no later than|by eod)\b""", ic)
    private val eventRe = Regex("""\b(invitation|you're invited|you are invited|webinar|ceremony|save the date|celebration|seminar|workshop)\b""", ic)
    private val requestRe = Regex("""\b(please|kindly|can you|could you|would you|need you to|i need|remind me|send me|let me know)\b""", ic)

    fun classify(text: String, isPrivate: Boolean): Detection? {
        val t = text.trim()
        if (t.length < 3) return null
        val cats = LinkedHashSet<Category>()
        val why = ArrayList<String>()
        urgentRe.find(t)?.let { cats.add(Category.URGENT); why.add("contains \"${it.value}\"") }
        importantRe.find(t)?.let { cats.add(Category.IMPORTANT); why.add("contains \"${it.value}\"") }
        meetingRe.find(t)?.let { cats.add(Category.MEETING); why.add("mentions a meeting (\"${it.value}\")") }
        deadlineRe.find(t)?.let { cats.add(Category.DEADLINE); why.add("mentions a deadline (\"${it.value}\")") }
        eventRe.find(t)?.let { cats.add(Category.EVENT); why.add("looks like an event announcement (\"${it.value}\")") }
        if (isPrivate || t.length < 300) requestRe.find(t)?.let { cats.add(Category.REQUEST); why.add("contains a request (\"${it.value}\")") }
        if (isPrivate && t.contains('?')) { cats.add(Category.REPLY); why.add("question in a private chat") }
        if (isPrivate && Category.REQUEST in cats) cats.add(Category.REPLY)
        if (cats.isEmpty()) return null
        val reason = "Rule-based: " + why.distinct().joinToString("; ").ifBlank { "keyword match" }
        return Detection(cats, priorityFor(cats, 0L, 0L), reason.take(220))
    }

    /** True if my own outgoing message asks something, i.e. a missing answer is worth a follow-up. */
    fun looksLikeAsk(text: String) = text.contains('?') || requestRe.containsMatchIn(text)

    fun priorityFor(cats: Set<Category>, dueMs: Long, nowMs: Long): Priority {
        if (Category.URGENT in cats) return Priority.HIGH
        val soon = dueMs > 0 && nowMs > 0 && dueMs >= nowMs - 3_600_000L && dueMs <= nowMs + 48 * 3_600_000L
        if (soon && (Category.DEADLINE in cats || Category.MEETING in cats)) return Priority.HIGH
        if (cats.any { it == Category.IMPORTANT || it == Category.DEADLINE || it == Category.MEETING || it == Category.REQUEST || it == Category.REPLY }) return Priority.MEDIUM
        return Priority.LOW
    }

    /**
     * Keeps REPLY only on the latest incoming message that has not been answered (no later outgoing message in that chat).
     * Insights left with no categories are dropped.
     */
    fun pruneReplies(list: List<Insight>, lastOutByChat: Map<Long, Long>): List<Insight> {
        val latestReply = HashMap<Long, Long>()
        list.filter { Category.REPLY in it.categories }.forEach { latestReply[it.chatId] = maxOf(latestReply[it.chatId] ?: 0L, it.msgId) }
        return list.mapNotNull { i ->
            if (Category.REPLY !in i.categories) return@mapNotNull i
            val answered = i.msgId < (lastOutByChat[i.chatId] ?: 0L)
            val notLatest = i.msgId != latestReply[i.chatId]
            if (!answered && !notLatest) i else {
                val rest = i.categories - Category.REPLY
                if (rest.isEmpty()) null else i.copy(categories = rest)
            }
        }
    }

    // ---------------------------------------------------------------- explicit date extraction

    private val monthPat = "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"
    private val isoRe = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")
    private val dmyNameRe = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?$monthPat\.?(?:,?\s+(\d{4}))?\b""", ic)
    private val mdyNameRe = Regex("""\b$monthPat\.?\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?\b""", ic)
    private val numericRe = Regex("""\b(\d{1,2})[/.-](\d{1,2})[/.-](\d{2,4})\b""")
    private val relRe = Regex("""\b(day after tomorrow|today|tonight|tomorrow)\b""", ic)
    private val weekdayRe = Regex("""\b(?:(next|this|on|by|before)\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""", ic)
    private val timeRe = Regex("""\b(\d{1,2})(?::(\d{2}))?\s?(am|pm)\b|\b([01]?\d|2[0-3]):([0-5]\d)\b""", ic)
    private val monthIdx = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val dayNames = listOf("sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday")

    private fun startOfDay(ms: Long): Long {
        val c = Calendar.getInstance(); c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
    private fun ymd(y: Int, m: Int, d: Int): Long? = runCatching {
        val c = Calendar.getInstance(); c.isLenient = false; c.clear(); c.set(y, m, d, 0, 0, 0); c.timeInMillis
    }.getOrNull()
    private fun addDays(dayMs: Long, n: Int): Long { val c = Calendar.getInstance(); c.timeInMillis = dayMs; c.add(Calendar.DAY_OF_MONTH, n); return c.timeInMillis }
    private fun yearOf(ms: Long): Int { val c = Calendar.getInstance(); c.timeInMillis = ms; return c.get(Calendar.YEAR) }
    private fun dowOf(ms: Long): Int { val c = Calendar.getInstance(); c.timeInMillis = ms; return c.get(Calendar.DAY_OF_WEEK) - 1 }
    private fun monthOf(name: String) = monthIdx.indexOf(name.lowercase(Locale.ROOT).take(3))

    private fun timeOfDay(text: String): Int? {
        val m = timeRe.find(text) ?: return null
        val g = m.groupValues
        return if (g[3].isNotEmpty()) {
            val h = g[1].toInt(); val min = g[2].ifEmpty { "0" }.toInt()
            if (h !in 1..12 || min > 59) return null
            val h24 = (h % 12) + if (g[3].lowercase(Locale.ROOT) == "pm") 12 else 0
            h24 * 60 + min
        } else g[4].toInt() * 60 + g[5].toInt()
    }

    /** Resolves only dates literally present in [text]. Relative or year-less dates are resolved but flagged uncertain. */
    fun parseDate(text: String, msgTsMs: Long): ParsedDate? {
        val msgDay = startOfDay(msgTsMs)
        var shown: String; var day: Long; var note = ""
        val iso = isoRe.find(text)
        val dmy = if (iso == null) dmyNameRe.find(text) else null
        val mdy = if (iso == null && dmy == null) mdyNameRe.find(text) else null
        val num = if (iso == null && dmy == null && mdy == null) numericRe.find(text) else null
        when {
            iso != null -> {
                shown = iso.value
                day = ymd(iso.groupValues[1].toInt(), iso.groupValues[2].toInt() - 1, iso.groupValues[3].toInt()) ?: return null
            }
            dmy != null || mdy != null -> {
                val m = (dmy ?: mdy)!!
                val d = (if (dmy != null) m.groupValues[1] else m.groupValues[2]).toInt()
                val mon = monthOf(if (dmy != null) m.groupValues[2] else m.groupValues[1])
                val yTxt = m.groupValues[3]
                shown = m.value
                if (yTxt.isNotEmpty()) day = ymd(yTxt.toInt(), mon, d) ?: return null
                else {
                    val y = yearOf(msgDay)
                    val cand = ymd(y, mon, d) ?: return null
                    day = if (cand >= msgDay) cand else (ymd(y + 1, mon, d) ?: return null)
                    note = "Year not stated; assumed ${yearOf(day)}"
                }
            }
            num != null -> {
                shown = num.value
                val a = num.groupValues[1].toInt(); val b = num.groupValues[2].toInt(); var y = num.groupValues[3].toInt()
                if (y < 100) y += 2000
                return when {
                    a > 12 && b <= 12 -> ymd(y, b - 1, a)?.let { finish(shown, it, text, note) }
                    b > 12 && a <= 12 -> ymd(y, a - 1, b)?.let { finish(shown, it, text, note) }
                    a <= 12 && b <= 12 && a == b -> ymd(y, a - 1, b)?.let { finish(shown, it, text, note) }
                    else -> ParsedDate(shown, 0L, "Ambiguous day/month order; no date assumed")
                }
            }
            else -> {
                val rel = relRe.find(text)
                if (rel != null) {
                    shown = rel.value
                    val w = rel.value.lowercase(Locale.ROOT)
                    day = when (w) { "tomorrow" -> addDays(msgDay, 1); "day after tomorrow" -> addDays(msgDay, 2); else -> msgDay }
                    note = "Relative date \"$w\" resolved from the message time"
                } else {
                    val wd = weekdayRe.find(text) ?: return null
                    shown = wd.value
                    val target = dayNames.indexOf(wd.groupValues[2].lowercase(Locale.ROOT))
                    var delta = (target - dowOf(msgDay) + 7) % 7
                    if (delta == 0) delta = 7
                    day = addDays(msgDay, delta)
                    note = "Relative date \"${wd.value.trim()}\" resolved from the message time to the next ${dayNames[target]}"
                }
            }
        }
        return finish(shown, day, text, note)
    }

    private fun finish(shown: String, day: Long, text: String, note: String): ParsedDate {
        val minutes = timeOfDay(text)
        val due = if (minutes != null) day + minutes * 60_000L else day
        return ParsedDate(shown, due, note)
    }
}
