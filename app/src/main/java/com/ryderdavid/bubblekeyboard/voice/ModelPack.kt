package com.ryderdavid.bubblekeyboard.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * A model downloaded on demand (Parakeet, the voice model): files at a pinned URL, each checked
 * against a known size and SHA-256 before use, resumable, kept in app storage that isn't backed
 * up. Progress is shared, so any screen can follow it.
 */
class ModelPack(val name: String, private val dirName: String, private val base: String, val files: List<File>) {
    data class File(val name: String, val size: Long, val sha256: String)

    sealed class State {
        data object Idle : State()
        data class Downloading(val done: Long, val total: Long) : State()
        data object Checking : State()
        data object Ready : State()
        data class Failed(val why: String) : State()
    }

    val total = files.sumOf { it.size }

    fun dir(context: Context) = java.io.File(context.noBackupFilesDir, dirName)
    fun file(context: Context, name: String) = java.io.File(dir(context), name)
    private fun marker(context: Context) = java.io.File(dir(context), ".verified")

    /** Downloaded and checked. */
    fun ready(context: Context): Boolean =
        marker(context).exists() && files.all { java.io.File(dir(context), it.name).length() == it.size }

    fun remove(context: Context) {
        cancel()
        dir(context).deleteRecursively()
        set(State.Idle)
    }

    @Volatile var state: State = State.Idle
        private set
    private val listeners = mutableSetOf<(State) -> Unit>()
    private val main by lazy { Handler(Looper.getMainLooper()) }
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
        worker = Thread({ run(app) }, "voicebar-download-$dirName").apply { isDaemon = true; start() }
    }

    fun cancel() { cancelled = true }

    private fun run(context: Context) {
        val d = dir(context).apply { mkdirs() }
        marker(context).delete()
        val missing = files.sumOf { f -> (f.size - java.io.File(d, f.name).length().coerceAtMost(f.size)) }
        if (d.usableSpace < missing + 50_000_000) { set(State.Failed("Not enough space: needs ${missing / 1_000_000} MB free")); return }
        try {
            var before = 0L
            for (f in files) {
                val out = java.io.File(d, f.name)
                if (out.length() > f.size) out.delete()
                while (out.length() < f.size) {
                    if (cancelled) { set(State.Idle); return }
                    val from = out.length()
                    val req = Request.Builder().url(base + f.name).apply { if (from > 0) header("Range", "bytes=$from-") }.build()
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
                                if (now - lastReport > 200) { lastReport = now; set(State.Downloading(before + raf.length(), total)) }
                            }
                        }
                    }
                }
                before += f.size
            }
            set(State.Checking)
            for (f in files) {
                if (sha256(java.io.File(d, f.name)) != f.sha256) {
                    java.io.File(d, f.name).delete()
                    set(State.Failed("A file didn’t check out; try again"))
                    return
                }
            }
            marker(context).writeText(name)
            set(State.Ready)
        } catch (t: Throwable) {
            Log.w("VoicebarModels", "download $name", t)
            set(State.Failed(if (t is java.net.UnknownHostException) "No connection; try again" else (t.message ?: "Download failed")))
        }
    }

    companion object {
        private val http by lazy {
            OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        }

        fun sha256(file: java.io.File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
