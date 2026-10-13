package com.ryderdavid.bubblekeyboard

import android.content.Context
import android.content.SharedPreferences

/** Settings keys and defaults, shared by the keyboard and its settings screen. */
object Prefs {
    private const val FILE = "bubble_keyboard"

    const val VOICE_LIVE = "voice_live"
    const val MILLENNIAL = "millennial_mode"
    const val GBOARD_BUBBLE_X = "gboard_bubble_x"
    const val GBOARD_BUBBLE_GAP = "gboard_bubble_gap"
    /** How the way back from Gboard looks: a bar on top of Gboard, a button in its voice-key spot, or a floating bubble. */
    const val GBOARD_RETURN = "gboard_return"
    const val GBOARD_RETURN_BAR = "bar"
    const val GBOARD_RETURN_MIC_KEY = "mic_key"
    const val GBOARD_RETURN_BUBBLE = "bubble"
    const val DEFAULT_GBOARD_RETURN = GBOARD_RETURN_BAR
    /** The bar at rest: "pills" (⋮ · Cursor ABC Voice · ⌫) or "equal" (⋮ space Cursor ABC Voice ⌫ ↵, all one size). */
    const val BAR_LAYOUT = "bar_layout"
    const val BAR_LAYOUT_PILLS = "pills"
    const val BAR_LAYOUT_EQUAL = "equal"
    const val DEFAULT_BAR_LAYOUT = BAR_LAYOUT_PILLS
    /** The bar's colors: "classic" or "tiered" (one dark action; modes tonal; keys neutral). */
    const val BAR_COLORS = "bar_colors"
    const val BAR_COLORS_CLASSIC = "classic"
    const val BAR_COLORS_TIERED = "tiered"
    const val DEFAULT_BAR_COLORS = BAR_COLORS_CLASSIC
    const val GBOARD_PACKAGE = "com.google.android.inputmethod.latin"
    const val SILENCE_TIMEOUT = "silence_timeout_s"
    const val CLEANUP = "voice_cleanup"
    const val CLEANUP_MODEL = "voice_cleanup_model"

    const val DEFAULT_CLEANUP = true
    const val DEFAULT_VOICE_LIVE = true
    const val DEFAULT_MILLENNIAL = false
    const val DEFAULT_SILENCE_TIMEOUT = 10 // seconds of quiet before hands-free dictation finishes

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
