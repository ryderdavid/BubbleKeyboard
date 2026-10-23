package com.ryderdavid.voicebar.voice

import android.util.Base64
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streams speech to a recognizer and reports text as it firms up. Swappable, so another provider
 * can replace ElevenLabs by implementing this.
 */
interface Transcriber {
    interface Listener {
        /** Interim text for the segment being spoken; replaces the previous partial. */
        fun onPartial(text: String)
        /** Final text for a segment; the partial is cleared. */
        fun onCommitted(text: String)
        fun onError(message: String)
        /** Ready to hear: connected to the service, or the model loaded. Audio before this was held, not lost. */
        fun onReady() {}
    }

    fun start(listener: Listener)
    /** 16 kHz, 16-bit, mono little-endian PCM. */
    fun send(pcm: ByteArray)
    /** Finalizes what was said so far; [onDone] runs once the last committed text arrived (or timed out). */
    fun finish(onDone: () -> Unit)
    /** Firms up what was said so far as committed text and keeps listening (a pause mid-dictation). */
    fun commit() {}
    fun cancel()
}

/** The ElevenLabs realtime speech-to-text WebSocket protocol, kept pure so it can be tested. */
object ElevenLabsProtocol {
    const val MODEL = "scribe_v2_realtime"

    /** [keyterms]: names and terms to listen for (the personal dictionary), at most 50 of 20 characters. */
    /** [vad]: the service ends each utterance itself at a pause (hands-free commands), instead of us committing. */
    fun url(base: String, keyterms: List<String> = emptyList(), vad: Boolean = false): HttpUrl = "$base/v1/speech-to-text/realtime".replace("wss://", "https://").replace("ws://", "http://")
        .toHttpUrl().newBuilder()
        .addQueryParameter("model_id", MODEL)
        .addQueryParameter("audio_format", "pcm_16000")
        // dictation: we commit at a pause (to polish what's said so far) and when it ends
        .addQueryParameter("commit_strategy", if (vad) "vad" else "manual")
        .apply { if (vad) addQueryParameter("vad_silence_threshold_secs", "0.8") }
        .apply { keyterms.filter { it.length in 1..20 }.take(50).forEach { addQueryParameter("keyterms", it) } }
        .build()

    /** [previousText]: context for the model (what's on screen); only allowed on the first chunk. */
    fun audioChunk(pcm: ByteArray, commit: Boolean, previousText: String? = null): String = JSONObject()
        .put("message_type", "input_audio_chunk")
        .put("audio_base_64", if (pcm.isEmpty()) "" else Base64.encodeToString(pcm, Base64.NO_WRAP))
        .put("commit", commit)
        .put("sample_rate", MicRecorder.SAMPLE_RATE)
        .apply { if (!previousText.isNullOrBlank()) put("previous_text", previousText) }
        .toString()

    sealed class Event {
        data class Partial(val text: String) : Event()
        data class Committed(val text: String) : Event()
        data class Error(val type: String, val message: String) : Event()
        object Other : Event()
    }

    fun parse(message: String): Event {
        val json = runCatching { JSONObject(message) }.getOrNull() ?: return Event.Other
        val type = json.optString("message_type")
        return when {
            type == "partial_transcript" -> Event.Partial(json.optString("text"))
            type == "committed_transcript" || type == "committed_transcript_with_timestamps" -> Event.Committed(json.optString("text"))
            json.has("error") -> Event.Error(type, json.optString("error"))
            else -> Event.Other
        }
    }

    /** What the service called this message, for the log: its `message_type`, or why we can't tell. */
    fun kind(message: String): String {
        val json = runCatching { JSONObject(message) }.getOrNull() ?: return "not json"
        return json.optString("message_type").ifBlank { "no message_type" }
    }

    /** Error type names vary in style ("auth_error", "scribeAuthError"), so match loosely. */
    fun describe(error: Event.Error): String {
        val t = error.type.lowercase().replace("_", "")
        return when {
            "auth" in t -> "ElevenLabs rejected the API key"
            "quota" in t -> "ElevenLabs quota used up"
            "ratelimit" in t -> "ElevenLabs rate limit, try again shortly"
            "insufficientaudio" in t -> "Didn't hear anything"
            else -> error.message.ifBlank { "Speech service error (${error.type})" }
        }
    }
}

