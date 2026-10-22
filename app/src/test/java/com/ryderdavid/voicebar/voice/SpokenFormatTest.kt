package com.ryderdavid.voicebar.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class SpokenFormatTest {
    private fun say(heard: String) = SpokenFormat.render(SpokenFormat.mark(heard))

    @Test fun theExampleReadsRight() {
        assertEquals(
            "I just watched The Lord of the Rings and she said \"it's long\" (fair)",
            say("I just watched title case the lord of the rings end title case and she said quote it's long end quote open paren fair close paren"),
        )
    }

    @Test fun theRecognizersPunctuationAroundThePhrasesIsTidied() {
        assertEquals("She said, \"it's long.\" Then left.", say("She said, quote, it's long. End quote. Then left."))
        assertEquals("We read \"Moby Dick\", then slept.", say("We read quote, title case, moby dick, end title case, end quote, then slept."))
        assertEquals("Meet at noon (or one).", say("Meet at noon, open paren, or one, close paren."))
    }

    @Test fun quotesAndParenthesesOnlyHappenInPairs() {
        assertEquals("Can you send me a quote for the job?", say("Can you send me a quote for the job?"))
        assertEquals("That's the end quote I meant", say("That's the end quote I meant"))
        assertEquals("Put it in parentheses please", say("Put it in parentheses please"))
        assertEquals("a so-called quote unquote expert", say("a so-called quote unquote expert"), "nothing between: just the words")
    }

    @Test fun literalSaysTheWordItself() {
        assertEquals("the quote was \"too high\"", say("the literal quote was quote too high end quote"))
    }

    @Test fun titleCaseRunsToTheEndWhenNotEnded() {
        assertEquals("Reading A Tale of Two Cities", say("Reading title case a tale of two cities"))
    }

    @Test fun titleCaseKeepsSmallWordsLowAndOwnCapitals() {
        assertEquals("The Wind in the Willows", SpokenFormat.titleCase("the wind in the willows"))
        assertEquals("What It Is: A Guide to Getting Up-to-Date", SpokenFormat.titleCase("what it is: a guide to getting up-to-date"))
        assertEquals("My iPhone and NASA", SpokenFormat.titleCase("my iPhone and NASA"))
        assertEquals("Don't Look Back in Anger", SpokenFormat.titleCase("don't look back in anger"))
        assertEquals("Where Are You From", SpokenFormat.titleCase("where are you from"), "last word capitalized")
    }

    @Test fun whileStillDictatingOpenQuotesShowOpen() {
        val tagged = SpokenFormat.mark("she said quote it's long")
        assertEquals("she said \"it's long", SpokenFormat.render(tagged, final = false))
        assertEquals("she said quote it's long", SpokenFormat.render(tagged, final = true))
        assertEquals(setOf(SpokenFormat.Kind.QUOTE), SpokenFormat.open(tagged))
    }

    @Test fun aQuoteCanSpanAPause() {
        // cleaned at a pause (tags kept), then the rest of what was said
        val atPause = SpokenFormat.mark("she said quote it's")
        val later = SpokenFormat.mark("long end quote and left")
        assertEquals("she said \"it's long\" and left", SpokenFormat.render("$atPause $later"))
    }

    @Test fun plainTextPassesUntouched() {
        assertEquals("Nothing to format here.", say("Nothing to format here."))
        assertEquals("", SpokenFormat.mark(""))
    }
}
