package com.ryderdavid.voicebar.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SegmenterTest {
    /** A gate driven by the test: speech on/off, and segments it hands back. */
    private class FakeGate : SpeechGate {
        var on = false
        val heard = ArrayList<Float>()
        val done = ArrayDeque<FloatArray>()
        var flushed = 0
        override fun accept(samples: FloatArray) { if (on) samples.forEach { heard += it } }
        override val speaking get() = on
        override fun poll() = done.removeFirstOrNull()
        override fun flush() { flushed++; if (heard.isNotEmpty()) { done.addLast(heard.toFloatArray()); heard.clear() } }
        fun pause() { on = false; done.addLast(heard.toFloatArray()); heard.clear() }
    }

    private val chunk = 1600 // 100 ms
    private fun audio(value: Float) = FloatArray(chunk) { value }

    @Test fun previewsWhileTalkingThenCommitsAtThePause() {
        val gate = FakeGate()
        val partials = ArrayList<String>(); val commits = ArrayList<String>()
        var t = 0L
        // "decodes" to how many 100 ms chunks it got, and whether the first is pre-roll (value 0.1)
        val seg = Segmenter(gate, decode = { s -> "${s.size / chunk}" + if (s.firstOrNull() == 0.1f) "+pre" else "" },
            onPartial = { partials += it }, onCommitted = { commits += it }, previewEveryMs = 800, now = { t })
        repeat(10) { seg.accept(audio(0.1f)) }                 // a second of quiet: kept as pre-roll (500 ms)
        gate.on = true
        repeat(12) { seg.accept(audio(0.5f)) }                 // 1.2 s of speech
        assertEquals(listOf("13+pre"), partials, "one preview at ~800 ms, with 500 ms of pre-roll")
        gate.pause()
        seg.accept(audio(0f))
        assertEquals(listOf("12"), commits, "the gate's finished stretch is what's committed")
        // the next stretch starts fresh
        gate.on = true
        repeat(9) { seg.accept(audio(0.5f)) }
        assertEquals("8", partials.last(), "straight after a stretch there is no quiet to pre-roll")
    }

    @Test fun slowDecodesPreviewLessOften() {
        val gate = FakeGate().apply { on = true }
        var t = 0L
        val partials = ArrayList<String>()
        val seg = Segmenter(gate, decode = { s -> t += 1000; "${s.size}" }, onPartial = { partials += it }, onCommitted = {}, now = { t })
        repeat(30) { seg.accept(audio(0.5f)) }                // 3 s, previews cost 1 s each
        assertTrue(partials.size <= 2, "spaced by twice the cost: ${partials.size}")
    }

    @Test fun commitAlwaysReports() {
        val gate = FakeGate()
        val commits = ArrayList<String>()
        val seg = Segmenter(gate, decode = { "words" }, onPartial = {}, onCommitted = { commits += it })
        seg.commit()                                          // nothing said
        assertEquals(listOf(""), commits)
        gate.on = true
        repeat(5) { seg.accept(audio(0.5f)) }
        seg.commit()                                          // mid-sentence: the gate flushes it
        assertEquals(listOf("", "words"), commits)
        assertEquals(2, gate.flushed)
    }
}