class ElevenLabsTranscriber(
    private val apiKey: String,
    private val client: OkHttpClient,
    private val baseUrl: String = "wss://api.elevenlabs.io",
    private val finishTimeoutMs: Long = 3000,
    /** Told how much audio (ms) was streamed, once the session ends: for the usage estimate. */
    private val onAudio: ((Long) -> Unit)? = null,
    /** Names and terms from the personal dictionary to listen for. */
    private val keyterms: List<String> = emptyList(),
    /**
     * Context gathered as dictation starts (reading the screen takes a moment), off the main
     * thread: keyterms replacing [keyterms], and text sent with the first audio. Audio captured
     * meanwhile is held and sent once the connection is made.
     */
    private val context: (() -> Context?)? = null,
    /** The service ends utterances at pauses (hands-free mode): see [ElevenLabsProtocol.url]. */
    private val vad: Boolean = false,
) : Transcriber {
    data class Context(val keyterms: List<String>, val previousText: String)

    private val lock = Any()
    /** When start was called, and whether the connection has opened and words have come back: for the log. */
    private val startedAt = android.os.SystemClock.uptimeMillis()
    @Volatile private var opened = false
    @Volatile private var heardAny = false
    /** Opening in the background (after [context]): audio waits here. */
    private var opening = false
    private val held = ArrayList<ByteArray>()
    private var finishWhenOpen: (() -> Unit)? = null
    private var previousText: String? = null

    private val audioBytes = java.util.concurrent.atomic.AtomicLong(0)
    private val reported = AtomicBoolean(false)
    private val logged = AtomicBoolean(false)
    /**
     * What came back, by kind, for the log: a session that sent audio and got only empty partials
     * (or messages we ignore) looks nothing like one that simply heard silence.
     */
    private val messages = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()
    private fun note(kind: String) { messages.computeIfAbsent(kind) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet() }
    private fun messageTally() = messages.entries.sortedBy { it.key }.joinToString(", ") { "${it.value.get()} ${it.key}" }
        .ifBlank { "nothing" }

    private fun report() {
        if (!reported.compareAndSet(false, true)) return
        val ms = audioBytes.get() * 1000 / (MicRecorder.SAMPLE_RATE * 2L) // 16-bit mono
        if (ms > 0) onAudio?.invoke(ms)
    }
    private var socket: WebSocket? = null
    private var listener: Transcriber.Listener? = null
    @Volatile private var finishing = false
    @Volatile private var pendingPartial = false
    @Volatile private var audioSent = false
    private val done = AtomicBoolean(false)
    private var onDone: (() -> Unit)? = null
    // a daemon thread: a forgotten transcriber must never keep the process alive
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "bubble-stt-timer").apply { isDaemon = true } }

    override fun start(listener: Transcriber.Listener) {
        this.listener = listener
        val gather = context
        if (gather == null) { open(listener, keyterms); return }
        opening = true
        Thread({
            val c = runCatching { gather() }.getOrNull()
            synchronized(lock) {
                if (done.get()) return@Thread
                previousText = c?.previousText?.ifBlank { null }
                open(listener, c?.keyterms ?: keyterms)
                opening = false
                val ws = socket
                if (ws != null) for (pcm in held) sendNow(ws, pcm)
                held.clear()
            }
            finishWhenOpen?.let { finishWhenOpen = null; finish(it) }
        }, "voicebar-stt-open").apply { isDaemon = true }.start()
    }

    private fun open(listener: Transcriber.Listener, terms: List<String>) {
        val request = Request.Builder().url(ElevenLabsProtocol.url(baseUrl, terms, vad)).header("xi-api-key", apiKey).build()
        // OkHttp queues sends made before the socket opens, so audio captured meanwhile isn't lost
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened = true
                com.ryderdavid.voicebar.AppLog.log("speech", "ElevenLabs connected in ${since()} ms")
                listener.onReady()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val e = ElevenLabsProtocol.parse(text)
                note(when (e) {
                    is ElevenLabsProtocol.Event.Partial -> if (e.text.isBlank()) "empty partials" else "partials"
                    is ElevenLabsProtocol.Event.Committed -> if (e.text.isBlank()) "empty committed" else "committed"
                    is ElevenLabsProtocol.Event.Error -> "errors (${e.type})"
                    ElevenLabsProtocol.Event.Other -> ElevenLabsProtocol.kind(text)
                })
                val words = when (e) { is ElevenLabsProtocol.Event.Partial -> e.text; is ElevenLabsProtocol.Event.Committed -> e.text; else -> "" }
                if (!heardAny && words.isNotBlank()) {
                    heardAny = true
                    com.ryderdavid.voicebar.AppLog.log("speech", "first words back ${since()} ms after the tap")
                }
                when (e) {
                    is ElevenLabsProtocol.Event.Partial -> {
                        pendingPartial = e.text.isNotBlank()
                        listener.onPartial(e.text)
                    }
                    is ElevenLabsProtocol.Event.Committed -> {
                        pendingPartial = false
                        listener.onCommitted(e.text)
                        if (finishing) complete()
                    }
                    is ElevenLabsProtocol.Event.Error -> {
                        if (finishing) complete() else listener.onError(ElevenLabsProtocol.describe(e))
                    }
                    ElevenLabsProtocol.Event.Other -> Unit
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                com.ryderdavid.voicebar.AppLog.log("speech", "ElevenLabs failed after ${since()} ms (opened=$opened): ${response?.code ?: t.javaClass.simpleName}")
                if (finishing) { complete(); return }
                if (done.get()) return // cancelled
                val msg = when (response?.code) {
                    401, 403 -> "ElevenLabs rejected the API key"
                    null -> "Can't reach the speech service"
                    else -> "Speech service error ${response.code}"
                }
                listener.onError(msg)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (finishing) complete()
                // the service ended the session on its own (a time limit): say so, so it can be reopened
                else if (!done.get()) listener.onError("The speech service closed the connection")
            }
        })
    }

    override fun send(pcm: ByteArray) {
        if (finishing) return
        synchronized(lock) {
            if (opening) { held += pcm; return }
            socket?.let { sendNow(it, pcm) }
        }
    }

    private fun sendNow(ws: WebSocket, pcm: ByteArray) {
        // the context goes with the first audio, and only the first
        val prev = previousText.also { previousText = null }
        if (ws.send(ElevenLabsProtocol.audioChunk(pcm, commit = false, previousText = prev))) {
            audioSent = true
            audioBytes.addAndGet(pcm.size.toLong())
        }
    }

    /** A commit mid-session: ElevenLabs closes the segment and keeps its context for what follows. */
    override fun commit() {
        if (!finishing && audioSent) socket?.send(ElevenLabsProtocol.audioChunk(ByteArray(0), commit = true))
    }

    override fun finish(onDone: () -> Unit) {
        synchronized(lock) { if (opening) { finishWhenOpen = onDone; return } }
        this.onDone = onDone
        finishing = true
        val ws = socket ?: return complete()
        ws.send(ElevenLabsProtocol.audioChunk(ByteArray(0), commit = true))
        // with audio sent, the text may still be on its way (a cold connection or a quick phrase can
        // finish before the first partial arrives): wait for it. Without audio nothing is coming.
        // the reply can beat us here and shut the timer down already; then there's nothing to wait for
        // never connected yet (a slow first connection after a while idle): the audio is queued, so
        // give the connection time to open and the words time to come back rather than dropping them
        val wait = when {
            audioSent && !opened -> maxOf(finishTimeoutMs, NOT_OPEN_WAIT_MS)
            audioSent || pendingPartial -> finishTimeoutMs
            else -> 300
        }
        if (!opened) com.ryderdavid.voicebar.AppLog.log("speech", "finishing before ElevenLabs connected (${since()} ms): waiting up to $wait ms")
        if (!done.get()) runCatching {
            timer.schedule({ complete() }, wait, TimeUnit.MILLISECONDS)
        }
    }

    private fun since() = android.os.SystemClock.uptimeMillis() - startedAt

    /** One line per session: how much audio actually went out, and what came back for it. */
    private fun logEnd(how: String) {
        if (!logged.compareAndSet(false, true)) return
        com.ryderdavid.voicebar.AppLog.log("speech", "$how (${since()} ms, connected=$opened" +
            ", sent ${"%.1f".format(audioBytes.get() / (MicRecorder.SAMPLE_RATE * 2f))} s of audio" +
            ", got ${messageTally()})")
    }

    private fun complete() {
        if (!done.compareAndSet(false, true)) return
        logEnd(if (heardAny) "ended" else "ended with no words back")
        socket?.close(1000, null)
        timer.shutdown()
        report()
        onDone?.invoke()
    }

    override fun cancel() {
        logEnd(if (heardAny) "thrown away" else "thrown away with no words back")
        done.set(true)
        timer.shutdownNow()
        report()
        socket?.cancel()
        socket = null
    }
}

/** Finishing before the connection ever opened: how long the queued audio's words may take to come back. */
private const val NOT_OPEN_WAIT_MS = 8_000L
