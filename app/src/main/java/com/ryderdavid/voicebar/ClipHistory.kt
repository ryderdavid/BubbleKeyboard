package com.ryderdavid.voicebar

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Clipboard history, Gboard-style: the last [KEEP] things copied (while Voicebar is the keyboard, and
 * everything Voicebar copies itself), newest first, with pinned ones kept for good. Nothing marked
 * sensitive (a password manager's copies) is kept. On the phone only; never exported.
 */
class ClipHistory(private val store: Store) {
    interface Store { fun read(): String?; fun write(json: String) }

    data class Clip(val text: String, val time: Long, val pinned: Boolean = false)

    private val clips = ArrayList<Clip>()

    init { load() }

    /** Pinned first, then newest first. */
    @Synchronized fun all(): List<Clip> = clips.sortedWith(compareByDescending<Clip> { it.pinned }.thenByDescending { it.time })

    @Synchronized fun add(text: String, now: Long = System.currentTimeMillis()) {
        val t = text.take(MAX_CHARS)
        if (t.isBlank()) return
        val old = clips.firstOrNull { it.text == t }
        clips.remove(old)
        clips += Clip(t, now, old?.pinned == true)
        // unpinned beyond the limit roll off, oldest first
        val loose = clips.filter { !it.pinned }.sortedByDescending { it.time }
        if (loose.size > KEEP) clips.removeAll(loose.drop(KEEP).toSet())
        save()
    }

    @Synchronized fun setPinned(text: String, pinned: Boolean) {
        val i = clips.indexOfFirst { it.text == text }
        if (i < 0) return
        clips[i] = clips[i].copy(pinned = pinned)
        if (pinned && clips.count { it.pinned } > MAX_PINNED) {
            clips.filter { it.pinned && it.text != text }.minByOrNull { it.time }?.let { o -> clips[clips.indexOf(o)] = o.copy(pinned = false) }
        }
        save()
    }

    @Synchronized fun remove(text: String) { clips.removeAll { it.text == text }; save() }

    @Synchronized fun clear() { clips.removeAll { !it.pinned }; save() }

    private fun load() {
        runCatching {
            val arr = JSONArray(store.read() ?: return)
            for (i in 0 until arr.length()) arr.getJSONObject(i).let { clips += Clip(it.getString("t"), it.optLong("w"), it.optBoolean("p")) }
        }
    }

    private fun save() {
        val arr = JSONArray()
        for (c in clips) arr.put(JSONObject().put("t", c.text).put("w", c.time).put("p", c.pinned))
        store.write(arr.toString())
    }

    companion object {
        const val KEEP = 15
        const val MAX_PINNED = 10
        const val MAX_CHARS = 5000

        @Volatile private var instance: ClipHistory? = null
        fun of(context: Context): ClipHistory = instance ?: synchronized(this) {
            instance ?: run {
                val p = context.applicationContext.getSharedPreferences("clip_history", Context.MODE_PRIVATE)
                ClipHistory(object : Store {
                    override fun read() = p.getString("clips", null)
                    override fun write(json: String) { p.edit().putString("clips", json).apply() }
                })
            }.also { instance = it }
        }

        /** The text of a clip worth keeping, or null (empty, not text, or marked sensitive). */
        fun textOf(context: Context, clip: ClipData?): String? {
            if (clip == null || clip.itemCount == 0) return null
            val extras = clip.description?.extras
            if (extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true || extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) return null
            return clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
        }
    }
}
