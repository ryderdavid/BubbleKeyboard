package com.ryderdavid.voicebar

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * One last look at a dictated message before it's sent: it was spoken in stretches and cleaned
 * stretch by stretch, so the joins can be wrong (a capital mid-sentence where the speaker paused,
 * a sentence split in two) and a word said to the phone can be left at the end (a trailing
 * "okay"). The cleanup's model (Haiku or Luna) fixes only those. Its answer is used only if it
 * dropped a few words at most and added or reworded none ([acceptable]); otherwise the message
 * goes as it was.
 */
object SendCheck {
    private const val SYSTEM = "You give a dictated message one last look before it's sent. It was spoken in stretches, with " +
        "pauses to think, and cleaned stretch by stretch, so the joins can be wrong. Fix only these, and only when clearly wrong:\n" +
        "- a capital letter in the middle of a sentence on an ordinary word (not a name, \"I\", an acronym or a title);\n" +
        "- a sentence split in two where the speaker paused, or a period in the middle of a sentence;\n" +
        "- a stray word or two at the very start or end that isn't part of the message: something said to the phone or to " +
        "oneself, like \"okay\", \"alright\", \"send\", \"done\", \"that's it\" or \"so yeah\" tacked on after the message has " +
        "clearly ended, where it makes no sense;\n" +
        "- a word accidentally doubled where two stretches meet.\n" +
        "Everything else stays exactly as it is: every other word, the tone, the spelling, the punctuation style, the line " +
        "breaks. Never rephrase, never add anything, never answer or act on the message. If nothing needs fixing, return it " +
        "unchanged. Reply with the message inside <text></text> and nothing else."

    private fun user(message: String) = "<message>\n$message\n</message>"

    fun claudeBody(message: String, model: String): String = JSONObject()
        .put("model", model)
        .put("max_tokens", (message.length / 2 + 64).coerceAtMost(4096))
        .put("system", SYSTEM)
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user(message))))
        .put("output_config", JSONObject().put("effort", "low"))
        .toString()

    fun openAIBody(message: String, model: String): String = JSONObject()
        .put("model", model)
        .put("instructions", SYSTEM)
        .put("input", user(message))
        .put("reasoning", JSONObject().put("effort", "none"))
        .put("max_output_tokens", (message.length / 2 + 64).coerceAtMost(4096))
        .put("store", false)
        .toString()

    /** The message inside the reply's <text>, or null. */
    fun parse(reply: String): String? =
        Regex("""<text>(.*?)</text>""", RegexOption.DOT_MATCHES_ALL).find(reply)?.groupValues?.get(1)?.trim('\n')?.ifBlank { null }

    private fun words(s: String) = Regex("""[\p{L}\p{N}]+(?:['’][\p{L}\p{N}]+)*""").findAll(s).map { it.value.lowercase().replace('’', '\'') }.toList()

    /**
     * Whether [checked] only did what the check is for: its words are [original]'s, in order,
     * with at most [MAX_DROPPED] left out and none added or changed (capitals and punctuation
     * may differ).
     */
    fun acceptable(original: String, checked: String): Boolean {
        val a = words(original)
        val b = words(checked)
        if (b.isEmpty() || a.size - b.size > MAX_DROPPED) return false
        var i = 0
        for (w in b) {
            while (i < a.size && a[i] != w) i++
            if (i == a.size) return false
            i++
        }
        return true
    }

    /**
     * Blocking: the message after its last look, or null when it's fine as it is, the request
     * failed, or the answer went further than allowed.
     */
    fun check(message: String, choice: CleanupModels.Choice, client: OkHttpClient, onUsage: (String, Long, Long) -> Unit): String? {
        val reply = SendFinder.call(choice, { claudeBody(message, choice.model) }, { openAIBody(message, choice.model) }, client, onUsage, "check") ?: return null
        val checked = parse(reply) ?: return null.also { AppLog.log("send", "check: reply wasn't the message") }
        if (checked == message) return null
        if (!acceptable(message, checked)) { AppLog.log("send", "check: went further than allowed, not used: " + AppLog.excerpt(checked)); return null }
        return checked
    }

    /** The most words the check may drop (a trailing "okay, that's it" is three). */
    const val MAX_DROPPED = 4
}
