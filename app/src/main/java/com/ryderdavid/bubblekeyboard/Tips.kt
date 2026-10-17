package com.ryderdavid.bubblekeyboard

import android.content.Context
import androidx.core.content.edit

/**
 * The first-run tips on the bar: which one to show, and when. One tip per time the keyboard
 * opens (the "Tap to finish" one during the first recording instead); using a feature retires
 * its tip, "Got it" retires the one showing, and "Skip tips" turns them all off.
 */
object Tips {
    enum class Id(val anchor: String, val title: Int, val body: Int) {
        VOICE("voice", R.string.tip_voice_title, R.string.tip_voice),
        FINISH("voice", R.string.tip_finish_title, R.string.tip_finish),
        ABC("abc", R.string.tip_abc_title, R.string.tip_abc),
        CURSOR("cursor", R.string.tip_cursor_title, R.string.tip_cursor),
        BKSP("bksp", R.string.tip_bksp_title, R.string.tip_bksp),
        VOICEBAR("voice", R.string.tip_voicebar_title, R.string.tip_voicebar),
    }

    enum class Moment { OPEN, RECORDING }

    /** Dictations before the "voice bar" tip, so the basics come first. */
    const val VOICEBAR_AFTER = 3

    /** What the bar reports using ("voice", "abc", …) to the tip it retires. */
    fun forUse(key: String): Id? = when (key) {
        "voice" -> Id.VOICE
        "finish" -> Id.FINISH
        "abc" -> Id.ABC
        "cursor" -> Id.CURSOR
        "bksp" -> Id.BKSP
        "voicebar" -> Id.VOICEBAR
        else -> null
    }

    /** The tip for this moment, or null. [shownThisOpen]: a tip already came up since the keyboard opened. */
    fun next(done: Set<Id>, moment: Moment, dictations: Int, shownThisOpen: Boolean): Id? {
        if (moment == Moment.RECORDING) return Id.FINISH.takeIf { it !in done && Id.VOICE in done }
        if (shownThisOpen) return null
        if (Id.VOICE !in done) return Id.VOICE
        for (id in listOf(Id.ABC, Id.CURSOR, Id.BKSP)) if (id !in done) return id
        if (Id.VOICEBAR !in done && dictations >= VOICEBAR_AFTER) return Id.VOICEBAR
        return null
    }

    // ---------------------------------------------------------------- saved state

    fun on(context: Context) = Prefs.get(context).getBoolean(Prefs.TIPS, Prefs.DEFAULT_TIPS)

    fun done(context: Context): Set<Id> =
        Prefs.get(context).getStringSet(Prefs.TIPS_DONE, emptySet()).orEmpty().mapNotNull { n -> Id.entries.firstOrNull { it.name == n } }.toSet()

    fun markDone(context: Context, id: Id) {
        val now = done(context) + id
        Prefs.get(context).edit { putStringSet(Prefs.TIPS_DONE, now.map { it.name }.toSet()) }
    }

    fun dictations(context: Context) = Prefs.get(context).getInt(Prefs.TIPS_DICTATIONS, 0)

    fun countDictation(context: Context) {
        val n = dictations(context)
        if (n < 100) Prefs.get(context).edit { putInt(Prefs.TIPS_DICTATIONS, n + 1) }
    }

    /** Show them all again from the start. */
    fun reset(context: Context) = Prefs.get(context).edit {
        putBoolean(Prefs.TIPS, true); remove(Prefs.TIPS_DONE); remove(Prefs.TIPS_DICTATIONS)
    }
}
