package com.ryderdavid.voicebar.swipe

import android.content.Context
import org.json.JSONObject
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File

/**
 * FUTO Swipe's models (assets/swipe/, under the FUTO Model Weights License 1.0), run with
 * ExecuTorch: the encoder turns a swipe and the keyboard's key positions into per-step letter
 * probabilities for any layout; the English decoder refines them for QWERTY. Then
 * [SwipeDecoder.search] finds the words. Blocking and not thread-safe: use from one worker thread.
 */
class SwipeModels private constructor(private val encoder: Module, private val decoder: Module, val params: SwipeDecoder.Params) {

    /**
     * The words [points] most likely spells on a keyboard whose a-to-z key centers are [keys]
     * (x, y pairs in alphabetical order), both in the [0, 1] frame of the letter rows.
     */
    fun decode(points: List<SwipeDecoder.Point>, keys: FloatArray, lexicon: SwipeDecoder.Lexicon, n: Int = 5): List<SwipeDecoder.Candidate> {
        require(keys.size == 2 * SwipeDecoder.LETTERS)
        val features = Tensor.fromBlob(SwipeDecoder.resample(points), longArrayOf(1, 2, SwipeDecoder.T_IN.toLong()))
        val layout = FloatArray(SwipeDecoder.MAX_KEYS * 2).also { keys.copyInto(it) }
        val mask = BoolTensor(BooleanArray(SwipeDecoder.MAX_KEYS) { it < SwipeDecoder.LETTERS }, longArrayOf(1, SwipeDecoder.MAX_KEYS.toLong()))
        val out = encoder.forward(
            EValue.from(features),
            EValue.from(Tensor.fromBlob(layout, longArrayOf(1, SwipeDecoder.MAX_KEYS.toLong(), 2))),
            EValue.from(mask),
        )
        val logE = out[0].toTensor().dataAsFloatArray   // [1, 32, 65]: 64 keys, then blank
        val coef = out[1].toTensor().dataAsFloatArray   // [1, 32, 64]
        val gate = out[2].toTensor().dataAsFloatArray   // [1, 32, 1]
        val logp = decoderOutput(decoderInput(logE, coef, gate))
        return SwipeDecoder.search(logp, lexicon, params, n)
    }

    /** The decoder's input at each step: the 26 letters' and blank's log-probabilities, the 64 coefficients, the gate. */
    private fun decoderInput(logE: FloatArray, coef: FloatArray, gate: FloatArray): FloatArray =
        Companion.decoderInput(logE, coef, gate)

    private fun decoderOutput(x: FloatArray): Array<FloatArray> {
        val y = decoder.forward(EValue.from(Tensor.fromBlob(x, longArrayOf(1, SwipeDecoder.T_OUT.toLong(), DECODER_IN.toLong()))))[0]
            .toTensor().dataAsFloatArray // [1, 32, 27], already log-probabilities
        return Array(SwipeDecoder.T_OUT) { t -> FloatArray(27) { y[t * 27 + it] } }
    }

    companion object {
        /** The tuned scoring for the encoder with the English decoder (and no context model), in scoring.json. */
        const val SCORING_KEY = "encoder:honorable_sturgeon decoder:magic_macaw"
        private const val DECODER_IN = 27 + 64 + 1

        internal fun decoderInput(logE: FloatArray, coef: FloatArray, gate: FloatArray): FloatArray {
            val keys = SwipeDecoder.MAX_KEYS + 1
            val x = FloatArray(SwipeDecoder.T_OUT * DECODER_IN)
            for (t in 0 until SwipeDecoder.T_OUT) {
                val o = t * DECODER_IN
                for (k in 0 until SwipeDecoder.LETTERS) x[o + k] = logE[t * keys + k]
                x[o + SwipeDecoder.LETTERS] = logE[t * keys + SwipeDecoder.MAX_KEYS]
                for (c in 0 until 64) x[o + 27 + c] = coef[t * 64 + c]
                x[o + 91] = gate[t]
            }
            return x
        }

        /** Loads the models (copied out of the APK once, since ExecuTorch reads files) and the tuned scoring. */
        fun load(context: Context): SwipeModels {
            val dir = File(context.noBackupFilesDir, "swipe").apply { mkdirs() }
            fun file(name: String): String {
                val f = File(dir, name)
                val size = context.assets.openFd("swipe/$name").use { it.length }
                if (f.length() != size) context.assets.open("swipe/$name").use { i -> f.outputStream().use { i.copyTo(it) } }
                return f.absolutePath
            }
            val s = JSONObject(context.assets.open("swipe/scoring.json").bufferedReader().readText()).getJSONObject(SCORING_KEY)
            val params = SwipeDecoder.Params(s.getDouble("gamma"), s.getDouble("lambda"), s.getDouble("beta"), s.getDouble("gamma_prune"), s.getDouble("beta_prune"))
            return SwipeModels(Module.load(file("encoder.pte")), Module.load(file("decoder.pte")), params)
        }
    }
}
