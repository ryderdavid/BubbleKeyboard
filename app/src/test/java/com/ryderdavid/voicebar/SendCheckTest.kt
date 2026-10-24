package com.ryderdavid.voicebar

import com.ryderdavid.voicebar.voice.ClaudeCleanupRequest
import com.ryderdavid.voicebar.voice.DictationController
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SendCheckTest {
    private val said = "The ability to do that and make a spectacular point on your feet is, honestly, what I want all of " +
        "the new generation of our politicians to be able to do. Okay"

    @Test fun droppingAStrayWordAndFixingCapitalsIsAllowed() {
        assertTrue(SendCheck.acceptable(said, said.removeSuffix(" Okay")))
        assertTrue(SendCheck.acceptable("give it one extra Broad, does this make sense", "give it one extra broad does this make sense?"))
        assertTrue(SendCheck.acceptable("I said. Hello there", "I said hello there"))
    }

    @Test fun rewordingAddingOrDroppingTooMuchIsNot() {
        assertFalse(SendCheck.acceptable("Let's meet at six", "Let's meet at seven"), "a word changed")
        assertFalse(SendCheck.acceptable("Let's meet at six", "Let's all meet at six"), "a word added")
        assertFalse(SendCheck.acceptable("Let's meet at six", "six at meet Let's"), "reordered")
        assertFalse(SendCheck.acceptable("one two three four five six seven", "one two"), "too much dropped")
        assertFalse(SendCheck.acceptable("hello there", ""), "nothing left")
    }

    @Test fun theReplysMessageIsRead() {
        assertEquals("Hi there.", SendCheck.parse("<text>Hi there.</text>"))
        assertEquals("Line one\nline two", SendCheck.parse("Sure!\n<text>\nLine one\nline two\n</text>"))
        assertNull(SendCheck.parse("Hi there."))
    }

    @Test fun bothModelsGetTheMessage() {
        val claude = JSONObject(SendCheck.claudeBody("hello there", "claude-haiku-5-5"))
        assertTrue("<message>\nhello there\n</message>" in claude.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertTrue("trailing" !in claude.getString("system") || "okay" in claude.getString("system"))
        val luna = JSONObject(SendCheck.openAIBody("hello there", "gpt-6-luna"))
        assertEquals("none", luna.getJSONObject("reasoning").getString("effort"))
        assertTrue("hello there" in luna.getString("input"))
    }

    @Test fun whatsSaidAfterAPauseLosesTheRecognizersCapital() {
        assertEquals("broad, does this make sense", DictationController.joined("give it one extra", "Broad, does this make sense"))
        assertEquals("Broad start", DictationController.joined("", "Broad start"), "nothing before: left alone")
        assertEquals("I think so", DictationController.joined("well", "I think so"))
        assertEquals("I'm sure", DictationController.joined("well", "I'm sure"))
        assertEquals("NASA said so", DictationController.joined("well", "NASA said so"))
        assertEquals("  message here", DictationController.joined("well", "  Message here"))
    }

    @Test fun aCleanupAtAPauseKnowsTheSpeakerWillGoOn() {
        assertTrue(ClaudeCleanupRequest.PAUSED in ClaudeCleanupRequest.user("", "so I was thinking", paused = true))
        assertFalse(ClaudeCleanupRequest.PAUSED in ClaudeCleanupRequest.user("", "so I was thinking"))
        assertTrue("those capitals and periods mean" in ClaudeCleanupRequest.SYSTEM)
    }

    @Test fun aFixSendsTheWholeBoxAndTheInstructionsApart() {
        val o = JSONObject(DictationFix.claudeBody("Give it one extra Broad look. (typed by hand)", "broad should be lowercase", "claude-haiku-5-5"))
        val user = o.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue("<text>\nGive it one extra Broad look. (typed by hand)\n</text>" in user)
        assertTrue("<fix>\nbroad should be lowercase\n</fix>" in user)
        assertTrue("never words for the text" in o.getString("system"))
        assertTrue("edited by hand" in o.getString("system"), "it's told the box may hold more than a dictation")
        val luna = JSONObject(DictationFix.openAIBody("A.", "", "gpt-6-luna"))
        assertTrue("<fix>\n\n</fix>" in luna.getString("input"), "nothing said: an empty fix, a closer look")
        assertEquals("Give it one extra broad look.", DictationFix.parse("<text>Give it one extra broad look.</text>"))
        assertNull(DictationFix.parse("It should be lowercase."))
    }
}
