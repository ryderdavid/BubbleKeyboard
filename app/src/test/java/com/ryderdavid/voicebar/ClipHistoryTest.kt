package com.ryderdavid.voicebar

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ClipHistoryTest {
    private fun history(): ClipHistory {
        var saved: String? = null
        return ClipHistory(object : ClipHistory.Store { override fun read() = saved; override fun write(json: String) { saved = json } })
    }

    @Test fun newestFirstAndTheSameTextMovesUp() {
        val h = history()
        h.add("one", 1); h.add("two", 2); h.add("one", 3)
        assertEquals(listOf("one", "two"), h.all().map { it.text })
    }

    @Test fun onlyFifteenLooseOnesButPinsStay() {
        val h = history()
        h.add("keep me", 0); h.setPinned("keep me", true)
        for (i in 1..20) h.add("clip $i", i.toLong())
        val all = h.all()
        assertEquals("keep me", all.first().text)
        assertEquals(ClipHistory.KEEP + 1, all.size)
        assertTrue(all.none { it.text == "clip 1" })
        h.clear()
        assertEquals(listOf("keep me"), h.all().map { it.text })
    }

    @Test fun itSurvivesARestart() {
        var saved: String? = null
        val store = object : ClipHistory.Store { override fun read() = saved; override fun write(json: String) { saved = json } }
        ClipHistory(store).apply { add("hello", 5); setPinned("hello", true) }
        assertEquals(listOf(ClipHistory.Clip("hello", 5, true)), ClipHistory(store).all())
    }
}
