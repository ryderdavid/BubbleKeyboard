package com.ryderdavid.bubblekeyboard

import android.content.Context
import android.content.SharedPreferences

/** Settings keys and defaults, shared by the keyboard and its settings screen. */
object Prefs {
    private const val FILE = "bubble_keyboard"

    const val BUBBLE_SIZE = "bubble_size"
    const val RING_SIZE = "ring_size"
    const val ROTATE_BLOOM = "rotate_bloom"
    /** Followed by "x_<screen width px>" / "y_<screen width px>", so each orientation keeps its own spot. */
    const val POSITION_PREFIX = "position_"

    const val DEFAULT_BUBBLE_SIZE = 56 // dp, the size of a floating action button
    const val DEFAULT_RING_SIZE = 150 // dp
    const val DEFAULT_ROTATE_BLOOM = true

    val BUBBLE_SIZE_RANGE = 40..72
    val RING_SIZE_RANGE = 110..200

    fun get(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
