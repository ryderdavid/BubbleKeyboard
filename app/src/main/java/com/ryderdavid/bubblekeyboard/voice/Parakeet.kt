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
    private const val TAG = "VoicebarParakeet"
    private const val BASE = "https://huggingface.co/csukuangfj2/sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming/resolve/8c3a10fb13408c7a7054f6898958bf1c64a8d6c7/"

    data class ModelFile(val name: String, val size: Long, val sha256: String)

    val FILES = listOf(
        ModelFile("encoder.int8.onnx", 654_040_552, "6716910b7a0833997fec7a410494c995d70124001a0e9b66d6370d6aced577e0"),
        ModelFile("decoder.int8.onnx", 7_257_753, "a5e223392c90e75f8144cdb5eb95af7625db389e39edef2bd1a9c872b3298fe6"),
        ModelFile("joiner.int8.onnx", 1_735_860, "869f43f7d24595c55581ad3bf249a935fb8a71389fbdaa7504b9f46f93140f8a"),
        ModelFile("tokens.txt", 8_952, "dc0b4584ab2e4ddbf888425c076c61b736e7356a015250db7d307e6f1a8188ff"),
    )
    val TOTAL = FILES.sumOf { it.size }

    fun dir(context: Context) = File(context.noBackupFilesDir, "parakeet-unified-0.6b")
    private fun marker(context: Context) = File(dir(context), ".verified")

    /** Downloaded and checked. */
    fun ready(context: Context): Boolean =
        marker(context).exists() && FILES.all { File(dir(context), it.name).length() == it.size }

    /** Chosen in settings and downloaded: dictation and hands-free run on the phone. */
    fun inUse(context: Context): Boolean =
        com.ryderdavid.bubblekeyboard.Prefs.get(context).getString(com.ryderdavid.bubblekeyboard.Prefs.SPEECH_ENGINE, null) ==
            com.ryderdavid.bubblekeyboard.Prefs.ENGINE_PARAKEET && ready(context)

    fun remove(context: Context) {
        Engine.release()
        dir(context).deleteRecursively()
    }

    // ---------------------------------------------------------------- downloading

    sealed class State {
        data object Idle : State()
        data class Downloading(val done: Long, val total: Long) : State()
        data object Checking : State()
        data object Ready : State()
        data class Failed(val why: String) : State()
    }

    @Volatile var state: State = State.Idle
        private set
    private val listeners = mutableSetOf<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    private var worker: Thread? = null

    fun observe(l: (State) -> Unit): () -> Unit {
        synchronized(listeners) { listeners += l }
        l(state)
        return { synchronized(listeners) { listeners -= l } }
    }

    private fun set(s: State) {
        state = s
        val ls = synchronized(listeners) { listeners.toList() }
        main.post { ls.forEach { it(s) } }
    }

    /** Starts (or resumes) the download; a partial file picks up where it stopped. */
    fun download(context: Context) {
        if (worker?.isAlive == true) return
        val app = context.applicationContext
        if (ready(app)) { set(State.Ready); return }
        cancelled = false
        worker = Thread({ run(app) }, "voicebar-parakeet-download").apply { isDaemon = true; start() }
    }

    fun cancel() { cancelled = true }

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    private fun run(context: Context) {
        val d = dir(context).apply { mkdirs() }
        marker(context).delete()
        val free = d.usableSpace
        val missing = FILES.sumOf { f -> (f.size - File(d, f.name).length().coerceAtMost(f.size)) }
        if (free < missing + 50_000_000) { set(State.Failed("Not enough space: needs ${missing / 1_000_000} MB free")); return }
        try {
            var before = 0L
            for (f in FILES) {
                val out = File(d, f.name)
                if (out.length() > f.size) out.delete()
                while (out.length() < f.size) {
                    if (cancelled) { set(State.Idle); return }
                    val from = out.length()
                    val req = Request.Builder().url(BASE + f.name).apply { if (from > 0) header("Range", "bytes=$from-") }.build()
                    http.newCall(req).execute().use { r ->
                        if (r.code == 200 && from > 0) out.delete() // the server ignored the range: start over
                        else if (!r.isSuccessful) throw java.io.IOException("Download failed (${r.code})")
                        val body = r.body ?: throw java.io.IOException("Empty download")
                        RandomAccessFile(out, "rw").use { raf ->
                            raf.seek(out.length())
                            val buf = ByteArray(256 * 1024)
                            val input = body.byteStream()
                            var lastReport = 0L
                            while (true) {
                                if (cancelled) { set(State.Idle); return }
                                val n = input.read(buf)
                                if (n < 0) break
                                raf.write(buf, 0, n)
                                val now = System.currentTimeMillis()
                                if (now - lastReport > 200) { lastReport = now; set(State.Downloading(before + raf.length(), TOTAL)) }
                            }
                        }
                    }
                }
                before += f.size
            }
            set(State.Checking)
            for (f in FILES) {
                if (sha256(File(d, f.name)) != f.sha256) {
                    File(d, f.name).delete()
                    set(State.Failed("A file didn’t check out; try again"))
                    return
                }
            }
            marker(context).writeText(NAME)
            set(State.Ready)
        } catch (t: Throwable) {
            Log.w(TAG, "download", t)
            set(State.Failed(if (t is java.net.UnknownHostException) "No connection; try again" else (t.message ?: "Download failed")))
        }
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
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
