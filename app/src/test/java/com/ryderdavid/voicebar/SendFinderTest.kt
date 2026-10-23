package com.ryderdavid.voicebar

import com.ryderdavid.voicebar.SendFinder.Box
import com.ryderdavid.voicebar.SendFinder.Button
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SendFinderTest {
    // a message box across most of a 1080-wide screen, at the bottom
    private val field = Box(140, 2000, 860, 2100)
    private fun button(left: Int, label: String = "", id: String = "", top: Int = 2010) = Button(Box(left, top, left + 80, top + 80), label, id, "android.widget.ImageButton")

    @Test fun onlyButtonsRightOfTheBoxOnItsRowAndNotSayingTheyreSomethingElse() {
        val all = listOf(
            button(30, "Attach"),                 // left of the box
            button(980),                          // the unlabeled arrow
            button(880, "Camera"),                // says it's something else
            button(880, id = "voice_record_btn"), // so does its id
            button(900, top = 200),               // the toolbar up top
            button(960, "Call", top = 120),
        )
        val c = SendFinder.candidates(field, all, slack = 30)
        assertEquals(listOf(980), c.map { it.box.left })
    }

    @Test fun candidatesRunLeftToRightAndStopAtSix() {
        val c = SendFinder.candidates(Box(0, 2000, 100, 2100), (0 until 9).map { button(900 - it * 90) }, slack = 30)
        assertEquals(SendFinder.MAX, c.size)
        assertEquals(c.sortedBy { it.box.cx }, c)
    }

    @Test fun aPickIsRememberedByItsIdOrElseItsPlaceFromTheRight() {
        val a = button(880); val b = button(980)
        assertEquals("right:0", SendFinder.spot(b, listOf(a, b)))
        assertEquals("right:1", SendFinder.spot(a, listOf(a, b)))
        val named = button(980, id = "fab_go")
        assertEquals("id:fab_go", SendFinder.spot(named, listOf(a, named)))
        // found again today, wherever it moved; gone when it isn't there
        assertEquals(named, SendFinder.at("id:fab_go", listOf(button(700), named)))
        assertEquals(b, SendFinder.at("right:0", listOf(a, b)))
        assertNull(SendFinder.at("right:3", listOf(a, b)))
        assertNull(SendFinder.at("id:gone", listOf(a, b)))
    }

    @Test fun theReplyPicksAButtonOrNone() {
        assertEquals(1, SendFinder.parse("{\"send\": 2}", 3))
        assertEquals(0, SendFinder.parse("Sure: {\"send\":1}", 3))
        assertNull(SendFinder.parse("{\"send\": null}", 3))
        assertNull(SendFinder.parse("{\"send\": 4}", 3), "out of range")
        assertNull(SendFinder.parse("button 2", 3), "not JSON")
    }

    private val row = SendFinder.Row("com.example.chat", field, listOf(button(880, id = "emoji_btn2"), button(980)), jpegBase64 = "AAAA")

    @Test fun haikuGetsThePictureAndTheList() {
        val o = JSONObject(SendFinder.claudeBody(row, "claude-haiku-5-5"))
        assertEquals("claude-haiku-5-5", o.getString("model"))
        val content = o.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("image", content.getJSONObject(0).getString("type"))
        assertEquals("AAAA", content.getJSONObject(0).getJSONObject("source").getString("data"))
        val text = content.getJSONObject(1).getString("text")
        assertTrue("1. ImageButton, id \"emoji_btn2\"" in text && "2. ImageButton" in text && "rightmost" in text, text)
    }

    @Test fun lunaGetsTheSameThroughTheResponsesApi() {
        val o = JSONObject(SendFinder.openAIBody(row, "gpt-6-luna"))
        assertEquals("gpt-6-luna", o.getString("model"))
        assertEquals("none", o.getJSONObject("reasoning").getString("effort"))
        val content = o.getJSONArray("input").getJSONObject(0).getJSONArray("content")
        assertEquals("data:image/jpeg;base64,AAAA", content.getJSONObject(0).getString("image_url"))
        assertEquals("input_text", content.getJSONObject(1).getString("type"))
    }

    @Test fun eitherApisReplyIsRead() {
        val claude = """{"content":[{"type":"text","text":"{\"send\": 2}"}],"usage":{"input_tokens":900,"output_tokens":7}}"""
        assertEquals("{\"send\": 2}", SendFinder.replyText(claude, CleanupModels.Provider.ANTHROPIC))
        val openAI = """{"output":[{"type":"message","content":[{"type":"output_text","text":"{\"send\": 1}"}]}],"usage":{"input_tokens":800,"output_tokens":6}}"""
        assertEquals("{\"send\": 1}", SendFinder.replyText(openAI, CleanupModels.Provider.OPENAI))
        assertEquals(900L to 7L, SendFinder.usage(claude))
    }
}
