package com.ryderdavid.voicebar

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's Send button when nothing on it says "send" (an unlabeled arrow): the buttons to the
 * right of the message box are numbered on a picture of that row, and the cleanup's model (Claude
 * Haiku or GPT-6 Luna, whichever the cleanup uses) says which one sends.
 * Asked alongside a dictation's cleanup when it's swiped to send, and only used when the usual
 * search (the field's own action, a button labeled or named "send") comes up empty. A pick that
 * sends is remembered for that app, by its view id or its place counting from the right, so the
 * next send there needs no asking; one that doesn't send is forgotten (the app may have changed).
 */
object SendFinder {
    /** Screen pixels. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val cx get() = (left + right) / 2
        val cy get() = (top + bottom) / 2
        val width get() = right - left
        val height get() = bottom - top
    }

    /** A clickable control on screen: where, what it says about itself, and its kind (ImageButton, …). */
    data class Button(val box: Box, val label: String, val id: String, val kind: String)

    /**
     * The composer row: the app, its message box, the buttons to the box's right (see [candidates]),
     * and a JPEG of the row with them numbered (null below Android 11 or when the screenshot failed).
     */
    class Row(val app: String, val field: Box, val buttons: List<Button>, val jpegBase64: String? = null)

    /** The model's pick, and the buttons it was picked from (for [spot]). */
    class Pick(val button: Button, val among: List<Button>)

    /** Not the Send button, whatever else it is. */
    private val NOT_SEND = Regex("""attach|camera|photo|gallery|image|emoji|gif|sticker|\bmic|microphone|voice|record|audio|\bcall\b|video|\bplus\b|\badd\b|keyboard|more options""", RegexOption.IGNORE_CASE)

    /**
     * The buttons that could be Send: on the message box's row (within [slack] of it, up or down)
     * and to its right, minus any that say they're something else, left to right, at most [MAX].
     */
    fun candidates(field: Box, buttons: List<Button>, slack: Int): List<Button> =
        buttons.asSequence()
            .filter { it.box.width > 0 && it.box.height > 0 }
            .filter { it.box.cx >= field.right - slack && it.box.cy >= field.top - slack && it.box.cy <= field.bottom + slack }
            .filter { !NOT_SEND.containsMatchIn(it.label) && !NOT_SEND.containsMatchIn(it.id) }
            .distinctBy { it.box }
            .sortedBy { it.box.cx }
            .take(MAX)
            .toList()

    // ---------------------------------------------------------------- remembering, per app

    /** Where the Send button was: its view id when it has one, else its place counting from the right (0 = rightmost). */
    fun spot(chosen: Button, among: List<Button>): String =
        if (chosen.id.isNotBlank()) "id:${chosen.id}" else "right:${among.size - 1 - among.indexOf(chosen)}"

    /** The button at a remembered [spot] in today's [among], if it's still there. */
    fun at(spot: String, among: List<Button>): Button? = when {
        spot.startsWith("id:") -> among.firstOrNull { it.id == spot.removePrefix("id:") }
        spot.startsWith("right:") -> spot.removePrefix("right:").toIntOrNull()?.let { among.getOrNull(among.size - 1 - it) }
        else -> null
    }

    // ---------------------------------------------------------------- asking the cleanup's model

    const val MAX = 6

    private const val SYSTEM = "You find the Send button in a phone app's message composer. The picture is the row with the " +
        "message box; the buttons to its right are outlined and numbered, and listed below with what the app says about them. " +
        "Answer with JSON only: {\"send\": n} for the button that sends the message, or {\"send\": null} if none clearly does. " +
        "A send button is usually an arrow or paper plane at the right end. Never pick a button for attachments, the camera, " +
        "emoji, stickers, GIFs, voice messages or calls."

    /** The list of buttons that goes with the picture. */
    fun describe(row: Row): String = buildString {
        append("App: ").append(row.app).append('\n')
        if (row.jpegBase64 == null) append("(No picture: decide from the list.)\n")
        append("Buttons, left to right:\n")
        row.buttons.forEachIndexed { i, b ->
            append(i + 1).append(". ").append(b.kind.substringAfterLast('.').ifBlank { "view" })
            if (b.id.isNotBlank()) append(", id \"").append(b.id).append('"')
            if (b.label.isNotBlank()) append(", says \"").append(b.label.take(40)).append('"')
            append(", ").append(b.box.width).append('×').append(b.box.height).append(" px")
            if (i == row.buttons.lastIndex) append(", rightmost")
            append('\n')
        }
    }

    /** Claude's Messages API (Haiku). */
    fun claudeBody(row: Row, model: String): String {
        val content = JSONArray()
        row.jpegBase64?.let {
            content.put(JSONObject().put("type", "image").put("source",
                JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", it)))
        }
        content.put(JSONObject().put("type", "text").put("text", describe(row)))
        return JSONObject()
            .put("model", model)
            .put("max_tokens", 30)
            .put("system", SYSTEM)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .toString()
    }

    /** OpenAI's Responses API (Luna), no reasoning: a quick look, like the cleanup. */
    fun openAIBody(row: Row, model: String): String {
        val content = JSONArray()
        row.jpegBase64?.let { content.put(JSONObject().put("type", "input_image").put("image_url", "data:image/jpeg;base64,$it")) }
        content.put(JSONObject().put("type", "input_text").put("text", describe(row)))
        return JSONObject()
            .put("model", model)
            .put("instructions", SYSTEM)
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("reasoning", JSONObject().put("effort", "none"))
            .put("max_output_tokens", 30)
            .put("store", false)
            .toString()
    }

    /** The reply's words, from either API. */
    fun replyText(response: String, provider: CleanupModels.Provider): String? = when (provider) {
        CleanupModels.Provider.OPENAI -> com.ryderdavid.voicebar.voice.OpenAICleanupRequest.text(response)
        CleanupModels.Provider.ANTHROPIC -> runCatching {
            val blocks = JSONObject(response).getJSONArray("content")
            (0 until blocks.length()).mapNotNull { blocks.optJSONObject(it) }
                .filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
        }.getOrNull()
    }

    /** The button picked (0-based) from the reply's words, or null for none, out of range, or unreadable. */
    fun parse(text: String, count: Int): Int? {
        val json = Regex("""\{[^{}]*\}""").find(text)?.value ?: return null
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (!o.has("send") || o.isNull("send")) return null
        val n = o.optInt("send", 0)
        return (n - 1).takeIf { it in 0 until count }
    }

    fun usage(response: String): Pair<Long, Long>? {
        val u = runCatching { JSONObject(response).getJSONObject("usage") }.getOrNull() ?: return null
        return (u.optLong("input_tokens") + u.optLong("cache_creation_input_tokens") + u.optLong("cache_read_input_tokens")) to u.optLong("output_tokens")
    }

    /**
     * Blocking: one request to the cleanup's model ([choice]: Haiku or Luna), the body made for
     * whichever API that is; the reply's words, or null when it failed. [what] names it in the log.
     */
    fun call(choice: CleanupModels.Choice, claude: () -> String, openAI: () -> String, client: OkHttpClient, onUsage: (String, Long, Long) -> Unit, what: String, tag: String = "send"): String? {
        val isOpenAI = choice.provider == CleanupModels.Provider.OPENAI
        val request = Request.Builder().apply {
            if (isOpenAI) url("https://api.openai.com/v1/responses").header("Authorization", "Bearer ${choice.key}")
            else url("https://api.anthropic.com/v1/messages").header("x-api-key", choice.key).header("anthropic-version", "2023-06-01")
        }
            .post((if (isOpenAI) openAI() else claude()).toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            client.newCall(request).execute().use { r ->
                val body = r.body?.string() ?: return@use null
                usage(body)?.let { (i, o) -> onUsage(choice.model, i, o) }
                if (!r.isSuccessful) { AppLog.log(tag, "$what request failed: ${r.code}"); return@use null }
                replyText(body, choice.provider)
            }
        }.onFailure { AppLog.log(tag, "$what request failed: ${it.javaClass.simpleName}") }.getOrNull()
    }

    /**
     * Blocking: the cleanup model's pick ([choice]: Haiku or Luna, whichever the cleanup uses)
     * from [row]'s buttons, or null (none, or the request failed).
     */
    fun ask(row: Row, choice: CleanupModels.Choice, client: OkHttpClient, onUsage: (String, Long, Long) -> Unit): Button? {
        if (row.buttons.isEmpty()) return null
        val words = call(choice, { claudeBody(row, choice.model) }, { openAIBody(row, choice.model) }, client, onUsage, "finder") ?: return null
        AppLog.log("send", "finder (${choice.model}) said: " + words.take(40))
        return parse(words, row.buttons.size)?.let { row.buttons[it] }
    }
}
