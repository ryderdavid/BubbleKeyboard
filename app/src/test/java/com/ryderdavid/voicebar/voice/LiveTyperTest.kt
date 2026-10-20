package com.ryderdavid.voicebar.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An editor with a cursor and an optional draft (composing) region, like a TextView's. */
private class FakeField(initial: String = "") : LiveField {
    val text = StringBuilder(initial)
    var cursor = initial.length
    /** The selection's other end (== cursor when nothing is selected). */
    var anchor = initial.length
    private var compStart = -1
    private var compEnd = -1

    val draft get() = if (compStart >= 0) text.substring(compStart, compEnd) else null

    override fun setComposing(text: String) {
        if (compStart < 0) { compStart = cursor; compEnd = cursor }
        this.text.replace(compStart, compEnd, text)
        compEnd = compStart + text.length
        cursor = compEnd
        anchor = cursor
    }
    override fun finishComposing() { compStart = -1; compEnd = -1 }
    override fun commit(text: String) {
        if (compStart >= 0) { this.text.replace(compStart, compEnd, text); cursor = compStart + text.length; finishComposing() }
        else { this.text.insert(cursor, text); cursor += text.length }
        anchor = cursor
    }
    override fun textBefore(n: Int): CharSequence = text.substring(maxOf(0, cursor - n), cursor)
    override fun deleteBefore(n: Int) { val s = maxOf(0, cursor - n); text.delete(s, cursor); cursor = s; anchor = s }
    override fun setSelection(start: Int, end: Int) { anchor = start; cursor = end }

    /** What onUpdateSelection reports: (newSelStart, newSelEnd, candidatesStart, candidatesEnd). */
    fun report() = listOf(minOf(anchor, cursor), maxOf(anchor, cursor), compStart, compEnd)
    fun moveCursor(to: Int) { cursor = to; anchor = to }
    fun select(a: Int, b: Int) { anchor = a; cursor = b }
    val selection get() = minOf(anchor, cursor) to maxOf(anchor, cursor)
    /** The app ends the draft itself (the user tapped elsewhere, say). */
    fun appFinishesDraft() = finishComposing()
    fun typeByUser(s: String) { finishComposing(); text.insert(cursor, s); cursor += s.length; anchor = cursor }
    override fun toString() = text.toString()
}

class LiveTyperTest {
    private val field = FakeField("Hi there")
    private var lostCalls = 0
    private val typer = LiveTyper({ field }, onUserEdit = { lostCalls++ })

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

    @Test fun theAppEndingTheDraftStopsTheDictationAndThePolishStillLands() {
        typer.begin()
        typer.live("first words"); report()
        field.appFinishesDraft(); report()
        assertEquals(1, lostCalls) // the dictation is told to stop
        typer.live("first words and more") // late words don't go anywhere: the user has the field
        assertEquals("Hi there first words", field.toString())
        typer.result("First words and more.")
        assertEquals("Hi there First words and more.", field.toString())
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

    @Test fun tappingElsewhereStopsDictationAndKeepsTheCursorThere() {
        typer.begin()
        typer.live("some words"); report()
        field.moveCursor(2); report() // the user tapped near the start; the draft is still a draft
        assertEquals(1, lostCalls)
        typer.live("some words keep coming") // ignored: never yank the cursor back
        assertEquals(2, field.cursor)
        typer.result("Some words keep coming.")
        assertEquals("Hi there Some words keep coming.", field.toString())
        assertEquals(2 to 2, field.selection) // the cursor stayed where the user put it
        assertNull(field.draft)
    }

    @Test fun selectingTextMidDictationKeepsTheSelectionThroughThePolish() {
        val f = FakeField("Hello world. ")
        var edits = 0
        val t = LiveTyper({ f }, onUserEdit = { edits++ })
        t.begin()
        t.live("um so yes"); f.report().let { (a, b, c, d) -> t.onSelection(a, b, c, d) }
        f.select(0, 5); f.report().let { (a, b, c, d) -> t.onSelection(a, b, c, d) } // "Hello"
        assertEquals(1, edits)
        t.result("So yes.")
        assertEquals("Hello world. So yes.", f.toString())
        assertEquals(0 to 5, f.selection)
    }

    @Test fun aSelectionAfterTheDraftShiftsWithThePolish() {
        val f = FakeField("A B")
        f.moveCursor(1) // between "A" and " B"
        val t = LiveTyper({ f })
        t.begin()
        t.live("um hello there"); f.report().let { (a, b, c, d) -> t.onSelection(a, b, c, d) }
        // "A um hello there B": the user selects "B" at the end
        f.select(f.text.length - 1, f.text.length); f.report().let { (a, b, c, d) -> t.onSelection(a, b, c, d) }
        t.result("Hello there")
        assertEquals("A Hello there B", f.toString())
        assertEquals(f.text.length - 1 to f.text.length, f.selection)
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
