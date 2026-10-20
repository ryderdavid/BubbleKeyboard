package com.ryderdavid.voicebar.voice

import android.text.InputType
import android.view.inputmethod.EditorInfo
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatAppsTest {
    private val text = InputType.TYPE_CLASS_TEXT
    private val multi = text or InputType.TYPE_TEXT_FLAG_MULTI_LINE

    @Test fun messagingAppsAreChats() {
        assertTrue(ChatApps.isChat("com.google.android.apps.messaging", multi, 0))
        assertTrue(ChatApps.isChat("com.Slack", multi, 0))
        assertTrue(ChatApps.isChat("org.thoughtcrime.securesms", multi, EditorInfo.IME_ACTION_NONE))
    }

    @Test fun messageBoxesAnywhereAreChats() {
        assertTrue(ChatApps.isChat("com.example.app", text or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE, 0))
        assertTrue(ChatApps.isChat("com.example.app", multi, EditorInfo.IME_ACTION_SEND))
    }

    @Test fun everythingElseIsNot() {
        assertFalse(ChatApps.isChat("com.google.android.gm", multi, 0)) // Gmail body
        assertFalse(ChatApps.isChat("com.google.android.gm", text or InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT, 0))
        assertFalse(ChatApps.isChat("com.google.android.keep", multi, 0))
        assertFalse(ChatApps.isChat("com.example.app", multi, EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION))
        assertFalse(ChatApps.isChat("com.example.app", text, EditorInfo.IME_ACTION_SEARCH))
        // a chat app's search box or link field
        assertFalse(ChatApps.isChat("com.whatsapp", text or InputType.TYPE_TEXT_VARIATION_URI, 0))
        assertFalse(ChatApps.isChat("com.whatsapp", InputType.TYPE_CLASS_PHONE, 0))
    }
}
