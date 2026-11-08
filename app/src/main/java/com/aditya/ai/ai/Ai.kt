package com.aditya.ai.ai
import com.aditya.ai.data.Store
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.*
import java.util.concurrent.TimeUnit

class Ai(private val store: Store) {
    private val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()
    var apiKey: String get() = store.str("or_key", ""); set(v) = store.setStr("or_key", v)
    var model: String get() = store.str("or_model", "openrouter/auto"); set(v) = store.setStr("or_model", v)
    suspend fun chat(system: String, msgs: List<Pair<String, String>>): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (apiKey.isBlank()) error("OpenRouter API key is not set (Settings)")
            val arr = JSONArray().put(JSONObject().put("role", "system").put("content", system))
            msgs.forEach { arr.put(JSONObject().put("role", it.first).put("content", it.second)) }
            val req = Request.Builder().url("https://openrouter.ai/api/v1/chat/completions").header("Authorization", "Bearer $apiKey")
                .post(JSONObject().put("model", model).put("messages", arr).toString().toRequestBody("application/json".toMediaType())).build()
            http.newCall(req).execute().use { r ->
                val s = r.body!!.string()
                if (!r.isSuccessful) error("OpenRouter ${r.code}: " + runCatching { JSONObject(s).getJSONObject("error").getString("message") }.getOrDefault(s.take(120)))
                JSONObject(s).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
            }
        }
    }
}
