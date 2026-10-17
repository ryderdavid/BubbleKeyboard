package com.ryderdavid.bubblekeyboard

import android.content.Context
import android.content.SharedPreferences

/** Settings keys and defaults, shared by the keyboard and its settings screen. */
object Prefs {
    private const val FILE = "bubble_keyboard"

    const val VOICE_LIVE = "voice_live"
    /** Start dictating as soon as a text box opens the keyboard. */
    const val AUTO_LISTEN = "auto_listen"
    const val DEFAULT_AUTO_LISTEN = false
    /** The old on/off switch for millennial mode; read once to carry it over to [MILLENNIAL_WHERE]. */
    const val MILLENNIAL = "millennial_mode"
    /** Where millennial mode applies: "off", "chats" (messaging apps and message boxes) or "everywhere". */
    const val MILLENNIAL_WHERE = "millennial_where"
    const val MILLENNIAL_OFF = "off"
    const val MILLENNIAL_CHATS = "chats"
    const val MILLENNIAL_EVERYWHERE = "everywhere"
    /** "Voice bar, send" and friends: spoken commands while dictating. */
    const val VOICE_COMMANDS = "voice_commands"
    const val DEFAULT_VOICE_COMMANDS = true
    /** Learn names and terms from the user's hand corrections to dictated text. */
    const val LEARN = "learn_corrections"
    const val DEFAULT_LEARN = true
    /** Send the personal dictionary to the speech model as keyterms (ElevenLabs charges a little extra). */
    const val DICT_KEYTERMS = "dictionary_keyterms"
    const val DEFAULT_DICT_KEYTERMS = true
    /** Speech recognition: ElevenLabs Scribe (cloud, the default) or Parakeet on the phone. */
    const val SPEECH_ENGINE = "speech_engine"
    const val ENGINE_ELEVENLABS = "elevenlabs"
    const val ENGINE_PARAKEET = "parakeet"
    /** First-run tips on the bar ("Skip tips" turns them off; Settings › How to use it turns them back on). */
    const val VOICEPRINT = "voiceprint_on"
    const val VOICEPRINT_STRENGTH = "voiceprint_strength"

    const val TIPS = "tips_on"
    const val DEFAULT_TIPS = true
    /** Tips already seen or retired by using the feature (Tips.Id names). */
    const val TIPS_DONE = "tips_done"
    /** Dictations so far, up to 100: the "voice bar" tip waits for a few. */
    const val TIPS_DICTATIONS = "tips_dictations"
    /** The first-launch setup has been finished (or the app was already set up when it arrived). */
    const val SETUP_DONE = "setup_done"
    /** Hands-free mode ("voice bar, hands free"): control the phone by voice. */
    const val HANDS_FREE = "hands_free"
    const val DEFAULT_HANDS_FREE = true
    /** Listen for "voice bar, hands free" everywhere, not only while a keyboard is up. */
    const val HANDS_FREE_ANYWHERE = "hands_free_anywhere"
    const val DEFAULT_HANDS_FREE_ANYWHERE = false
    /** The Claude model that plans hands-free steps (default Haiku). */
    const val HANDS_FREE_MODEL = "hands_free_model"
    /** Read the screen's text when dictation starts, as context for recognition and cleanup. */
    const val SCREEN_CONTEXT = "screen_context"
    const val DEFAULT_SCREEN_CONTEXT = true
    /** Listen on the phone for "voice bar, listen" while a keyboard is up and idle (the bar, or over Gboard). */
    const val WAKE_WORD = "wake_word"
    const val DEFAULT_WAKE_WORD = true
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

    /** Where millennial mode applies; the old switch's "on" becomes chats only. */
    fun millennialWhere(context: Context): String {
        val p = get(context)
        p.getString(MILLENNIAL_WHERE, null)?.let { return it }
        return if (p.getBoolean(MILLENNIAL, DEFAULT_MILLENNIAL)) MILLENNIAL_CHATS else MILLENNIAL_OFF
    }

    fun get(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
