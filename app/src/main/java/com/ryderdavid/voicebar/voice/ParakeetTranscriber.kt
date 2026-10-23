package com.ryderdavid.voicebar.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The voice-activity side of local recognition, kept free of the native libraries so it can be
 * tested: something that hears speech start and stop and hands back finished stretches of it.
 */
interface SpeechGate {
    fun accept(samples: FloatArray)
    /** Someone is talking right now. */
    val speaking: Boolean
    /** A finished stretch of speech (padded a little either side), or null. */
    fun poll(): FloatArray?
    /** End whatever's being said now, as if a pause came. */
    fun flush()
    fun release() {}
}

/**
 * Splits speech at pauses and turns each stretch into text with [decode] (Parakeet, on the
 * phone). Between pauses it re-reads what's been said so far every [previewEveryMs], so words
 * still show up while you talk; when a pause ends a stretch, the whole stretch is read once more
 * and committed. Single-threaded: call everything from one worker thread.
 */
class Segmenter(
    private val gate: SpeechGate,
    private val decode: (FloatArray) -> String,
    private val onPartial: (String) -> Unit,
    private val onCommitted: (String) -> Unit,
    private val sampleRate: Int = 16000,
    private val previewEveryMs: Long = 800,
    /** Audio kept from just before speech is noticed (detection lags the first syllable). */
    private val prerollMs: Long = 500,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val preroll = ArrayDeque<FloatArray>()
    private var prerollSamples = 0
    private val current = ArrayList<FloatArray>()
    private var currentSamples = 0
    private var sincePreview = 0
    private var lastPreviewCost = 0L
    private var shown = ""

    fun accept(samples: FloatArray) {
        gate.accept(samples)
        if (gate.speaking || currentSamples > 0) {
            if (currentSamples == 0) { preroll.forEach { add(it) }; sincePreview = 0 }
            add(samples)
        } else {
            preroll.addLast(samples); prerollSamples += samples.size
            while (prerollSamples - (preroll.firstOrNull()?.size ?: 0) >= sampleRate * prerollMs / 1000) {
                prerollSamples -= preroll.removeFirst().size
            }
        }
        sincePreview += samples.size
        drain()
        // a preview, spaced out by at least twice what the last one took (slow phones preview less often)
        val gap = maxOf(previewEveryMs, lastPreviewCost * 2) * sampleRate / 1000
        if (gate.speaking && currentSamples > 0 && sincePreview >= gap) {
            sincePreview = 0
            val t = now()
            val text = decode(joined()).trim()
            lastPreviewCost = now() - t
            if (text != shown) { shown = text; onPartial(text) }
        }
    }

    /** Commits what's been said so far, now (a pause the app noticed, or the end). Always reports, even "". */
    fun commit() {
        gate.flush()
        var any = drain()
        if (!any) {
            // nothing finished: read the stretch in progress (or report nothing)
            val text = if (currentSamples > 0) decode(joined()).trim() else ""
            reset()
            onCommitted(text)
        }
    }

    /** Finished stretches from the gate: read, commit, and start fresh. True if any. */
    private fun drain(): Boolean {
        var any = false
        while (true) {
            val seg = gate.poll() ?: break
            any = true
            val text = decode(seg).trim()
            reset()
            onCommitted(text)
        }
        return any
    }

    private fun add(s: FloatArray) { current += s; currentSamples += s.size }

    private fun reset() {
        current.clear(); currentSamples = 0; sincePreview = 0; shown = ""
        preroll.clear(); prerollSamples = 0
    }

    private fun joined(): FloatArray {
        val out = FloatArray(currentSamples)
        var i = 0
        for (c in current) { c.copyInto(out, i); i += c.size }
        return out
    }
}

/** Silero VAD (bundled, 0.6 MB) through sherpa-onnx. */
class SileroGate(context: Context) : SpeechGate {
    private val vad = Vad(context.assets, VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
            model = "vad/silero_vad.onnx",
            threshold = 0.5f,
            minSilenceDuration = 0.6f,
            minSpeechDuration = 0.25f,
            windowSize = 512,
            maxSpeechDuration = 20f,
        ),
        sampleRate = 16000,
        numThreads = 1,
    ))
    override fun accept(samples: FloatArray) = vad.acceptWaveform(samples)
    override val speaking get() = vad.isSpeechDetected()
    override fun poll(): FloatArray? = if (vad.empty()) null else vad.front().samples.also { vad.pop() }
    override fun flush() = vad.flush()
    override fun release() = vad.release()
}

/**
 * Speech recognition on the phone with Parakeet: the same [Transcriber] contract as ElevenLabs.
 * Stretches of speech are committed at pauses on their own (so hands-free gets one command per
 * utterance), live words arrive as partials, and [commit] / [finish] end the stretch in progress.
 * Free and private: no audio leaves the phone.
 */
class ParakeetTranscriber(private val context: Context) : Transcriber {
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "voicebar-parakeet").apply { isDaemon = true } }
    private var segmenter: Segmenter? = null
    private var gate: SpeechGate? = null
    private val done = AtomicBoolean(false)
    @Volatile private var listener: Transcriber.Listener? = null

    override fun start(listener: Transcriber.Listener) {
        this.listener = listener
        exec {
            val t0 = android.os.SystemClock.uptimeMillis()
            val rec = try { Parakeet.Engine.get(context) } catch (t: Throwable) {
                Log.w("VoicebarParakeet", "load", t)
                listener.onError("Couldn’t load Parakeet; try downloading it again in Settings")
                return@exec
            }
            val g = SileroGate(context).also { gate = it }
            segmenter = Segmenter(
                g,
                decode = { samples ->
                    val s = rec.createStream()
                    try { s.acceptWaveform(samples, 16000); rec.decode(s); rec.getResult(s).text } finally { s.release() }
                },
                onPartial = { if (!done.get()) listener.onPartial(it) },
                onCommitted = { if (!done.get()) listener.onCommitted(it) },
            )
            com.ryderdavid.voicebar.AppLog.log("speech", "Parakeet ready in ${android.os.SystemClock.uptimeMillis() - t0} ms")
            listener.onReady()
        }
    }

    override fun send(pcm: ByteArray) {
        if (done.get()) return
        val f = FloatArray(pcm.size / 2)
        for (i in f.indices) f[i] = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xff)).toShort() / 32768f
        exec { segmenter?.accept(f) }
    }

    override fun commit() = exec { segmenter?.commit() }

    override fun finish(onDone: () -> Unit) {
        exec {
            segmenter?.commit()
            done.set(true)
            close()
            onDone()
        }
        work.shutdown()
    }

    override fun cancel() {
        done.set(true)
        exec { close() }
        work.shutdown()
    }

    private fun close() {
        gate?.release(); gate = null; segmenter = null
        Parakeet.Engine.done()
    }

    private fun exec(block: () -> Unit) {
        if (work.isShutdown) return
        runCatching { work.execute { runCatching(block).onFailure { Log.w("VoicebarParakeet", "work", it) } } }
    }

    /** For tests: wait for queued work. */
    internal fun drain(ms: Long) { work.shutdown(); work.awaitTermination(ms, TimeUnit.MILLISECONDS) }
}
