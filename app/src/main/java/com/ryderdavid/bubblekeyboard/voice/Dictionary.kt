package com.ryderdavid.bubblekeyboard.voice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Voicebar's personal dictionary: names and terms to recognize (vocabulary) and, for each, the ways
 * it's been misheard (replacements). Learned from the user's hand corrections, or added by hand.
 * It feeds the speech model (ElevenLabs keyterms), the cleanup prompt, and a final pass of
 * replacements. Stored on the phone only, in the app's private files.
 */
class Dictionary internal constructor(private val file: File?) {
    data class Entry(
        val term: String,
        /** Ways it's been misheard ("you da" for "YODA"); empty for vocabulary only. */
        val heard: List<String> = emptyList(),
        /** Feed it to the speech model and the cleanup as a term to recognize. */
        val vocabulary: Boolean = true,
        val count: Int = 1,
        val lastSeen: Long = System.currentTimeMillis(),
        /** Learned from a correction (vs. added by hand). */
        val learned: Boolean = true,
    )

    private val entries = mutableListOf<Entry>()

    init { load() }

    @Synchronized fun all(): List<Entry> = entries.sortedWith(compareByDescending<Entry> { it.count }.thenByDescending { it.lastSeen })

    /** Adds [term] (and a mishearing of it), or counts it again; returns the entry as it is now. */
    @Synchronized fun add(term: String, heard: String? = null, vocabulary: Boolean = true, learned: Boolean = true): Entry {
        val t = term.trim()
        val i = entries.indexOfFirst { it.term.equals(t, ignoreCase = true) }
        val h = heard?.trim()?.takeIf { it.isNotEmpty() && !it.equals(t, ignoreCase = true) }
        val e = if (i < 0) Entry(t, listOfNotNull(h), vocabulary, 1, System.currentTimeMillis(), learned)
        else entries[i].let { old ->
            old.copy(
                heard = (old.heard + listOfNotNull(h)).distinctBy { it.lowercase() },
                vocabulary = old.vocabulary || vocabulary,
                count = old.count + 1,
                lastSeen = System.currentTimeMillis(),
                learned = old.learned && learned,
            )
        }
        if (i < 0) entries += e else entries[i] = e
        save()
        return e
    }

    /** Puts an entry back as it was before an [add] (undo), or removes it if it was new. */
    @Synchronized fun restore(term: String, previous: Entry?) {
        entries.removeAll { it.term.equals(term, ignoreCase = true) }
        if (previous != null) entries += previous
        save()
    }

    @Synchronized fun find(term: String): Entry? = entries.firstOrNull { it.term.equals(term, ignoreCase = true) }

    @Synchronized fun remove(term: String) { entries.removeAll { it.term.equals(term, ignoreCase = true) }; save() }

    /** Terms for the speech model: the most used vocabulary that fits its limits (50 terms, 20 characters). */
    @Synchronized fun keyterms(limit: Int = 50, maxLength: Int = 20): List<String> =
        all().filter { it.vocabulary && it.term.length in 2..maxLength }.take(limit).map { it.term }

    /** The dictionary for the cleanup prompt; empty when there's nothing in it. */
    @Synchronized fun promptSection(): String {
        val all = all()
        if (all.isEmpty()) return ""
        val sb = StringBuilder("The speaker's personal dictionary. Spell these names and terms exactly as written when they're what was said:\n")
        all.filter { it.vocabulary }.take(200).forEach { sb.append("- ").append(it.term).append('\n') }
        val misheard = all.filter { it.heard.isNotEmpty() }.take(200)
        if (misheard.isNotEmpty()) {
            sb.append("Speech recognition often mishears these; when the transcript has the left side and the context fits, write the right side:\n")
            misheard.forEach { e -> e.heard.forEach { h -> sb.append("- \"").append(h).append("\" → \"").append(e.term).append("\"\n") } }
        }
        return sb.toString().trim()
    }

    /**
     * The sure replacements, done without the model: mishearings added by hand, or seen at
     * least twice. Whole words only, ignoring case.
     */
    @Synchronized fun applySure(text: String): String {
        var out = text
        for (e in entries) {
            if (e.learned && e.count < 2) continue
            for (h in e.heard) {
                val re = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(h) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
                out = out.replace(re, Regex.escapeReplacement(e.term))
            }
        }
        return out
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val heard = o.optJSONArray("heard")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                entries += Entry(o.getString("term"), heard, o.optBoolean("vocabulary", true), o.optInt("count", 1),
                    o.optLong("lastSeen", 0L), o.optBoolean("learned", true))
            }
        }
    }

    private fun save() {
        val f = file ?: return
        val arr = JSONArray()
        for (e in entries) arr.put(JSONObject().put("term", e.term).put("heard", JSONArray(e.heard)).put("vocabulary", e.vocabulary)
            .put("count", e.count).put("lastSeen", e.lastSeen).put("learned", e.learned))
        runCatching { f.writeText(arr.toString()) }
    }

    companion object {
        @Volatile private var instance: Dictionary? = null
        fun of(context: Context): Dictionary = instance ?: synchronized(this) {
            instance ?: Dictionary(File(context.applicationContext.filesDir, "dictionary.json")).also { instance = it }
        }
    }
}
