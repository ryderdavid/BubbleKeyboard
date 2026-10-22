package com.ryderdavid.voicebar.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A field as text with a cursor (no selection), for taking leftovers out around a dictation. */
private class SeamField(text: String, cursorAt: String) : LiveField {
    val text = StringBuilder(text)
    var cursor = text.indexOf(cursorAt).also { require(it >= 0) } + cursorAt.length
    override fun setComposing(text: String) = Unit
    override fun finishComposing() = Unit
    override fun commit(text: String) { this.text.insert(cursor, text); cursor += text.length }
    override fun textBefore(n: Int): CharSequence = text.substring(maxOf(0, cursor - n), cursor)
    override fun deleteBefore(n: Int) { val s = maxOf(0, cursor - n); text.delete(s, cursor); cursor = s }
    override fun textAfter(n: Int): CharSequence = text.substring(cursor, minOf(text.length, cursor + n))
    override fun deleteAfter(n: Int) { text.delete(cursor, minOf(text.length, cursor + n)) }
    override fun toString() = text.toString()
}

class InfillTest {
    private fun fit(text: String, before: String, after: String, seam: Infill.Seam = Infill.Seam()) =
        Infill.fit(text, Around(before, after), seam)

    @Test fun midSentenceAnOrdinaryWordLosesItsCapital() {
        assertEquals("the ladder from", fit("The ladder from", "Can you grab ", " the garage?").text)
        assertEquals("but not today", fit("But not today", "We can go, ", " if it rains.").text)
        assertEquals("don't forget", fit("Don't forget", "And ", " the milk.").text)
    }

    @Test fun namesAndIKeepTheirCapitals() {
        assertEquals("Siona said yes", fit("Siona said yes", "Then ", " and left.").text)
        assertEquals("I think", fit("I think", "Well, ", " so.").text)
        assertEquals("I'll bring it", fit("I'll bring it", "Sure, ", " tomorrow.").text)
        assertEquals("Will is coming", fit("Will is coming", "Good news: ", ".").text)
    }

    @Test fun atTheStartOfASentenceItIsCapitalized() {
        assertEquals("Then we left.", fit("then we left.", "It rained. ", "").text)
        assertEquals("The end", fit("The end", "", " of it").text, "start of the field: left alone")
        // an all-lowercase field keeps its style
        assertEquals("then we left", fit("then we left", "it rained. ", "").text)
    }

    @Test fun noClosingPeriodWhenTheSentenceGoesOn() {
        assertEquals("on Wednesday", fit("on Wednesday.", "Let's meet ", " at nine.").text)
        assertEquals("on Wednesday", fit("on Wednesday.", "Let's meet ", ", if that works.").text)
        assertEquals("We met.", fit("We met.", "Hi. ", " Then we ate.").text, "a new sentence after: the period stays")
        assertEquals("well...", fit("well...", "It went ", " anyway.").text, "an ellipsis stays")
    }

    @Test fun aWordSaidAgainAcrossTheSeamGoesOnce() {
        assertEquals("Wednesday", fit("on Wednesday", "Let's meet on ", " at nine.").text)
        assertEquals("on Wednesday", fit("on Wednesday at", "Let's meet ", " at nine.").text)
        assertEquals("had enough", fit("had enough", "She had ", ".").text, "\"had had\" can be meant")
    }

    @Test fun leftoversTheCleanupNamesAreTakenOnlyWhenTheyReallyAreThere() {
        val f = fit("at the park", "Let's meet on ", " tomorrow.", Infill.Seam(removeBefore = "on"))
        assertEquals("on", f.removeBefore)
        assertEquals("at the park", f.text)
        assertEquals("", fit("at the park", "Let's meet ", " tomorrow.", Infill.Seam(removeBefore = "on")).removeBefore, "not at the seam")
        assertEquals("", fit("x", "Let's meet upon ", "", Infill.Seam(removeBefore = "on")).removeBefore, "not a whole word")
        assertEquals("", fit("x", "a b c d e ", "", Infill.Seam(removeBefore = "b c d e")).removeBefore, "too many words")
        assertEquals("Tuesday", fit("Wednesday", "Meet on ", " Tuesday at nine.", Infill.Seam(removeAfter = "Tuesday")).removeAfter)
    }

    @Test fun theCapitalIsJudgedAfterTheLeftoverGoes() {
        // "Done. Meet on" with "on" a leftover: the dictation now starts the second sentence's predicate, not a new one
        val f = fit("At the park", "Done. Meet on ", " later.", Infill.Seam(removeBefore = "on"))
        assertEquals("at the park", f.text)
    }

    @Test fun leftoverTagsAreReadFromTheReplyAndNeverTyped() {
        val reply = "<text>at the park</text>\n<remove_before>on</remove_before>"
        assertEquals(Infill.Seam(removeBefore = "on"), Infill.seam(reply))
        assertEquals("at the park", ClaudeCleanupRequest.parseText(reply))
        assertEquals(Infill.Seam(), Infill.seam("<text>fine</text>"))
    }

    @Test fun leftoversComeOutOfTheFieldAroundTheDictation() {
        val field = SeamField("Let's meet on at the park tomorrow.", "Let's meet on at the park")
        assertTrue(Infill.removeLeftovers(field, "at the park", Infill.Fit("at the park", removeBefore = "on")))
        assertEquals("Let's meet at the park tomorrow.", field.toString())
        assertTrue(field.toString().substring(0, field.cursor).endsWith("at the park"), "the cursor stays after the dictation")

        val after = SeamField("Meet on Wednesday Tuesday at nine.", "Meet on Wednesday")
        assertTrue(Infill.removeLeftovers(after, "Wednesday", Infill.Fit("Wednesday", removeAfter = "Tuesday")))
        assertEquals("Meet on Wednesday at nine.", after.toString())
    }

    @Test fun aFieldThatChangedMeanwhileIsLeftAlone() {
        val field = SeamField("Let's meet at the park tomorrow.", "Let's meet at the park")
        assertFalse(Infill.removeLeftovers(field, "at the park", Infill.Fit("at the park", removeBefore = "on")))
        assertEquals("Let's meet at the park tomorrow.", field.toString())
    }

    @Test fun theCleanupIsToldWhatTheSelectionWas() {
        val user = ClaudeCleanupRequest.user("", "Wednesday", around = Around("Meet on ", " at nine.", replacing = "Tuesday"))
        assertTrue(user.contains("<replacing>\nTuesday\n</replacing>"))
        assertFalse(ClaudeCleanupRequest.user("", "x", around = Around("a ", " b")).contains("<replacing>"))
        assertTrue(ClaudeCleanupRequest.system(around = Around("a", "b")).contains("<remove_before>"))
    }
}
