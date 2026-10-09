package com.ryderdavid.bubblekeyboard

import android.content.Context
import android.content.SharedPreferences

/** Settings keys and defaults, shared by the keyboard and its settings screen. */
object Prefs {
    private const val FILE = "bubble_keyboard"

    const val BUBBLE_SIZE = "bubble_size"
    const val RING_SIZE = "ring_size"
    const val ROTATE_BLOOM = "rotate_bloom"
    const val SWELL = "swell_strength"
    const val BUBBLE_OPACITY = "bubble_opacity"
    const val RING_TIMEOUT = "ring_timeout_s"
    const val VOICE_LIVE = "voice_live"
    const val TAP_TO_TALK = "tap_to_talk"
    const val HOLD_TO_TALK = "hold_to_talk"
    const val MILLENNIAL = "millennial_mode"
    const val SILENCE_TIMEOUT = "silence_timeout_s"
    const val CLEANUP = "voice_cleanup"
    const val CLEANUP_MODEL = "voice_cleanup_model"
    /** Followed by "x_<screen width px>" / "y_<screen width px>", so each orientation keeps its own spot. */
    const val POSITION_PREFIX = "position_"

    const val DEFAULT_BUBBLE_SIZE = 56 // dp, the size of a floating action button
    const val DEFAULT_RING_SIZE = 150 // dp
    const val DEFAULT_ROTATE_BLOOM = true
    const val DEFAULT_SWELL = 70 // percent
    const val DEFAULT_BUBBLE_OPACITY = 100 // percent
    const val DEFAULT_CLEANUP = true
    const val DEFAULT_VOICE_LIVE = true
    const val DEFAULT_TAP_TO_TALK = true
    const val DEFAULT_HOLD_TO_TALK = true
    const val DEFAULT_MILLENNIAL = false
    const val DEFAULT_SILENCE_TIMEOUT = 10 // seconds of quiet before hands-free dictation finishes
    const val DEFAULT_RING_TIMEOUT = 5 // seconds; paused while dictating

    val BUBBLE_SIZE_RANGE = 40..72
    val RING_SIZE_RANGE = 110..200
    val SWELL_RANGE = 0..100
    val BUBBLE_OPACITY_RANGE = 20..100
    val RING_TIMEOUT_RANGE = 5..120
    val SILENCE_TIMEOUT_RANGE = 3..60

    fun get(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
