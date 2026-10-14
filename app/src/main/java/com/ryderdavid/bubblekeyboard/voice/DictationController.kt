package com.ryderdavid.bubblekeyboard.voice

import android.os.Handler
import android.os.Looper

/**
 * Hold-to-talk dictation: microphone -> streaming transcriber (live text) -> optional cleanup
 * model on the whole utterance -> one block of text to type.
 *
 * Cleanup runs in two stages. At a pause ([pause], from the silence countdown) what's been said so
 * far is committed and cleaned while listening goes on; the cleaned text replaces the raw words.
 * Later cleanups send the cleaned text and the raw words since, marked apart, so the model has the
 * whole context and spoken corrections that span the pause ("...on Tuesday. No, make that
 * Wednesday") still resolve. If nothing new was said after the last cleanup, finishing types the
 * cleaned text without another request.
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
        /** New words were recognized (speech, not noise): the silence countdown starts over. */
        fun onHeard() {}
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

    /** Text cleaned at a pause, and how many heard words (after [skipWords]) it stands for. */
    private var cleaned = ""
    private var cleanedWords = 0
    /** A pause asked for a commit; the cleanup runs when the committed text arrives. */
    private var pauseWaiting = false
    private var pauseCleaning = false
    private var pauseCleaner: Cleaner? = null
    /** Bumped when a boundary or a new dictation makes an in-flight pause cleanup stale. */
    private var generation = 0
    private var heardCount = 0

    /** Heard words (by position) taken out of the text: spoken commands. */
    private val cut = HashSet<Int>()

    private fun heard() = words(fullText()).withIndex().filter { it.index >= skipWords && it.index !in cut }.map { it.value }

    /**
     * Takes the last [n] heard words out of the text (a spoken command), and shows what's left.
     * False when not listening.
     */
    fun cutLast(n: Int): Boolean {
        if (state != State.LISTENING) return false
        val all = words(fullText()).indices.filter { it >= skipWords && it !in cut }
        all.takeLast(n).forEach { cut.add(it) }
        ui.onLiveText(liveText())
        return true
    }
    private fun rawTail() = heard().drop(cleanedWords).joinToString(" ")
    private fun liveText(): String = listOf(cleaned, rawTail()).filter { it.isNotBlank() }.joinToString(" ")

    private fun noteHeard() {
        val n = words(fullText()).size
        if (n > heardCount) ui.onHeard()
        heardCount = n
    }

    /**
     * [typedWords] more words are typed for good (the user pressed a key, or the draft ended):
     * from here, live text and the cleanup cover only what follows them. False once finishing.
     */
    @Suppress("UNUSED_PARAMETER")
    fun markBoundary(typedWords: Int): Boolean {
        if (state != State.LISTENING) return false
        // everything heard so far is in the field now, cleaned or not
        skipWords = words(fullText()).size
        cleaned = ""
        cleanedWords = 0
        pauseWaiting = false
        generation++
        return true
    }

    /**
     * A pause while listening: commit what's been said and clean it now, keeping the microphone
     * open. Does nothing without cleanup, or when there's nothing new since the last cleanup.
     */
    fun pause() {
        if (state != State.LISTENING || pauseWaiting || pauseCleaning) return
        if (heard().size <= cleanedWords && partial.isBlank()) return
        // no cleanup (off, or no key): leave the transcript alone, and don't commit for nothing
        pauseCleaner = cleanerFactory() ?: return
        if (partial.isNotBlank()) {
            pauseWaiting = true
            transcriber?.commit()
            // the commit's text arrives through onCommitted, which runs the cleanup
        } else cleanAtPause()
    }

    private fun cleanAtPause() {
        pauseWaiting = false
        if (state != State.LISTENING) return
        val all = heard()
        val upTo = all.size
        val raw = all.drop(cleanedWords).joinToString(" ")
        if (raw.isBlank()) return
        val cleaner = pauseCleaner ?: return
        val id = session
        val gen = generation
        val before = cleaned
        pauseCleaning = true
        background(Runnable {
            val result = runCatching { cleaner.clean(before, raw) }.getOrNull()?.takeIf { it.isNotBlank() }
            main(Runnable {
                if (id != session) return@Runnable
                pauseCleaning = false
                if (gen != generation || result == null || state != State.LISTENING) return@Runnable
                cleaned = result
                cleanedWords = upTo
                ui.onLiveText(liveText())
            })
        })
    }

    /** Starts listening; false when it couldn't (busy finishing the last one, no key, no mic). */
    fun start(): Boolean {
        if (state != State.IDLE) return false
        val t = transcriberFactory() ?: return false.also { ui.onError("Add your ElevenLabs API key in Bubble Keyboard settings") }
        val id = ++session
        committed.clear()
        partial = ""
        cut.clear()
        skipWords = 0
        cleaned = ""
        cleanedWords = 0
        pauseWaiting = false
        pauseCleaning = false
        heardCount = 0
        generation++
        transcriber = t
        t.start(object : Transcriber.Listener {
            override fun onPartial(text: String) = main(Runnable {
                if (id != session || (state != State.LISTENING && state != State.FINISHING)) return@Runnable
                partial = text
                noteHeard()
                ui.onLiveText(liveText())
            })

            override fun onCommitted(text: String) = main(Runnable {
                if (id != session || (state != State.LISTENING && state != State.FINISHING)) return@Runnable
                committed.add(text)
                partial = ""
                noteHeard()
                ui.onLiveText(liveText())
                if (pauseWaiting && state == State.LISTENING) cleanAtPause()
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
                val before = cleaned
                // a spoken command cut from the end can leave the comma that led into it
                val raw = rawTail().trimEnd { it.isWhitespace() || it in LEAD_IN }
                val all = listOf(before, raw).filter { it.isNotBlank() }.joinToString(" ")
                if (all.isBlank()) {
                    state = State.IDLE
                    ui.onIdle()
                    return@Runnable
                }
                // cleaned at the last pause and nothing said since: that's the result already
                if (raw.isBlank()) {
                    deliver(before)
                    return@Runnable
                }
                val cleaner = cleanerFactory()
                if (cleaner == null) {
                    deliver(all)
                    return@Runnable
                }
                state = State.CLEANING
                ui.onCleaning(all)
                background(Runnable {
                    // any failure or timeout falls back to what we have (cleaned part + raw words); nothing is lost
                    val result = runCatching { cleaner.clean(before, raw) }.getOrNull()?.takeIf { it.isNotBlank() } ?: all
                    main(Runnable { if (id == session && state == State.CLEANING) deliver(result) })
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
        const val LEAD_IN = ",;:-–—"
    }
}
