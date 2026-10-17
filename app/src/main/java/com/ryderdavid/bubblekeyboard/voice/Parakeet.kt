package com.ryderdavid.bubblekeyboard.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * NVIDIA's Parakeet Unified 0.6B (English, with punctuation and capitals), run on the phone by
 * sherpa-onnx: speech recognition without a network or an API key. The model (about 660 MB) is
 * downloaded once from a pinned Hugging Face revision, checked against known sizes and SHA-256
 * sums, and kept in app storage that isn't backed up.
 */
object Parakeet {
    const val NAME = "Parakeet Unified 0.6B"
    private const val BASE = "https://huggingface.co/csukuangfj2/sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming/resolve/8c3a10fb13408c7a7054f6898958bf1c64a8d6c7/"

    val FILES = listOf(
        ModelPack.File("encoder.int8.onnx", 654_040_552, "6716910b7a0833997fec7a410494c995d70124001a0e9b66d6370d6aced577e0"),
        ModelPack.File("decoder.int8.onnx", 7_257_753, "a5e223392c90e75f8144cdb5eb95af7625db389e39edef2bd1a9c872b3298fe6"),
        ModelPack.File("joiner.int8.onnx", 1_735_860, "869f43f7d24595c55581ad3bf249a935fb8a71389fbdaa7504b9f46f93140f8a"),
        ModelPack.File("tokens.txt", 8_952, "dc0b4584ab2e4ddbf888425c076c61b736e7356a015250db7d307e6f1a8188ff"),
    )
    val pack = ModelPack(NAME, "parakeet-unified-0.6b", BASE, FILES)
    val TOTAL get() = pack.total
    val state get() = pack.state

    fun dir(context: Context) = pack.dir(context)
    fun ready(context: Context) = pack.ready(context)
    fun observe(l: (ModelPack.State) -> Unit) = pack.observe(l)
    fun download(context: Context) = pack.download(context)
    fun cancel() = pack.cancel()

    /** Chosen in settings and downloaded: dictation and hands-free run on the phone. */
    fun inUse(context: Context): Boolean =
        com.ryderdavid.bubblekeyboard.Prefs.get(context).getString(com.ryderdavid.bubblekeyboard.Prefs.SPEECH_ENGINE, null) ==
            com.ryderdavid.bubblekeyboard.Prefs.ENGINE_PARAKEET && ready(context)

    fun remove(context: Context) {
        Engine.release()
        pack.remove(context)
    }

    // ---------------------------------------------------------------- the recognizer, loaded once

    /**
     * The loaded model (about 700 MB of memory), shared by dictation and hands-free. Loaded on
     * first use (or warmed when the keyboard opens) and let go after a few idle minutes.
     */
    object Engine {
        private const val IDLE_MS = 3 * 60_000L
        private val lock = Any()
        @Volatile private var rec: OfflineRecognizer? = null
        private val main = Handler(Looper.getMainLooper())
        private val idle: Runnable = Runnable { release() }

        fun get(context: Context): OfflineRecognizer = synchronized(lock) {
            main.removeCallbacks(idle)
            rec ?: run {
                val d = dir(context)
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                OfflineRecognizer(null, OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = File(d, "encoder.int8.onnx").path,
                            decoder = File(d, "decoder.int8.onnx").path,
                            joiner = File(d, "joiner.int8.onnx").path,
                        ),
                        tokens = File(d, "tokens.txt").path,
                        numThreads = threads,
                        modelType = "nemo_transducer",
                    ),
                    decodingMethod = "greedy_search",
                )).also { rec = it }
            }
        }

        /** Loads it in the background if it's chosen and not loaded yet (the keyboard opened). */
        fun warm(context: Context) {
            if (rec != null || !inUse(context)) return
            val app = context.applicationContext
            Thread({ runCatching { get(app) }; done() }, "voicebar-parakeet-load").apply { isDaemon = true }.start()
        }

        /** Done for now: let go of it after a while. */
        fun done() { main.removeCallbacks(idle); main.postDelayed(idle, IDLE_MS) }


        fun release(): Unit = synchronized(lock) {
            main.removeCallbacks(idle)
            rec?.release()
            rec = null
        }
    }
}
