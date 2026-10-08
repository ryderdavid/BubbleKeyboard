package com.ryderdavid.bubblekeyboard.voice

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
    }

    fun start(listener: Listener)
    /** 16 kHz, 16-bit, mono little-endian PCM. */
    fun send(pcm: ByteArray)
    /** Finalizes what was said so far; [onDone] runs once the last committed text arrived (or timed out). */
    fun finish(onDone: () -> Unit)
    fun cancel()
}

/** The ElevenLabs realtime speech-to-text WebSocket protocol, kept pure so it can be tested. */
object ElevenLabsProtocol {
    const val MODEL = "scribe_v2_realtime"

    fun url(base: String): HttpUrl = "$base/v1/speech-to-text/realtime".replace("wss://", "https://").replace("ws://", "http://")
        .toHttpUrl().newBuilder()
        .addQueryParameter("model_id", MODEL)
        .addQueryParameter("audio_format", "pcm_16000")
        // we commit once, when the finger lifts; until then everything stays a partial
        .addQueryParameter("commit_strategy", "manual")
        .build()

    fun audioChunk(pcm: ByteArray, commit: Boolean): String = JSONObject()
        .put("message_type", "input_audio_chunk")
        .put("audio_base_64", if (pcm.isEmpty()) "" else Base64.encodeToString(pcm, Base64.NO_WRAP))
        .put("commit", commit)
        .put("sample_rate", MicRecorder.SAMPLE_RATE)
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
) : Transcriber {
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
        val request = Request.Builder().url(ElevenLabsProtocol.url(baseUrl)).header("xi-api-key", apiKey).build()
        // OkHttp queues sends made before the socket opens, so audio captured meanwhile isn't lost
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val e = ElevenLabsProtocol.parse(text)) {
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
                if (finishing) { complete(); return }
                val msg = when (response?.code) {
                    401, 403 -> "ElevenLabs rejected the API key"
                    null -> "Can't reach the speech service"
                    else -> "Speech service error ${response.code}"
                }
                listener.onError(msg)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (finishing) complete()
            }
        })
    }

    override fun send(pcm: ByteArray) {
        if (!finishing && socket?.send(ElevenLabsProtocol.audioChunk(pcm, commit = false)) == true) audioSent = true
    }

    override fun finish(onDone: () -> Unit) {
        this.onDone = onDone
        finishing = true
        val ws = socket ?: return complete()
        ws.send(ElevenLabsProtocol.audioChunk(ByteArray(0), commit = true))
        // with audio sent, the text may still be on its way (a cold connection or a quick phrase can
        // finish before the first partial arrives): wait for it. Without audio nothing is coming.
        // the reply can beat us here and shut the timer down already; then there's nothing to wait for
        if (!done.get()) runCatching {
            timer.schedule({ complete() }, if (audioSent || pendingPartial) finishTimeoutMs else 300, TimeUnit.MILLISECONDS)
        }
    }

    private fun complete() {
        if (!done.compareAndSet(false, true)) return
        socket?.close(1000, null)
        timer.shutdown()
        onDone?.invoke()
    }

    override fun cancel() {
        done.set(true)
        timer.shutdownNow()
        socket?.cancel()
        socket = null
    }
}
