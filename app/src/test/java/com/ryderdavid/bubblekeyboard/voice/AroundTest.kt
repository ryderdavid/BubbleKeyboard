package com.ryderdavid.bubblekeyboard.voice

import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AroundTest {
    @Test fun capturedOnlyWithText() {
        assertNull(Around.of("", ""))
        assertNull(Around.of(null, "  "))
        val a = Around.of("Meet me at the ", " at nine.")!!
        assertTrue(a.inside)
        assertEquals("x".repeat(Around.BEFORE_CHARS), Around.of("y" + "x".repeat(Around.BEFORE_CHARS), "")!!.before)
    }

    @Test fun theCleanupSeesBothSides() {
        val a = Around("Can you grab the ", " from the garage before dinner?")
        val j = JSONObject(ClaudeCleanupRequest.body("", "ladder", "m", around = a))
        val system = j.getString("system")
        val user = j.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(system.contains("<before> and <after>"))
        assertTrue(user.contains("<before>\nCan you grab the \n</before>"))
        assertTrue(user.contains("<after>\n from the garage before dinner?\n</after>"))
        assertTrue(user.indexOf("<after>") < user.indexOf("<transcript>"))
        // without text around the cursor, none of it
        val plain = JSONObject(ClaudeCleanupRequest.body("", "ladder", "m"))
        assertTrue(!plain.getString("system").contains("<before>"))
    }

    @Test fun anEchoedFieldIsTakenBackOff() {
        val a = Around("We should leave by noon. Can you grab the ", " from the garage before dinner?")
        assertEquals("ladder", ClaudeCleanupRequest.trimAround("ladder from the garage before dinner?", a))
        assertEquals("ladder", ClaudeCleanupRequest.trimAround("Can you grab the ladder", a))
        assertEquals("ladder", ClaudeCleanupRequest.trimAround("ladder", a))
        assertEquals("Fine.", ClaudeCleanupRequest.trimAround("Fine.", null))
        assertEquals("ladder", ClaudeCleanupRequest.parse("""{"content":[{"type":"text","text":"<transcript>ladder</transcript>"}]}"""))
    }
}
