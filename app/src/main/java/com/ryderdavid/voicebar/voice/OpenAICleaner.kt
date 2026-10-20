package com.ryderdavid.voicebar.voice

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Cleanup with GPT-6 Luna through OpenAI's Responses API: the same instructions, the same reply
 * format and the same checks as Claude's ([ClaudeCleanupRequest]), so either can be chosen.
 */
object OpenAICleanupRequest {
    const val LUNA = "gpt-6-luna"

    fun body(cleaned: String, raw: String, model: String, dictionary: String = "", screen: String = "", children: Boolean = false, around: Around? = null): String = JSONObject()
        .put("model", model)
        .put("instructions", ClaudeCleanupRequest.system(dictionary, screen, children, around))
        .put("input", ClaudeCleanupRequest.user(cleaned, raw, screen, around))
        // a light edit: latency matters more than depth
        .put("reasoning", JSONObject().put("effort", "low"))
        .put("max_output_tokens", 2048)
        .put("store", false)
        .toString()

    /** The reply's text: every output_text of the message items (never assume it's output[0]). */
    fun text(response: String): String? = runCatching {
        val out = JSONObject(response).getJSONArray("output")
        val sb = StringBuilder()
        for (i in 0 until out.length()) {
            val item = out.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val c = content.optJSONObject(j) ?: continue
                if (c.optString("type") == "output_text") sb.append(c.optString("text"))
            }
        }
        sb.toString()
    }.getOrNull()?.ifBlank { null }

    fun usage(response: String): Pair<Long, Long>? = runCatching {
        val u = JSONObject(response).getJSONObject("usage")
        u.optLong("input_tokens") to u.optLong("output_tokens")
    }.getOrNull()
}

class OpenAICleaner(
    private val apiKey: String,
    private val model: String,
    private val client: OkHttpClient,
    private val baseUrl: String = "https://api.openai.com",
    private val dictionary: () -> String = { "" },
    private val screen: () -> String = { "" },
    private val children: () -> Boolean = { false },
    private val around: () -> Around? = { null },
    private val onUsage: ((model: String, input: Long, output: Long) -> Unit)? = null,
) : Cleaner {
    @Volatile override var problem: String? = null
        private set

    override fun clean(raw: String): String? = clean("", raw)
    override fun clean(cleaned: String, raw: String): String? = run(cleaned, raw, withScreen = true)
    override fun cleanAtPause(cleaned: String, raw: String): String? = run(cleaned, raw, withScreen = false)

    private fun run(cleaned: String, raw: String, withScreen: Boolean): String? {
        val aroundNow = around()
        val request = Request.Builder()
            .url("$baseUrl/v1/responses")
            .header("Authorization", "Bearer $apiKey")
            .post(OpenAICleanupRequest.body(cleaned, raw, model, dictionary(), if (withScreen) screen() else "", children(), aroundNow)
                .toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(request).execute().use { response ->
            val body = response.body?.string()
            body?.let { OpenAICleanupRequest.usage(it) }?.let { (i, o) -> onUsage?.invoke(model, i, o) }
            problem = null
            if (!response.isSuccessful) {
                problem = "OpenAI answered ${response.code}" + (ClaudeCleanupRequest.errorMessage(body)?.let { ": $it" } ?: "")
                return null
            }
            val parsed = OpenAICleanupRequest.text(body ?: "")?.let { ClaudeCleanupRequest.parseText(it) }
                ?.let { ClaudeCleanupRequest.trimAround(it, aroundNow) }?.ifBlank { null }
            when {
                parsed == null -> { problem = "OpenAI's reply wasn't the cleaned text"; null }
                !ClaudeCleanupRequest.plausible(parsed, cleaned, raw) -> { problem = "OpenAI's reply was far longer than what you said"; null }
                else -> parsed
            }
        }
    }
}
