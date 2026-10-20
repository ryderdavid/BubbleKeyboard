package com.ryderdavid.voicebar.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MillennialTest {
    private fun s(t: String) = Millennial.strip(t).first

    @Test fun dropsOnlyTheFinalPeriod() {
        assertEquals("On my way", s("On my way."))
        assertEquals("Got it. See you at 5", s("Got it. See you at 5."))
        assertEquals("Trailing space too", s("Trailing space too. "))
    }

    @Test fun keepsOtherEndings() {
        assertEquals("Are you coming?", s("Are you coming?"))
        assertEquals("Yes!", s("Yes!"))
        assertEquals("Well...", s("Well..."))
        assertEquals("Wait…", s("Wait…"))
        assertEquals("No punctuation", s("No punctuation"))
        assertFalse(Millennial.strip("Yes!").second)
    }

    @Test fun periodInsideClosingQuotesOrBrackets() {
        assertEquals("He said \"fine\"", s("He said \"fine.\""))
        assertEquals("(see the doc)", s("(see the doc.)"))
    }

    @Test fun followUpPutsThePeriodBackOnlyAfterOurOwnText() {
        val tail = Millennial.tail("Got it. See you at 5")
        assertTrue(Millennial.needsPeriodBack(tail, "Got it. See you at 5"))
        assertFalse(Millennial.needsPeriodBack(tail, "Got it. See you at 5 ok")) // the user typed since
        assertFalse(Millennial.needsPeriodBack(null, "anything"))
        assertFalse(Millennial.needsPeriodBack("Done?", "Done?"))
    }
}
