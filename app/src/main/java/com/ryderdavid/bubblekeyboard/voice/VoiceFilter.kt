package com.ryderdavid.bubblekeyboard.voice

import android.content.Context
import java.util.concurrent.Executors

/**
 * Keeps only the user's voice: audio goes through in half-second blocks, and each block with
 * speech in it is checked against the voiceprint, using the last second of speech; a block
 * that doesn't sound like the user is replaced with silence. Recognition hears the user and
 * quiet, never the other voice. This costs one block of delay (half a second), and at the start
 * of speech, when there isn't enough to judge yet, up to one more.
 *
 * Voices talking over each other at the same moment can't be pulled apart this way; it handles
 * turns (someone else speaking between your sentences, a TV, a conversation nearby).
 * Single-threaded: call everything from one thread.
 */
class VoiceGate(
    private val speech: SpeechTest,
    private val embed: (FloatArray) -> FloatArray,
    private val print: FloatArray,
    private val threshold: Float,
    private val emit: (ByteArray) -> Unit,
    /** Each judgement: the similarity (null when there was too little speech to tell) and the verdict. */
    private val onVerdict: ((Float?, Boolean) -> Unit)? = null,
    private val sampleRate: Int = 16000,
    blockMs: Int = 500,
) {
    private val blockSamples = sampleRate * blockMs / 1000
    private val windowSamples = sampleRate              // judged on the last second of speech
    private val judgeSamples = sampleRate / 2           // wait for half a second of speech if we can
    private val leastSamples = sampleRate / 4           // under a quarter second: too little to tell
    private val resetSilence = sampleRate / 2           // a pause this long may be a new speaker
    private val maxHeld = 2
    /** Once it's you, a little leeway, so a soft word mid-sentence isn't dropped. */
    private val leeway = 0.05f

    private class Block(val pcm: ByteArray, val speech: Int)

    private val vadQueue = FloatArrayBuilder()
    private var blockPcm = java.io.ByteArrayOutputStream()
    private var blockFilled = 0
    private var blockSpeech = 0
    private val recent = ArrayDeque<FloatArray>()
    private var recentSamples = 0
    private val held = ArrayList<Block>()
    private var silenceRun = 0
    private var lastMe = false

    fun accept(pcm: ByteArray) {
        val f = pcmToFloats(pcm)
        var off = 0
        while (off < f.size) {
            val n = minOf(f.size - off, blockSamples - blockFilled)
            vadQueue.add(f.copyOfRange(off, off + n))
            blockPcm.write(pcm, off * 2, n * 2)
            blockFilled += n; off += n
            runVad()
            if (blockFilled == blockSamples) closeBlock()
        }
    }

    /** Decides everything held, now (a commit or the end). */
    fun flush() {
        if (blockFilled > 0) closeBlock(final = true)
        if (held.isNotEmpty()) decide()
    }

    private fun runVad() {
        while (vadQueue.size >= SpeechTest.WINDOW) {
            val w = vadQueue.take(SpeechTest.WINDOW)
            if (!speech.isSpeech(w)) {
                silenceRun += w.size
                if (silenceRun >= resetSilence) { recent.clear(); recentSamples = 0 }
            } else {
                silenceRun = 0
                blockSpeech += w.size
                recent.addLast(w); recentSamples += w.size
                while (recentSamples - recent.first().size >= windowSamples) recentSamples -= recent.removeFirst().size
            }
        }
    }

    private fun closeBlock(final: Boolean = false) {
        val b = Block(blockPcm.toByteArray(), blockSpeech)
        blockPcm = java.io.ByteArrayOutputStream(); blockFilled = 0; blockSpeech = 0
        if (b.speech == 0 && held.isEmpty()) { emit(b.pcm); return }
        held += b
        // speech just began: hold on for a little more to judge it by, unless it's already over
        if (!final && b.speech > 0 && recentSamples < judgeSamples && held.size < maxHeld) return
        decide()
    }

    private fun decide() {
        val score: Float?
        val me: Boolean
        if (recentSamples < leastSamples) { score = null; me = true } // too little to tell: let it through
        else {
            val all = FloatArray(recentSamples).also { var i = 0; for (r in recent) { r.copyInto(it, i); i += r.size } }
            score = runCatching { Voiceprint.similarity(embed(all), print) }.getOrNull()
            me = score == null || score >= threshold - (if (lastMe) leeway else 0f)
        }
        lastMe = me
        onVerdict?.invoke(score, me)
        for (b in held) emit(if (me) b.pcm else ByteArray(b.pcm.size))
        held.clear()
    }
}

/**
 * Wraps a recognizer (ElevenLabs or Parakeet) with the voice filters that are on: children's
 * voices taken out ([ChildFilter]), and voices that aren't the user's ([VoiceGate]). If the speaker
 * model can't load, that part passes audio through unchanged.
 */
class VoiceFilterTranscriber(private val context: Context, private val inner: Transcriber) : Transcriber {
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "voicebar-voicefilter").apply { isDaemon = true } }
    private var gate: VoiceGate? = null
    private var vad: SileroSpeechTest? = null
    private var child: ChildFilterPcm? = null

    override fun start(listener: Transcriber.Listener) {
        inner.start(listener)
        exec {
            if (ChildVoices.enabled(context)) child = ChildFilterPcm(ChildFilter.childMin(ChildVoices.strength(context)))
            if (!Voiceprint.active(context)) return@exec
            val print = Voiceprint.load(context) ?: return@exec
            try {
                Voiceprint.Engine.embed(context, FloatArray(8000)) // loads the model now, not on the first word
                val v = SileroSpeechTest(context).also { vad = it }
                gate = VoiceGate(v, { Voiceprint.Engine.embed(context, it) }, print, Voiceprint.threshold(Voiceprint.strength(context)), emit = inner::send)
            } catch (t: Throwable) {
                logW("load", t)
            }
        }
    }

    /** Children's voices out first (instant), then anyone who isn't the user (the voiceprint), then the recognizer. */
    override fun send(pcm: ByteArray) = exec {
        val clean = child?.process(pcm) ?: pcm
        if (clean.isEmpty()) return@exec
        gate?.accept(clean) ?: inner.send(clean)
    }

    override fun commit() = exec { drainChild(); gate?.flush(); inner.commit() }

    private fun drainChild() {
        val tail = child?.flush() ?: return
        gate?.accept(tail) ?: inner.send(tail)
    }

    override fun finish(onDone: () -> Unit) {
        exec { drainChild(); gate?.flush(); close(); inner.finish(onDone) }
        work.shutdown()
    }

    override fun cancel() {
        inner.cancel()
        exec { close() }
        work.shutdown()
    }

    private fun close() {
        vad?.close(); vad = null; gate = null; child = null
        Voiceprint.Engine.done()
    }

    private fun exec(block: () -> Unit) {
        if (work.isShutdown) return
        runCatching { work.execute { runCatching(block).onFailure { logW("work", it) } } }
    }

    companion object {
        /** [t] filtered when "Ignore children's voices" is on, or "Only my voice" is on and set up. */
        fun wrap(context: Context, t: Transcriber): Transcriber =
            if (ChildVoices.enabled(context) || Voiceprint.active(context)) VoiceFilterTranscriber(context.applicationContext, t) else t
    }
}
