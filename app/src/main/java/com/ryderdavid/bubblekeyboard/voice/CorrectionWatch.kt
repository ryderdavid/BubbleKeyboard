package com.ryderdavid.bubblekeyboard.voice

import android.os.SystemClock

/**
 * Keeps an eye on the last text Voicebar typed so the user's hand corrections to it can be
 * learned. The keyboard reads the field when it can (it's the active keyboard); while Gboard is
 * up, the accessibility service passes along the field's text as it changes. When the field no
 * longer holds what was typed (the message was sent, the field cleared), the last version seen is
 * the corrected one. Main thread.
 */
object CorrectionWatch {
    data class Pending(val text: String, val pkg: String?, val field: String?, val at: Long)

    /** Watching this long after typing, at most. */
    private const val WATCH_MS = 15 * 60_000L

    var pending: Pending? = null
        private set
    /** The field's text as the accessibility service last saw it (while Gboard was up). */
    private var latest: String? = null
    /** Told about a field text to compare with what was typed; true when it found corrections. */
    var onCheck: ((Pending, String) -> Boolean)? = null

    fun start(text: String, pkg: String?, field: String?) {
        pending = Pending(text, pkg, field, SystemClock.uptimeMillis())
        latest = null
    }

    fun stop() { pending = null; latest = null }

    private fun live(): Pending? {
        val p = pending ?: return null
        if (SystemClock.uptimeMillis() - p.at > WATCH_MS) { stop(); return null }
        return p
    }

    /** Compares [fieldText] with what was typed; stops watching once corrections were found. */
    fun check(fieldText: String?) {
        val p = live() ?: return
        val t = fieldText ?: latest ?: return
        if (onCheck?.invoke(p, t) == true) stop()
    }

    /** The field is going away (another field, another app): one last look, then stop. */
    fun finish(fieldText: String?) {
        check(fieldText)
        stop()
    }

    /** The accessibility service saw [text] in a field of [pkg]. */
    fun seen(pkg: String?, text: String) {
        val p = live() ?: return
        if (p.pkg != null && pkg != null && p.pkg != pkg) return
        if (overlap(p.text, text) >= 0.5) { latest = text; return }
        // what we typed is gone from the field (sent, cleared): the last version seen was the corrected one
        latest?.let { finish(it) } ?: stop()
    }

    /** The share of the typed words still in [field]. */
    fun overlap(typed: String, field: String): Double {
        fun ws(s: String) = s.lowercase().split(Regex("\\s+")).map { w -> w.filter { it.isLetterOrDigit() } }.filter { it.isNotEmpty() }
        val a = ws(typed)
        if (a.isEmpty()) return 0.0
        val b = ws(field).groupingBy { it }.eachCount().toMutableMap()
        var hit = 0
        for (w in a) { val n = b[w] ?: 0; if (n > 0) { hit++; b[w] = n - 1 } }
        return hit.toDouble() / a.size
    }
}
