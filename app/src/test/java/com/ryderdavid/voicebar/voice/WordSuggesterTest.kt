package com.ryderdavid.voicebar.voice

import com.ryderdavid.voicebar.KeyFixes
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class WordSuggesterTest {
    private val words = listOf("the", "to", "and", "that", "this", "they", "there", "then", "thanks", "I", "I'm", "John", "running", "run")

    @Test fun commonWordsFinishThePrefixMostCommonFirst() {
        val s = WordSuggester(words)
        assertEquals(listOf("the", "that", "this"), s.suggest("th"))
        assertEquals(emptyList(), s.suggest(""))
        assertEquals(listOf("running"), s.suggest("run"), "the word itself isn't offered back")
    }

    @Test fun theCaseYouStartedInCarriesOver() {
        val s = WordSuggester(words)
        assertEquals(listOf("The", "That", "This"), s.suggest("Th"))
        assertEquals(listOf("THE", "THAT", "THIS"), s.suggest("TH"))
        assertEquals(listOf("I'm"), s.suggest("i"), "a word's own capitals stay")
        assertEquals(listOf("John"), s.suggest("jo"))
    }

    @Test fun yourOwnWordsComeFirst() {
        val s = WordSuggester(words, extra = { listOf("Thalia") })
        s.learn("thanks"); s.learn("thanks"); s.learn("thermos")
        assertEquals(listOf("thanks", "thermos", "Thalia"), s.suggest("th"))
        val again = WordSuggester(words).apply { load(s.save()) }
        assertEquals("thanks", again.suggest("th").first(), "what's learned is saved")
    }

    @Test fun theWordAtTheCursor() {
        assertEquals("runn", WordSuggester.currentWord("I'm runn"))
        assertEquals("don't", WordSuggester.currentWord("I don't"))
        assertEquals("", WordSuggester.currentWord("Done. "))
        assertEquals("", WordSuggester.currentWord(null))
    }

    @Test fun missingApostrophesAndALoneIAreFixed() {
        assertEquals("I", KeyFixes.fix("i"))
        assertEquals("don't", KeyFixes.fix("dont"))
        assertEquals("Don't", KeyFixes.fix("Dont"))
        assertEquals("I'M", KeyFixes.fix("IM"))
        assertNull(KeyFixes.fix("ill"), "a word on its own")
        assertNull(KeyFixes.fix("I"))
        assertTrue(KeyFixes.fix("wont") == null && KeyFixes.fix("lets") == null && KeyFixes.fix("were") == null)
    }
}
