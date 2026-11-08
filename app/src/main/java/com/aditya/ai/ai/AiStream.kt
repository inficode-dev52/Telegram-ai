package com.aditya.ai.ai

import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Streaming chat on top of the existing OpenRouter configuration (key + model come from [Ai], unchanged). */
class AiStream {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    suspend fun stream(ai: Ai, system: String, msgs: List<Pair<String, String>>, onDelta: (String) -> Unit): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (ai.apiKey.isBlank()) error("OpenRouter API key is not set (Settings)")
            val arr = JSONArray().put(JSONObject().put("role", "system").put("content", system))
            msgs.forEach { arr.put(JSONObject().put("role", it.first).put("content", it.second)) }
            val req = Request.Builder().url("https://openrouter.ai/api/v1/chat/completions").header("Authorization", "Bearer ${ai.apiKey}")
                .post(JSONObject().put("model", ai.model).put("messages", arr).put("stream", true).toString().toRequestBody("application/json".toMediaType())).build()
            val call = http.newCall(req)
            val handle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use { r ->
                    if (!r.isSuccessful) {
                        val s = r.body?.string().orEmpty()
                        error("OpenRouter ${r.code}: " + runCatching { JSONObject(s).getJSONObject("error").getString("message") }.getOrDefault(s.take(120)))
                    }
                    val src = r.body!!.source()
                    val sb = StringBuilder()
                    var finished = false
                    while (!finished) {
                        coroutineContext.ensureActive()
                        val line = src.readUtf8Line() ?: break
                        when (val e = Sse.parseLine(line)) {
                            is SseEvent.Delta -> { sb.append(e.text); onDelta(e.text) }
                            is SseEvent.Failure -> error(e.message)
                            SseEvent.Done -> finished = true
                            SseEvent.Ignore -> {}
                        }
                    }
                    if (sb.isBlank()) error("The model returned an empty response")
                    sb.toString().trim()
                }
            } finally { handle?.dispose() }
        }
    }
}
