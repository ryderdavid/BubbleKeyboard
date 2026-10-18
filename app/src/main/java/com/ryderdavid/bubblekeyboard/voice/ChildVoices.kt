package com.ryderdavid.bubblekeyboard.voice

import android.content.Context
import com.ryderdavid.bubblekeyboard.Prefs

/** The "Ignore children's voices" setting. */
object ChildVoices {
    fun enabled(context: Context) = Prefs.get(context).getBoolean(Prefs.CHILD_FILTER, false)
    fun strength(context: Context) = Prefs.get(context).getInt(Prefs.CHILD_FILTER_STRENGTH, ChildFilter.DEFAULT_STRENGTH)
}
