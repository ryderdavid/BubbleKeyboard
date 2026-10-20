package com.ryderdavid.voicebar

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.ryderdavid.voicebar.voice.Dictionary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Settings to a file and back: every setting, the dictionary (with what it learned from your
 * fixes), the voiceprint and the usage history. Never the API keys (they'd sit in a plain file in
 * Downloads) and not downloaded speech models (large, and fetched again in a tap).
 *
 * Carries a setup from one install to another, including from the old "Bubble Keyboard" app ID
 * to Voicebar 1.0's.
 */
object Backup {
    const val FORMAT = "voicebar-settings"
    const val VERSION = 1

    /** The shared-preferences files that travel, by their role in the file → their name on the phone (the keys file never does). */
    private val PREF_FILES = mapOf("settings" to Prefs.FILE, "usage" to "usage")
    private const val DICTIONARY = "dictionary.json"
    private const val VOICEPRINT = "voiceprint.bin"

    data class Summary(val settings: Int, val words: Int, val voiceprint: Boolean)

    fun export(context: Context): String {
        val prefs = JSONObject()
        for ((role, name) in PREF_FILES) prefs.put(role, prefsToJson(context.getSharedPreferences(name, Context.MODE_PRIVATE)))
        val out = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exported", System.currentTimeMillis())
            .put("prefs", prefs)
        File(context.filesDir, DICTIONARY).takeIf { it.exists() }?.let { out.put("dictionary", JSONArray(it.readText())) }
        File(context.filesDir, VOICEPRINT).takeIf { it.exists() }?.let { out.put("voiceprint", Base64.encodeToString(it.readBytes(), Base64.NO_WRAP)) }
        return out.toString(1)
    }

    /** Reads a file made by [export] into this install; throws with a plain message if it isn't one. */
    fun import(context: Context, text: String): Summary {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: throw IllegalArgumentException("That file isn't a Voicebar settings file.")
        require(root.optString("format") == FORMAT) { "That file isn't a Voicebar settings file." }
        require(root.optInt("version") <= VERSION) { "That file is from a newer Voicebar. Update this one first." }
        var settings = 0
        root.optJSONObject("prefs")?.let { prefs ->
            for ((role, name) in PREF_FILES) prefs.optJSONObject(role)?.let { settings += jsonToPrefs(it, context.getSharedPreferences(name, Context.MODE_PRIVATE)) }
        }
        var words = 0
        root.optJSONArray("dictionary")?.let { arr ->
            File(context.filesDir, DICTIONARY).writeText(arr.toString())
            Dictionary.of(context).reload()
            words = arr.length()
        }
        val print = root.optString("voiceprint").takeIf { it.isNotEmpty() }?.let { Base64.decode(it, Base64.NO_WRAP) }
        if (print != null) File(context.filesDir, VOICEPRINT).writeBytes(print)
        return Summary(settings, words, print != null)
    }

    fun fileName(now: Long = System.currentTimeMillis()): String =
        "voicebar-settings-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(now)) + ".json"

    private fun prefsToJson(p: SharedPreferences): JSONObject {
        val o = JSONObject()
        for ((k, v) in p.all) {
            val typed = when (v) {
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is String -> JSONObject().put("t", "s").put("v", v)
                is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
                else -> null
            }
            if (typed != null) o.put(k, typed)
        }
        return o
    }

    private fun jsonToPrefs(o: JSONObject, p: SharedPreferences): Int {
        val e = p.edit()
        var n = 0
        for (k in o.keys()) {
            val t = o.optJSONObject(k) ?: continue
            when (t.optString("t")) {
                "b" -> e.putBoolean(k, t.getBoolean("v"))
                "i" -> e.putInt(k, t.getInt("v"))
                "l" -> e.putLong(k, t.getLong("v"))
                "f" -> e.putFloat(k, t.getDouble("v").toFloat())
                "s" -> e.putString(k, t.getString("v"))
                "ss" -> e.putStringSet(k, t.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
                else -> continue
            }
            n++
        }
        e.commit()
        return n
    }
}
