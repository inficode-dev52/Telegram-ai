package com.aditya.ai.ai

import org.json.JSONObject

sealed class SseEvent {
    data class Delta(val text: String) : SseEvent()
    object Done : SseEvent()
    data class Failure(val message: String) : SseEvent()
    object Ignore : SseEvent()
}

/** Parses one line of an OpenRouter/OpenAI-style server-sent-event stream. */
object Sse {
    fun parseLine(line: String): SseEvent {
        val l = line.trim()
        if (l.isEmpty() || l.startsWith(":") || !l.startsWith("data:")) return SseEvent.Ignore
        val p = l.removePrefix("data:").trim()
        if (p == "[DONE]") return SseEvent.Done
        return try {
            val o = JSONObject(p)
            o.optJSONObject("error")?.let { return SseEvent.Failure(it.optString("message", "Stream error")) }
            val first = o.optJSONArray("choices")?.optJSONObject(0) ?: return SseEvent.Ignore
            val delta = first.optJSONObject("delta") ?: return SseEvent.Ignore
            if (delta.isNull("content")) SseEvent.Ignore else delta.getString("content").let { if (it.isEmpty()) SseEvent.Ignore else SseEvent.Delta(it) }
        } catch (e: Exception) { SseEvent.Ignore }
    }
}
