package com.ryderdavid.bubblekeyboard.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlin.math.abs

/**
 * Watches for "voice bar" while a keyboard is up but nobody's dictating, entirely on the phone: a
 * small keyword-spotting model (sherpa-onnx, zipformer trained on GigaSpeech, about 5 MB) listens
 * to the microphone and nothing leaves the device.
 *
 * Two spotters run on the same audio: one for "voice bar" alone (so the button can light up as
 * soon as it's heard) and one for the whole command ("voice bar, listen" and its other wordings).
 *
 * Whoever wants it ([want]) gets the callbacks: the keyboard's idle bar, or the way-back button
 * over Gboard. It runs while anyone wants it; the most recent one hears about it. Main thread only.
 */
object WakeWord {
    interface Listener {
        /** "Voice bar" heard: light up, a command may follow. */
        fun onWake()
        /** "Voice bar, listen" (or dictate, start, talk…): start dictating. */
        fun onListen()
        /** "Voice bar, close" (or hide, dismiss, go away…): put the keyboard away. */
        fun onHide()
        /** "Voice bar" and then nothing usable: settle back down. */
        fun onWakeGone()
    }

    /** What it's doing, for the settings screen. */
    @Volatile var status: String = "Off"
        private set

    private const val TAG = "VoicebarWake"
    private const val RATE = 16_000
    private const val CHUNK = 1_600 // 100 ms
    /** After "voice bar", how long a command may take before the light goes off. */
    private const val WAKE_HOLD_MS = 3_500L
    /** Silence this long with the mic "open" means the system is muting us. */
    private const val MUTED_AFTER_MS = 3_000L

    private val WAKE = listOf(
        "▁ VO IC E ▁B AR :1.5 #0.25 @VOICE_BAR",
        "▁ VO IC E B AR :1.5 #0.25 @VOICE_BAR",
    ).joinToString("/")

    private val main = Handler(Looper.getMainLooper())
    private val owners = LinkedHashMap<String, Listener>()
    private var loop: Loop? = null
    private var awake = false
    private val wakeGone = Runnable { if (awake) { awake = false; current()?.onWakeGone() } }

    private fun current(): Listener? = owners.values.lastOrNull()

    /** [owner] wants it watching (with [listener]), or no longer does (null). */
    fun want(context: Context, owner: String, listener: Listener?) {
        if (listener == null) owners.remove(owner) else { owners.remove(owner); owners[owner] = listener }
        if (owners.isNotEmpty() && loop == null) {
            loop = Loop(context.applicationContext).also { it.start() }
        } else if (owners.isEmpty()) {
            loop?.finish()
            loop = null
            main.removeCallbacks(wakeGone)
            awake = false
            status = "Off"
        }
    }

    private fun heard(keyword: String) = main.post {
        val l = current() ?: return@post
        when (keyword) {
            "VOICE_BAR" -> {
                if (!awake) { awake = true; l.onWake() }
                main.removeCallbacks(wakeGone)
                main.postDelayed(wakeGone, WAKE_HOLD_MS)
            }
            "LISTEN" -> {
                main.removeCallbacks(wakeGone)
                awake = false
                l.onListen()
            }
            "HIDE" -> {
                main.removeCallbacks(wakeGone)
                awake = false
                l.onHide()
            }
        }
    }

    // ---------------------------------------------------------------- the model, loaded once

    @Volatile private var spotter: KeywordSpotter? = null

    private fun spotter(context: Context): KeywordSpotter = spotter ?: synchronized(this) {
        spotter ?: KeywordSpotter(
            context.assets,
            KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "kws/encoder.int8.onnx",
                        decoder = "kws/decoder.int8.onnx",
                        joiner = "kws/joiner.int8.onnx",
                    ),
                    tokens = "kws/tokens.txt",
                    numThreads = 1,
                    provider = "cpu",
                ),
                keywordsFile = "kws/keywords.txt",
                numTrailingBlanks = 1,
            ),
        ).also { spotter = it }
    }

    // ---------------------------------------------------------------- the listening thread

    private class Loop(private val context: Context) : Thread("voicebar-wake") {
        @Volatile private var stop = false
        fun finish() { stop = true }

        @SuppressLint("MissingPermission") // checked by the callers; a refusal just fails here
        override fun run() {
            val kws = runCatching { spotter(context) }.getOrElse {
                Log.w(TAG, "model didn't load", it); status = "Couldn't load the wake-word model"; return
            }
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK * 4))
            }.getOrNull()
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                status = "Microphone unavailable"; rec?.release(); return
            }
            val wake: OnlineStream = kws.createStream(WAKE)
            val command: OnlineStream = kws.createStream()
            try {
                rec.startRecording()
                status = "Listening for “voice bar”"
                val pcm = ShortArray(CHUNK)
                val f = FloatArray(CHUNK)
                var quietSince = SystemClock.uptimeMillis()
                while (!stop) {
                    val n = rec.read(pcm, 0, CHUNK)
                    if (n <= 0) { if (n < 0) { status = "Microphone error $n"; break }; continue }
                    var any = false
                    for (i in 0 until n) { f[i] = pcm[i] / 32768f; if (abs(pcm[i].toInt()) > 2) any = true }
                    val now = SystemClock.uptimeMillis()
                    if (any) {
                        quietSince = now
                        if (status != "Listening for “voice bar”") status = "Listening for “voice bar”"
                    } else if (now - quietSince > MUTED_AFTER_MS) status = "Muted by the system (another app has the mic)"
                    val chunk = if (n == CHUNK) f else f.copyOf(n)
                    for (s in arrayOf(wake, command)) {
                        s.acceptWaveform(chunk, RATE)
                        while (kws.isReady(s)) {
                            kws.decode(s)
                            val k = kws.getResult(s).keyword
                            if (k.isNotEmpty()) { kws.reset(s); heard(k) }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "wake loop", t)
                status = "Stopped: ${t.message}"
            } finally {
                runCatching { rec.stop() }
                rec.release()
                wake.release()
                command.release()
            }
        }
    }
}
