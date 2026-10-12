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
    /** The open ring stays open, with no countdown (pull the up arrow out, or the switch in settings). */
    const val RING_PINNED = "ring_pinned"
    /** Faint glyphs just outside the ring saying what holding each key does. */
    const val HOLD_HINTS = "hold_hints"
    const val VOICE_LIVE = "voice_live"
    /** "docked" (a one-row bar under the app) or "bubble" (the floating bubble). */
    const val STYLE = "keyboard_style"
    const val STYLE_DOCKED = "docked"
    const val STYLE_BUBBLE = "bubble"
    const val MILLENNIAL = "millennial_mode"
    /** ABC hands typing to Gboard, and a bubble over Gboard brings the voice bar back. */
    const val GBOARD_MODE = "gboard_mode"
    const val GBOARD_BUBBLE_X = "gboard_bubble_x"
    const val GBOARD_BUBBLE_GAP = "gboard_bubble_gap"
    /** How the way back from Gboard looks: a bar on top of Gboard, a button in its voice-key spot, or a floating bubble. */
    const val GBOARD_RETURN = "gboard_return"
    const val GBOARD_RETURN_BAR = "bar"
    const val GBOARD_RETURN_MIC_KEY = "mic_key"
    const val GBOARD_RETURN_BUBBLE = "bubble"
    const val DEFAULT_GBOARD_RETURN = GBOARD_RETURN_BAR
    const val GBOARD_PACKAGE = "com.google.android.inputmethod.latin"
    const val DEFAULT_GBOARD_MODE = false
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
    const val DEFAULT_STYLE = STYLE_DOCKED
    const val DEFAULT_VOICE_LIVE = true
    const val DEFAULT_MILLENNIAL = false
    const val DEFAULT_SILENCE_TIMEOUT = 10 // seconds of quiet before hands-free dictation finishes
    const val DEFAULT_RING_PINNED = false
    const val DEFAULT_HOLD_HINTS = false
    const val DEFAULT_RING_TIMEOUT = 5 // seconds; paused while dictating

    val BUBBLE_SIZE_RANGE = 40..72
    val RING_SIZE_RANGE = 110..200
    val SWELL_RANGE = 0..100
    val BUBBLE_OPACITY_RANGE = 20..100
    val RING_TIMEOUT_RANGE = 5..120
    val SILENCE_TIMEOUT_RANGE = 3..60

    /**
     * Gboard's input-method id if it's turned on as a keyboard, else null. Asks the input-method
     * service first, then falls back to the system's enabled-keyboards setting, which isn't filtered
     * by Android's package-visibility rules.
     */
    fun gboardImeId(context: Context): String? {
        val imm = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.enabledInputMethodList?.firstOrNull { it.packageName == GBOARD_PACKAGE }?.id?.let { return it }
        val enabled = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ENABLED_INPUT_METHODS)
            ?: return null
        return enabled.split(':').map { it.substringBefore(';') }.firstOrNull { it.startsWith("$GBOARD_PACKAGE/") }
    }

    fun get(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
