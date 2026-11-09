package com.aditya.ai.intel

import com.aditya.ai.data.Category
import com.aditya.ai.data.Priority
import org.json.JSONArray
import org.json.JSONObject

data class Candidate(val index: Int, val chat: String, val sender: String, val text: String)
data class AiItem(val index: Int, val categories: Set<Category>, val priority: Priority, val reason: String, val dateText: String, val uncertain: String)

/** Builds the classification request and strictly validates the model's JSON answer. Anything invalid is dropped, never guessed. */
object AiClassifier {
    private val allowed = listOf(Category.IMPORTANT, Category.URGENT, Category.REPLY, Category.MEETING, Category.DEADLINE, Category.EVENT, Category.REQUEST)

    val systemPrompt: String = """You classify chat messages for a personal assistant. Reply with ONE JSON object and nothing else (no markdown).
Schema: {"items":[{"i":<int>,"categories":[...],"priority":"HIGH|MEDIUM|LOW","reason":"<short factual reason>","date_text":"<copied verbatim from the message or empty>","uncertain":"<what is unclear or empty>"}]}
Allowed categories: ${allowed.joinToString(", ") { it.name }}.
Rules: Use an empty categories list when the message is not noteworthy. Never invent dates, people or meetings. date_text must be an exact substring of the message. HIGH only when the message is clearly time-sensitive. Keep reason under 160 characters and describe only what the message says."""

    fun buildPayload(items: List<Candidate>): String {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("i", it.index).put("chat", it.chat).put("sender", it.sender).put("text", it.text.take(400))) }
        return JSONObject().put("messages", arr).toString()
    }

    /** [sources] maps index -> original message text (used to verify date_text really occurs in the message). */
    fun parse(raw: String, sources: Map<Int, String>): Result<Map<Int, AiItem>> {
        val body = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = body.indexOf('{'); val end = body.lastIndexOf('}')
        if (start < 0 || end <= start) return Result.failure(IllegalArgumentException("AI returned no JSON object"))
        return try {
            val items = JSONObject(body.substring(start, end + 1)).optJSONArray("items")
                ?: return Result.failure(IllegalArgumentException("AI JSON is missing the items array"))
            val out = LinkedHashMap<Int, AiItem>()
            for (n in 0 until items.length()) {
                val o = items.optJSONObject(n) ?: continue
                val i = o.optInt("i", -1)
                val src = sources[i] ?: continue
                val cats = LinkedHashSet<Category>()
                val ca = o.optJSONArray("categories")
                if (ca != null) for (k in 0 until ca.length()) {
                    val name = ca.optString(k).trim().uppercase().replace(' ', '_').replace('-', '_')
                    allowed.firstOrNull { it.name == name }?.let { cats.add(it) }
                }
                if (cats.isEmpty()) continue
                val pri = Priority.values().firstOrNull { it.name == o.optString("priority").trim().uppercase() } ?: Priority.MEDIUM
                val reason = o.optString("reason").trim().take(200).ifBlank { "Classified by AI (no reason given)" }
                val rawDate = o.optString("date_text").trim()
                val date = if (rawDate.isNotEmpty() && src.contains(rawDate, ignoreCase = true)) rawDate else ""
                out[i] = AiItem(i, cats, pri, reason, date, o.optString("uncertain").trim().take(160))
            }
            Result.success(out)
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("AI returned invalid JSON"))
        }
    }
}
