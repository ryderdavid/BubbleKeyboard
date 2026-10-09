package com.ryderdavid.bubblekeyboard.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class DictationControllerTest {
    private val events = mutableListOf<String>()
    private val ui = object : DictationController.Ui {
        override fun onListening() { events.add("listening") }
        override fun onLevel(level: Float) {}
        override fun onLiveText(text: String) { events.add("live:$text") }
        override fun onCleaning(raw: String) { events.add("cleaning:$raw") }
        override fun onResult(text: String) { events.add("result:$text") }
        override fun onIdle() { events.add("idle") }
        override fun onError(message: String) { events.add("error:$message") }
    }

    private class FakeAudio(var ok: Boolean = true) : AudioSource {
        var chunk: ((ByteArray) -> Unit)? = null
        var running = false
        override fun start(onChunk: (ByteArray) -> Unit, onLevel: (Float) -> Unit): Boolean {
            chunk = onChunk; running = ok; return ok
        }
        override fun stop() { running = false }
    }

    private class FakeTranscriber : Transcriber {
        lateinit var listener: Transcriber.Listener
        var sent = 0
        var cancelled = false
        var finalText: String? = null
        override fun start(listener: Transcriber.Listener) { this.listener = listener }
        override fun send(pcm: ByteArray) { sent++ }
        override fun finish(onDone: () -> Unit) { finalText?.let { listener.onCommitted(it) }; onDone() }
        override fun cancel() { cancelled = true }
    }

    private val audio = FakeAudio()
    private val transcriber = FakeTranscriber()
    private var cleaner: Cleaner? = null
    private var key = true

    // everything runs inline: "main" and "background" are the test thread
    private val controller = DictationController(
        audio, { if (key) transcriber else null }, { cleaner }, ui,
        main = { it.run() }, background = { it.run() },
    )

    @Test fun liveTextThenCleanedResult() {
        cleaner = object : Cleaner { override fun clean(raw: String) = "Meet on Wednesday." }
        controller.start()
        audio.chunk!!(ByteArray(10))
        transcriber.listener.onPartial("meet on")
        transcriber.listener.onPartial("meet on tuesday no wait")
        transcriber.finalText = "meet on tuesday no wait wednesday"
        controller.stop(cancel = false)
        assertEquals(1, transcriber.sent)
        assertEquals(
            listOf(
                "listening", "live:meet on", "live:meet on tuesday no wait",
                "live:meet on tuesday no wait wednesday",
                "cleaning:meet on tuesday no wait wednesday", "result:Meet on Wednesday.", "idle",
            ),
            events,
        )
        assertEquals(DictationController.State.IDLE, controller.state)
    }

    @Test fun aNewHoldWhilePolishingIsRefused() {
        var finishCleaning: Runnable? = null
        val c = DictationController(
            audio, { transcriber }, { object : Cleaner { override fun clean(raw: String) = "Done." } }, ui,
            main = { it.run() }, background = { finishCleaning = it },
        )
        c.start()
        transcriber.finalText = "first"
        c.stop(cancel = false)
        assertEquals(DictationController.State.CLEANING, c.state)
        assertEquals(false, c.start())
        transcriber.listener.onPartial("late") // ignored while polishing
        finishCleaning!!.run()
        assertEquals(listOf("result:Done.", "idle"), events.takeLast(2))
        assertEquals(0, events.count { it == "live:first late" })
    }

    @Test fun aBoundaryLimitsLiveTextAndCleanupToWhatFollows() {
        val cleaned = mutableListOf<String>()
        cleaner = object : Cleaner { override fun clean(raw: String): String { cleaned.add(raw); return raw.uppercase() } }
        controller.start()
        transcriber.listener.onPartial("first part")
        assertEquals(true, controller.markBoundary(2))
        transcriber.listener.onPartial("first part second part")
        assertEquals("live:second part", events.last())
        transcriber.finalText = "first part second part done"
        controller.stop(cancel = false)
        assertEquals(listOf("second part done"), cleaned)
        assertEquals("result:SECOND PART DONE", events[events.size - 2])
    }

    @Test fun noBoundaryOnceFinishing() {
        controller.start()
        transcriber.finalText = "words"
        cleaner = object : Cleaner { override fun clean(raw: String) = raw }
        assertEquals(true, controller.dictating)
        controller.stop(cancel = false)
        assertEquals(false, controller.markBoundary(1))
    }

    @Test fun cleanupFailureTypesTheRawText() {
        cleaner = object : Cleaner { override fun clean(raw: String): String = throw java.io.IOException("offline") }
        controller.start()
        transcriber.finalText = "hello there"
        controller.stop(cancel = false)
        assertEquals("result:hello there", events[events.size - 2])
    }

    @Test fun withoutCleanupTheRawTextIsTyped() {
        controller.start()
        transcriber.listener.onCommitted("first part")
        transcriber.finalText = "second part"
        controller.stop(cancel = false)
        assertEquals(listOf("result:first part second part", "idle"), events.takeLast(2))
    }

    @Test fun cancelThrowsItAway() {
        controller.start()
        transcriber.listener.onPartial("never mind")
        controller.stop(cancel = true)
        assertEquals(true, transcriber.cancelled)
        assertEquals(false, audio.running)
        assertEquals("idle", events.last())
        assertEquals(0, events.count { it.startsWith("result") })
        transcriber.listener.onCommitted("late") // a late message after cancel is ignored
        assertEquals("idle", events.last())
    }

    @Test fun silenceTypesNothing() {
        controller.start()
        controller.stop(cancel = false)
        assertEquals(listOf("listening", "idle"), events)
    }

    @Test fun missingKeyExplains() {
        key = false
        controller.start()
        assertEquals(listOf("error:Add your ElevenLabs API key in Bubble Keyboard settings"), events)
        assertEquals(DictationController.State.IDLE, controller.state)
    }

    @Test fun micFailureCancelsTheStream() {
        audio.ok = false
        controller.start()
        assertEquals(true, transcriber.cancelled)
        assertEquals(listOf("error:Couldn't open the microphone"), events)
    }

    @Test fun streamErrorStopsListening() {
        controller.start()
        transcriber.listener.onError("ElevenLabs quota used up")
        assertEquals("error:ElevenLabs quota used up", events.last())
        assertEquals(DictationController.State.IDLE, controller.state)
        assertEquals(false, audio.running)
    }
}
