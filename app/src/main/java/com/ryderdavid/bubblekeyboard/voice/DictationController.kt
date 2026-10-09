package com.ryderdavid.bubblekeyboard.voice

import android.os.Handler
import android.os.Looper

/**
 * Hold-to-talk dictation: microphone -> streaming transcriber (live text) -> optional cleanup
 * model on the whole utterance -> one block of text to type.
 *
 * Cleaning the whole utterance at the end, rather than phrase by phrase, lets spoken corrections
 * that span phrases ("...on Tuesday. No, make that Wednesday") resolve correctly.
 *
 * All [Ui] calls happen on the main thread.
 */
class DictationController(
    private val audio: AudioSource,
    /** A fresh transcriber per utterance, or null when no API key is set. */
    private val transcriberFactory: () -> Transcriber?,
    /** The cleanup model, or null to type the raw transcript. */
    private val cleanerFactory: () -> Cleaner?,
    private val ui: Ui,
    private val main: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
    private val background: (Runnable) -> Unit = { Thread(it, "bubble-cleanup").start() },
) {
    interface Ui {
        fun onListening()
        fun onLevel(level: Float)
        fun onLiveText(text: String)
        fun onCleaning(raw: String)
        fun onResult(text: String)
        fun onIdle()
        fun onError(message: String)
    }

    enum class State { IDLE, LISTENING, FINISHING, CLEANING }

    var state = State.IDLE
        private set

    private var session = 0
    private var transcriber: Transcriber? = null
    private val committed = ArrayList<String>()
    private var partial = ""

    private fun fullText() = (committed + partial).filter { it.isNotBlank() }.joinToString(" ") { it.trim() }.trim()

    /** Words already typed for good (before a key press mid-dictation); what follows starts fresh. */
    private var skipWords = 0
    private fun words(s: String) = s.split(WHITESPACE).filter { it.isNotEmpty() }
    private fun liveText() = if (skipWords == 0) fullText() else words(fullText()).drop(skipWords).joinToString(" ")

    /**
     * [typedWords] more words are typed for good (the user pressed a key, or the draft ended):
     * from here, live text and the cleanup cover only what follows them. False once finishing.
     */
    fun markBoundary(typedWords: Int): Boolean {
        if (state != State.LISTENING) return false
        skipWords += typedWords
        return true
    }

    /** Starts listening; false when it couldn't (busy finishing the last one, no key, no mic). */
    fun start(): Boolean {
        if (state != State.IDLE) return false
        val t = transcriberFactory() ?: return false.also { ui.onError("Add your ElevenLabs API key in Bubble Keyboard settings") }
        val id = ++session
        committed.clear()
        partial = ""
        skipWords = 0
        transcriber = t
        t.start(object : Transcriber.Listener {
            override fun onPartial(text: String) = main(Runnable {
                if (id != session || (state != State.LISTENING && state != State.FINISHING)) return@Runnable
                partial = text
                ui.onLiveText(liveText())
            })

            override fun onCommitted(text: String) = main(Runnable {
                if (id != session || (state != State.LISTENING && state != State.FINISHING)) return@Runnable
                committed.add(text)
                partial = ""
                ui.onLiveText(liveText())
            })

            override fun onError(message: String) = main(Runnable {
                if (id != session || state == State.IDLE) return@Runnable
                audio.stop()
                transcriber?.cancel()
                state = State.IDLE
                ui.onError(message)
            })
        })
        val ok = audio.start(
            onChunk = { pcm -> if (id == session) t.send(pcm) },
            onLevel = { level -> main(Runnable { if (id == session && state == State.LISTENING) ui.onLevel(level) }) },
        )
        if (!ok) {
            t.cancel()
            ui.onError("Couldn't open the microphone")
            return false
        }
        state = State.LISTENING
        ui.onListening()
        return true
    }

    /** The finger lifted: finish and type the text, or throw it away when [cancel]. */
    fun stop(cancel: Boolean) {
        if (state != State.LISTENING) return
        audio.stop()
        val t = transcriber ?: return
        if (cancel) {
            session++
            t.cancel()
            state = State.IDLE
            ui.onIdle()
            return
        }
        val id = session
        state = State.FINISHING
        t.finish {
            main(Runnable {
                if (id != session || state != State.FINISHING) return@Runnable
                val raw = liveText()
                if (raw.isBlank()) {
                    state = State.IDLE
                    ui.onIdle()
                    return@Runnable
                }
                val cleaner = cleanerFactory()
                if (cleaner == null) {
                    deliver(raw)
                    return@Runnable
                }
                state = State.CLEANING
                ui.onCleaning(raw)
                background(Runnable {
                    // any failure or timeout falls back to the raw transcript; nothing is lost
                    val cleaned = runCatching { cleaner.clean(raw) }.getOrNull()?.takeIf { it.isNotBlank() } ?: raw
                    main(Runnable { if (id == session && state == State.CLEANING) deliver(cleaned) })
                })
            })
        }
    }

    private fun deliver(text: String) {
        state = State.IDLE
        ui.onResult(text)
        ui.onIdle()
    }

    val dictating get() = state != State.IDLE

    /** Drops anything in flight, e.g. when the text field goes away. */
    fun abort() {
        if (state == State.IDLE) return
        session++
        audio.stop()
        transcriber?.cancel()
        state = State.IDLE
        ui.onIdle()
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
