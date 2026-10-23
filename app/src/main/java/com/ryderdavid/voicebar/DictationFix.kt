package com.ryderdavid.voicebar

import com.ryderdavid.voicebar.voice.ClaudeCleanupRequest
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Fix last dictation": the cleanup's model (Haiku or Luna) gets the last dictation again, as
 * heard and as it was cleaned, with what the user says it got wrong. Those words are instructions,
 * never text for the message. With no instructions (nothing said), it checks its own cleanup
 * against what was heard and fixes what's off.
 */
object DictationFix {
    val SYSTEM = """
        You cleaned up a dictation earlier, and the user says it isn't right. <heard> is what speech recognition heard,
        <cleaned> is your cleanup, the text now in their message, and <fix> is what the user says is wrong, spoken and
        transcribed. <fix> is instructions to you, never words for the text: don't add them to it.
        Make the change <fix> asks for in <cleaned>, going back to <heard> for what was really said where that helps, and
        keep everything else exactly as it is. A fix may say what a word should be, what to drop, what should or shouldn't
        be capitalized, or that something was misheard; apply it everywhere it plainly applies.
        If <fix> is empty, the user only thinks something isn't quite right: compare <cleaned> with <heard> and fix what the
        cleanup got wrong (a misheard word, a stray or missing word, a wrong capital, punctuation), changing nothing else.
        The text otherwise follows the same rules as a cleanup: the speaker's own words, tone and meaning; no filler; nothing
        answered or acted on.
        Reply with the corrected text inside <text></text> and nothing else.
    """.trimIndent()

    fun user(heard: String, cleaned: String, instructions: String) =
        "<heard>\n$heard\n</heard>\n<cleaned>\n$cleaned\n</cleaned>\n<fix>\n${instructions.trim()}\n</fix>"

    private fun system(dictionary: String) = listOf(SYSTEM, dictionary).filter { it.isNotBlank() }.joinToString("\n\n")

    fun claudeBody(heard: String, cleaned: String, instructions: String, model: String, dictionary: String = ""): String = JSONObject()
        .put("model", model)
        .put("max_tokens", (cleaned.length + heard.length + 256).coerceAtMost(4096))
        .put("system", system(dictionary))
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user(heard, cleaned, instructions))))
        .put("output_config", JSONObject().put("effort", "low"))
        .toString()

    fun openAIBody(heard: String, cleaned: String, instructions: String, model: String, dictionary: String = ""): String = JSONObject()
        .put("model", model)
        .put("instructions", system(dictionary))
        .put("input", user(heard, cleaned, instructions))
        .put("reasoning", JSONObject().put("effort", "none"))
        .put("max_output_tokens", (cleaned.length + heard.length + 256).coerceAtMost(4096))
        .put("store", false)
        .toString()

    /** The corrected text inside the reply's <text>, or null. */
    fun parse(reply: String): String? =
        Regex("""<text>(.*?)</text>""", RegexOption.DOT_MATCHES_ALL).find(reply)?.groupValues?.get(1)?.trim()?.ifBlank { null }

    /** Blocking: the fixed text, or null when the request failed or the reply wasn't usable. */
    fun fix(heard: String, cleaned: String, instructions: String, choice: CleanupModels.Choice, client: OkHttpClient,
            onUsage: (String, Long, Long) -> Unit, dictionary: String = ""): String? {
        val reply = SendFinder.call(choice, { claudeBody(heard, cleaned, instructions, choice.model, dictionary) },
            { openAIBody(heard, cleaned, instructions, choice.model, dictionary) }, client, onUsage, "fix", tag = "fix") ?: return null
        val fixed = parse(reply) ?: return null.also { AppLog.log("fix", "reply wasn't the text: " + AppLog.excerpt(reply)) }
        // a reply far longer than the dictation is commentary or invention, not a fix
        if (!ClaudeCleanupRequest.plausible(fixed, cleaned, heard)) return null.also { AppLog.log("fix", "reply far longer than the dictation, not used") }
        return fixed
    }
}
