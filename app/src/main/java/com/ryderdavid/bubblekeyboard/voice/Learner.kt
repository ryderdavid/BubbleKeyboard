package com.ryderdavid.bubblekeyboard.voice

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Asks Claude whether a hand correction is worth remembering: a personal name, a project, a
 * username, an uncommon term the speech model got wrong, versus an ordinary edit (a rewrite, a
 * grammar fix, a changed fact). Kept pure apart from [judge] so the request and reply can be tested.
 */
object LearnerRequest {
    enum class Verdict { VOCABULARY, REPLACEMENT, BOTH, NONE }
    data class Result(val verdict: Verdict, val term: String, val heard: String)

    val SYSTEM = """
        You help a dictation keyboard learn from its user's corrections. You get a phrase the speech
        recognizer produced (<heard>), what the user changed it to (<fixed>), and a few words around it.
        Decide whether this is a reusable, user-specific correction worth remembering:
        - Yes: personal names, places, internal project or product names, usernames, uncommon or specialized
          terms, or a phrase the recognizer clearly misheard as other words.
        - No: rewording, grammar or tense fixes, a different word chosen on purpose, changed numbers or facts,
          capitalization only, expanding an abbreviation, common words, and well-known brands or terms that any
          recognizer knows (Apple, GitHub, Monday).
        Answer with JSON only: {"verdict": "vocabulary" | "replacement" | "both" | "none", "term": "<the corrected
        term as it should be written>", "heard": "<the misheard phrase>"}.
        "vocabulary": a full name or term to recognize; "replacement": this exact mishearing should become the term
        (e.g. a first name alone, or a recurring sound-alike); "both" when both help.
    """.trimIndent()

    fun body(c: Corrections.Change, model: String): String = JSONObject()
        .put("model", model)
        .put("max_tokens", 120)
        .put("system", SYSTEM)
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content",
            "<before>${c.before}</before>\n<heard>${c.heard}</heard>\n<fixed>${c.fixed}</fixed>\n<after>${c.after}</after>")))
        .put("output_config", JSONObject().put("effort", "low"))
        .toString()

    fun parse(response: String): Result? {
        val text = ClaudeCleanupRequest.parse(response) ?: return null
        val json = Regex("\\{[\\s\\S]*\\}").find(text)?.value ?: return null
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val v = when (o.optString("verdict").lowercase()) {
            "vocabulary" -> Verdict.VOCABULARY
            "replacement" -> Verdict.REPLACEMENT
            "both" -> Verdict.BOTH
            else -> Verdict.NONE
        }
        val term = o.optString("term").trim()
        if (v != Verdict.NONE && term.isEmpty()) return null
        return Result(v, term, o.optString("heard").trim())
    }

    fun judge(c: Corrections.Change, apiKey: String, model: String, client: OkHttpClient,
              onUsage: ((String, Long, Long) -> Unit)? = null, baseUrl: String = "https://api.anthropic.com"): Result? {
        val request = Request.Builder()
            .url("$baseUrl/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body(c, model).toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            client.newCall(request).execute().use { r ->
                val b = r.body?.string()
                b?.let { ClaudeCleanupRequest.usage(it) }?.let { (i, o) -> onUsage?.invoke(model, i, o) }
                if (!r.isSuccessful) null else parse(b ?: return@use null)
            }
        }.getOrNull()
    }
}
