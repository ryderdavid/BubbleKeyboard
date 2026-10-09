package com.ryderdavid.bubblekeyboard.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An editor with a cursor and an optional draft (composing) region, like a TextView's. */
private class FakeField(initial: String = "") : LiveField {
    val text = StringBuilder(initial)
    var cursor = initial.length
    private var compStart = -1
    private var compEnd = -1

    val draft get() = if (compStart >= 0) text.substring(compStart, compEnd) else null

    override fun setComposing(text: String) {
        if (compStart < 0) { compStart = cursor; compEnd = cursor }
        this.text.replace(compStart, compEnd, text)
        compEnd = compStart + text.length
        cursor = compEnd
    }
    override fun finishComposing() { compStart = -1; compEnd = -1 }
    override fun commit(text: String) {
        if (compStart >= 0) { this.text.replace(compStart, compEnd, text); cursor = compStart + text.length; finishComposing() }
        else { this.text.insert(cursor, text); cursor += text.length }
    }
    override fun textBefore(n: Int): CharSequence = text.substring(maxOf(0, cursor - n), cursor)
    override fun deleteBefore(n: Int) { val s = maxOf(0, cursor - n); text.delete(s, cursor); cursor = s }

    /** What onUpdateSelection reports: (newSelStart, newSelEnd, candidatesStart, candidatesEnd). */
    fun report() = listOf(cursor, cursor, compStart, compEnd)
    fun moveCursor(to: Int) { cursor = to }
    /** The app ends the draft itself (the user tapped elsewhere, say). */
    fun appFinishesDraft() = finishComposing()
    fun typeByUser(s: String) { finishComposing(); text.insert(cursor, s); cursor += s.length }
    override fun toString() = text.toString()
}

class LiveTyperTest {
    private val field = FakeField("Hi there")
    private var lostCalls = 0
    private var stillListening = true
    private var lostWords = -1
    private val typer = LiveTyper({ field }, onLost = { n -> lostCalls++; lostWords = n; stillListening })

    private fun report() = field.report().let { (a, b, c, d) -> typer.onSelection(a, b, c, d) }

    @Test fun wordsAppearAsADraftAndThePolishReplacesThem() {
        typer.begin()
        typer.live("um so"); report()
        assertEquals("Hi there um so", field.toString())
        typer.live("um so we should meet tuesday no wednesday"); report()
        assertEquals(" um so we should meet tuesday no wednesday", field.draft)
        typer.result("So we should meet Wednesday.")
        assertEquals("Hi there So we should meet Wednesday.", field.toString())
        assertNull(field.draft)
        assertEquals(field.text.length, field.cursor)
    }

    @Test fun noLeadingSpaceAfterWhitespaceOrAtTheStart() {
        val empty = FakeField("")
        val t = LiveTyper({ empty })
        t.begin(); t.live("Hello"); t.result("Hello.")
        assertEquals("Hello.", empty.toString())
        val spaced = FakeField("Note: ")
        val t2 = LiveTyper({ spaced })
        t2.begin(); t2.live("buy milk"); t2.result("Buy milk.")
        assertEquals("Note: Buy milk.", spaced.toString())
    }

    @Test fun cancelRemovesTheDraft() {
        typer.begin()
        typer.live("never mind this"); report()
        typer.cancel()
        assertEquals("Hi there", field.toString())
        assertNull(field.draft)
    }

    @Test fun endKeepsWhatWasTyped() {
        typer.begin()
        typer.live("network dropped here"); report()
        typer.end()
        assertEquals("Hi there network dropped here", field.toString())
        assertNull(field.draft)
    }

    @Test fun aKeyWhileListeningStartsANewDraftAfterIt() {
        typer.begin()
        typer.live("first part"); report()
        typer.interrupt(newSegment = true)
        field.commit(",") // the key the user pressed
        typer.live("second part"); report()
        typer.result("Second part.")
        assertEquals("Hi there first part, Second part.", field.toString())
    }

    @Test fun polishStillLandsIfTheAppEndedTheDraftButNothingElseChanged() {
        stillListening = false
        typer.begin()
        typer.live("raw words here"); report()
        field.appFinishesDraft(); report()
        assertEquals(1, lostCalls)
        typer.live("raw words here") // the dictation's final raw text: ignored, it's already there
        assertEquals("Hi there raw words here", field.toString())
        typer.result("Raw words here.")
        assertEquals("Hi there Raw words here.", field.toString())
    }

    @Test fun polishLeavesTheUsersEditsAlone() {
        stillListening = false
        typer.begin()
        typer.live("raw words"); report()
        field.appFinishesDraft(); report()
        field.typeByUser(" and more")
        typer.result("Raw words.")
        assertEquals("Hi there raw words and more", field.toString())
    }

    @Test fun aKeyWhilePolishingKeepsTheRawWords() {
        typer.begin()
        typer.live("raw words"); report()
        typer.interrupt(newSegment = false) // finishing: no new segment
        field.commit("!")
        typer.result("Raw words.")
        assertEquals("Hi there raw words!", field.toString())
    }

    @Test fun losingTheDraftWhileListeningStartsANewOne() {
        stillListening = true
        typer.begin()
        typer.live("first"); report()
        field.appFinishesDraft(); report()
        assertEquals(1, lostCalls)
        assertEquals(1, lostWords)
        typer.live("second"); report() // the dictation moved its boundary: only new words come
        typer.result("Second.")
        assertEquals("Hi there first Second.", field.toString())
    }

    @Test fun aStaleReportBeforeTheDraftIsShownDoesntCount() {
        typer.begin()
        typer.live("hello")
        typer.onSelection(8, 8, -1, -1) // from before our draft reached the app
        assertTrue(typer.active)
        assertEquals(0, lostCalls)
        report()
        typer.result("Hello.")
        assertEquals("Hi there Hello.", field.toString())
    }

    @Test fun resultWithNoLiveTextJustTypes() {
        typer.begin()
        typer.result("Quick one.")
        assertEquals("Hi there Quick one.", field.toString())
        assertFalse(typer.active)
    }

    @Test fun aStaleReportForAnOlderDraftCantFakeAnEnd() {
        typer.begin()
        typer.live("first part"); val staleFirst = field.report()
        typer.interrupt(newSegment = true)
        val staleFinish = field.report()
        typer.live("second") // a new draft, not yet reported
        typer.onSelection(staleFirst[0], staleFirst[1], staleFirst[2], staleFirst[3]) // late report for "first part"
        typer.onSelection(staleFinish[0], staleFinish[1], staleFinish[2], staleFinish[3]) // late -1 from our own finish
        assertTrue(typer.active)
        assertEquals(0, lostCalls)
        report()
        typer.result("Second.")
        assertEquals("Hi there first part Second.", field.toString())
    }

    @Test fun movingTheCursorAwayEndsTheDraft() {
        stillListening = true
        typer.begin()
        typer.live("some words"); report()
        field.moveCursor(2); report() // the user tapped near the start; the draft is still composing
        assertEquals(1, lostCalls)
        assertNull(field.draft)
        assertFalse(typer.active)
    }

    @Test fun lateWordsAfterAQuickKeyAreStillTyped() {
        typer.begin()
        typer.live("raw words"); report()
        typer.interrupt(newSegment = false) // released, then tapped space at once
        field.commit(" ")
        typer.live("raw words and the rest") // the final words arrive
        typer.result("Raw words and the rest.")
        assertEquals("Hi there raw words and the rest", field.toString())
    }
}
