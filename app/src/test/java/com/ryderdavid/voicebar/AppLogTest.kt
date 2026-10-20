package com.ryderdavid.voicebar

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLogTest {
    @Test fun keepsOnlyTheLastFiveMinutes() {
        AppLog.clear()
        AppLog.log("dictation", "old one")
        val later = System.currentTimeMillis() + AppLog.KEEP_MS + 1000
        AppLog.log("dictation", "new one")
        val text = AppLog.text(later)
        assertFalse(text.contains("old one"))
        AppLog.clear()
        AppLog.log("send", "pressed the app's Send")
        assertTrue(AppLog.text().contains("send: pressed the app's Send"))
        assertTrue(AppLog.excerpt("hello there", 5).startsWith("\"hello…\""))
    }
}
