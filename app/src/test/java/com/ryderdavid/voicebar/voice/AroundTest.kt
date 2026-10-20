package com.ryderdavid.voicebar.voice

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

@RunWith(RobolectricTestRunner::class)
class CleanupReplyTest {
    private fun reply(t: String) = JSONObject().put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", t))).toString()

    @Test fun onlyWhatsInsideTheTagIsTyped() {
        assertEquals("and Codex for this same Mac", ClaudeCleanupRequest.parse(reply("The cursor is after ChatGPT, so:\n<text>and Codex for this same Mac</text>")))
        assertEquals("Meet at nine.", ClaudeCleanupRequest.parse(reply("<text>Meet at nine.")))
    }

    @Test fun untaggedThinkingOutLoudIsNotTyped() {
        // the reply that leaked into a message
        assertNull(ClaudeCleanupRequest.parse(reply("Wait, the cursor is after \"Claude Code and ChatGPT\", and the dictation starts with \"Codex\". Corrected version: And then, ask them")))
        assertEquals("So yeah.", ClaudeCleanupRequest.parse(reply("So yeah.")))
    }

    @Test fun aReplyFarLongerThanTheSpeechIsRejected() {
        assertTrue(ClaudeCleanupRequest.plausible("Meet at nine.", "", "meet at nine"))
        assertTrue(!ClaudeCleanupRequest.plausible("x".repeat(400), "", "meet at nine"))
    }

    @Test fun theDictationIncludesTheCleanedPart() {
        val s = JSONObject(ClaudeCleanupRequest.body("Tell Claude Code and ChatGPT", "and codex too", "m", around = Around("Hi team. ", ""))).getString("system")
        assertTrue(s.contains("<before>, then the dictation"))
        assertTrue(s.contains("inside <text></text>"))
    }
}

class CorrectionPromptTest {
    @org.junit.Test fun restatementsAreAppliedNotKept() {
        val sys = ClaudeCleanupRequest.SYSTEM
        org.junit.Assert.assertTrue(sys.contains("\"or sorry\"") && sys.contains("keep only the restated version"))
        org.junit.Assert.assertTrue(sys.contains("even when the replaced part is in <cleaned>"))
        // the caution is about mis-hearings only, never a reason to leave a correction in
        org.junit.Assert.assertFalse(sys.contains("When unsure, keep the speaker's words as spoken"))
    }
}
