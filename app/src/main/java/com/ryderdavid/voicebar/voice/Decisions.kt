package com.ryderdavid.voicebar.voice

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * The auto-listen safeguard's second opinion, with an OpenAI key: OpenAI's Decisions API (on GPT-6
 * Luna) is asked whether what's being dictated is still a message for this conversation, or has
 * drifted into talking to the room. One yes/no question, a probability back in about 200 ms, for a
 * fraction of a cent an hour. Cleanup stays with Claude; this only judges.
 */
object Decisions {
    const val MODEL = "gpt-6-luna"
    /** Below this probability that it's a message, it's the room: the dictation goes to the clipboard. */
    const val ROOM_BELOW = 0.25
    /** How often a running dictation is checked. */
    const val EVERY_MS = 10_000L
    /** Too few words to judge yet. */
    const val MIN_WORDS = 6

    const val QUESTION = "Is the dictated text a message the speaker is composing to send in this conversation, " +
        "as opposed to the speaker having trailed off into talking with people around them, a TV or other background " +
        "speech, or talk that isn't meant to be sent? Judge the whole dictated text, mostly its latest part."

    fun input(app: String, conversation: String, dictated: String): String = buildString {
        if (app.isNotBlank()) append("App: ").append(app).append('\n')
        if (conversation.isNotBlank()) append("Conversation so far (latest last):\n").append(conversation.takeLast(800)).append('\n')
        append("Dictated text:\n").append(dictated.takeLast(1500))
    }

    fun body(input: String, model: String = MODEL): String = JSONObject()
        .put("model", model)
        .put("input", input)
        .put("questions", JSONArray().put(JSONObject().put("type", "predicate").put("name", "message").put("instructions", QUESTION)))
        .toString()

    /** The probability it's a message, or null (refused, unreadable). */
    fun probability(response: String): Double? = runCatching {
        val answers = JSONObject(response).getJSONArray("answers")
        (0 until answers.length()).map { answers.getJSONObject(it) }
            .firstOrNull { it.optString("type") == "predicate" && it.has("probability") }?.getDouble("probability")
    }.getOrNull()

    fun inputTokens(response: String): Long = runCatching { JSONObject(response).getJSONObject("usage").optLong("input_tokens") }.getOrDefault(0)

    /** Blocking: the probability [input]'s dictated text is a message, or null when it couldn't tell. */
    fun ask(client: OkHttpClient, apiKey: String, input: String, baseUrl: String = "https://api.openai.com",
            onUsage: ((String, Long, Long) -> Unit)? = null): Double? = runCatching {
        val request = Request.Builder()
            .url("$baseUrl/v1/decisions")
            .header("Authorization", "Bearer $apiKey")
            .post(body(input).toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { r ->
            val b = r.body?.string() ?: return@use null
            onUsage?.invoke("$MODEL-decisions", inputTokens(b), 0)
            if (r.isSuccessful) probability(b) else null
        }
    }.getOrNull()
}

/** The safeguard's recent verdicts, kept on the phone to see how it judges (and tune it). */
object SafeguardLog {
    private const val PREFS = "safeguard_log"
    private const val KEY = "entries"
    const val KEEP = 30

    data class Entry(val time: Long, val app: String, val probability: Double?, val words: Int, val diverted: Boolean, val start: String)

    fun add(context: Context, e: Entry) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = runCatching { JSONArray(p.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        arr.put(JSONObject().put("t", e.time).put("a", e.app).put("p", e.probability ?: JSONObject.NULL).put("w", e.words).put("d", e.diverted).put("s", e.start))
        val keep = JSONArray()
        for (i in maxOf(0, arr.length() - KEEP) until arr.length()) keep.put(arr.get(i))
        p.edit().putString(KEY, keep.toString()).apply()
    }

    fun all(context: Context): List<Entry> = runCatching {
        val arr = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            Entry(it.optLong("t"), it.optString("a"), if (it.isNull("p")) null else it.optDouble("p"), it.optInt("w"), it.optBoolean("d"), it.optString("s"))
        }.reversed()
    }.getOrDefault(emptyList())

    fun clear(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply() }
}
