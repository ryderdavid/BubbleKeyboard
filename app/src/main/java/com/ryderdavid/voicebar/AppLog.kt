package com.ryderdavid.voicebar

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What Voicebar did in the last [KEEP_MS] (five minutes), in memory only, to copy into a bug report
 * from Settings › How to use it. Events and short excerpts, never keys; nothing leaves the phone
 * unless you paste it somewhere.
 */
object AppLog {
    const val KEEP_MS = 5 * 60_000L
    private const val MAX_LINES = 3000
    private val lines = ArrayDeque<Pair<Long, String>>()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun log(tag: String, msg: String, t: Throwable? = null) {
        val now = System.currentTimeMillis()
        val line = "$tag: $msg" + (t?.let { " (${it.javaClass.simpleName}: ${it.message?.take(120)})" } ?: "")
        synchronized(lines) {
            lines.addLast(now to line)
            trim(now)
        }
    }

    /** A short, single-line excerpt of something said or typed. */
    fun excerpt(text: String?, n: Int = 60): String =
        if (text.isNullOrEmpty()) "\"\"" else "\"" + text.replace(Regex("\\s+"), " ").take(n) + (if (text.length > n) "…" else "") + "\" (${text.length} chars)"

    fun text(now: Long = System.currentTimeMillis()): String = synchronized(lines) {
        trim(now)
        lines.joinToString("\n") { (t, l) -> time.format(Date(t)) + "  " + l }
    }

    fun clear() = synchronized(lines) { lines.clear() }

    /**
     * Android's own log for this app (logcat, this process only) over the same five minutes:
     * warnings, crashes, and what the system said about Voicebar. Only warnings and errors unless
     * [everything]: the rest is the system's window and keyboard chatter, hundreds of lines a minute.
     * Blocking; call off the main thread.
     */
    fun systemLog(everything: Boolean = false, now: Long = System.currentTimeMillis()): String = runCatching {
        val since = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(now - KEEP_MS))
        val args = mutableListOf("logcat", "-d", "-v", "time", "--pid=${android.os.Process.myPid()}", "-T", since)
        if (!everything) args += "*:W"
        val p = ProcessBuilder(args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        out.lines().takeLast(1500).joinToString("\n").trim()
    }.getOrElse { "(couldn't read the system log: ${it.message})" }

    /** Everything, for a bug report: the app's events, then the system log. */
    fun report(header: String, everything: Boolean = false): String =
        header + "\n\n== Voicebar events (last 5 minutes) ==\n" + text().ifBlank { "(none)" } +
            "\n\n== Android log for Voicebar (last 5 minutes, ${if (everything) "everything" else "warnings and errors"}) ==\n" +
            systemLog(everything).ifBlank { "(none)" }

    private fun trim(now: Long) {
        while (lines.isNotEmpty() && (now - lines.first().first > KEEP_MS || lines.size > MAX_LINES)) lines.removeFirst()
    }
}
