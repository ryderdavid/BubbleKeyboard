package com.ryderdavid.voicebar.voice

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Whether a text field is a chat message box, for settings that apply only in chats (millennial
 * mode). A field counts when its app is a known messaging app, or when it says so itself: a field
 * marked as a short message, or one whose Enter key sends. Email addresses, subjects, web addresses
 * and passwords never count, even inside a chat app.
 */
object ChatApps {
    /** Messaging apps, by package name. */
    val PACKAGES = setOf(
        "com.google.android.apps.messaging",   // Google Messages
        "com.samsung.android.messaging",       // Samsung Messages
        "com.google.android.apps.dynamite",    // Google Chat
        "com.google.android.apps.googlevoice",
        "com.whatsapp", "com.whatsapp.w4b",
        "org.thoughtcrime.securesms",          // Signal
        "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.plus", "org.thunderdog.challegram",
        "com.Slack",
        "com.discord",
        "com.facebook.orca", "com.facebook.mlite", // Messenger
        "com.instagram.android",
        "com.microsoft.teams",
        "com.snapchat.android",
        "com.beeper.android", "com.beeper.chat",
        "im.vector.app",                       // Element
        "ch.threema.app", "ch.threema.app.libre",
        "jp.naver.line.android",
        "com.viber.voip",
        "com.tencent.mm",                      // WeChat
        "com.kakao.talk",
        "com.wire",
        "network.loki.messenger",              // Session
        "com.groupme.android",
        "com.skype.raider",
        "com.zing.zalo",
        "chat.simplex.app",
    )

    /**
     * A search box: its Enter key searches, or it's an address bar or a list filter, or its hint
     * says "Search". A final period there becomes part of what's searched for.
     */
    fun isSearch(info: EditorInfo?): Boolean =
        info != null && isSearch(info.inputType, info.imeOptions, info.hintText)

    fun isSearch(inputType: Int, imeOptions: Int, hint: CharSequence?): Boolean {
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_FILTER -> return true
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> return false
        }
        if ((imeOptions and EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEARCH) return true
        return hint?.contains("search", ignoreCase = true) == true
    }

    fun isChat(info: EditorInfo?): Boolean =
        info != null && isChat(info.packageName, info.inputType, info.imeOptions)

    fun isChat(packageName: String?, inputType: Int, imeOptions: Int): Boolean {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        if (cls != InputType.TYPE_CLASS_TEXT) return false
        when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE -> return true
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT, InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
            InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS, InputType.TYPE_TEXT_VARIATION_FILTER -> return false
        }
        if (packageName != null && packageName in PACKAGES) return true
        val sends = (imeOptions and EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEND &&
            (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0
        return sends
    }
}
