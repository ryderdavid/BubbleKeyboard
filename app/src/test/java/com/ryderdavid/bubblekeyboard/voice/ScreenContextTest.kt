package com.ryderdavid.bubblekeyboard.voice

import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ScreenContextTest {
    private val chat = ScreenContext.build(listOf(
        ScreenContext.Piece("Message", 2000, 40, field = true),
        ScreenContext.Piece("Siona Okafor", 100, 200),
        ScreenContext.Piece("Are you coming to Camelback Saturday? Bring the GoPro", 400, 40),
        ScreenContext.Piece("Yes! The trailhead at 6:40", 600, 300),
        ScreenContext.Piece("Siona Okafor", 100, 200), // the same header twice
        ScreenContext.Piece("Thanks, see you there", 800, 40),
        ScreenContext.Piece("thanks for planning it SRP", 900, 40),
    ))

    @Test fun readingOrderWithoutRepeats() {
        assertEquals(
            "Siona Okafor\nAre you coming to Camelback Saturday? Bring the GoPro\nYes! The trailhead at 6:40\nThanks, see you there\nthanks for planning it SRP\nMessage",
            chat.text)
    }

    @Test fun namesNotEverydayWords() {
        val names = chat.names
        assertTrue("Siona" in names && "Okafor" in names && "Camelback" in names && "GoPro" in names && "SRP" in names, names.toString())
        for (common in listOf("Are", "Saturday", "Yes", "The", "Thanks", "Message", "Bring")) assertFalse(common in names, "$common in $names")
    }

    @Test fun keytermsPutTheScreenFirstAndDedupe() {
        val k = ScreenContext.keyterms(listOf("Siona", "Camelback"), listOf("camelback", "YODA"))
        assertEquals(listOf("Siona", "Camelback", "YODA"), k)
        assertEquals(50, ScreenContext.keyterms((1..30).map { "Screen$it" }, (1..60).map { "Dict$it" }).size)
    }

    @Test fun longScreensKeepTheHeaderAndTheLatest() {
        val lines = listOf("Chat with Ezra") + (1..400).map { "message number $it here" }
        val s = ScreenContext.build(lines.mapIndexed { i, t -> ScreenContext.Piece(t, i * 10, 0) })
        assertTrue(s.text.length <= ScreenContext.MAX_CHARS + 2)
        assertTrue(s.text.startsWith("Chat with Ezra"))
        assertTrue(s.text.endsWith("message number 400 here"))
        assertTrue(s.recent.length <= ScreenContext.RECENT_CHARS && s.recent.endsWith("400 here"))
    }

    @Test fun cleanupGetsTheScreenMarkedAsContext() {
        val body = JSONObject(ClaudeCleanupRequest.body("", "tell shauna yes", "m", screen = chat.prompt))
        assertTrue(body.getString("system").contains("<screen> is the text that was on screen"))
        val content = body.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(content.startsWith("<screen>\nSiona Okafor") && content.endsWith("<transcript>\ntell shauna yes\n</transcript>"))
        // nothing on screen: the request is as before
        val plain = JSONObject(ClaudeCleanupRequest.body("", "hi", "m"))
        assertFalse(plain.getString("system").contains("<screen>"))
    }

    @Test fun firstChunkCarriesTheContext() {
        val first = JSONObject(ElevenLabsProtocol.audioChunk(ByteArray(4), commit = false, previousText = "Siona: see you"))
        assertEquals("Siona: see you", first.getString("previous_text"))
        assertFalse(JSONObject(ElevenLabsProtocol.audioChunk(ByteArray(4), commit = false)).has("previous_text"))
    }
}
