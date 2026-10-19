package com.ryderdavid.bubblekeyboard

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash on the phone (when, which version, and the stack trace), so it can be
 * copied from Settings › Help and sent along. Nothing is uploaded.
 */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "last-crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file(app).writeText(report(app, thread, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun report(context: Context, thread: Thread, error: Throwable): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        return "Voicebar $version · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}\n$time · thread ${thread.name}\n\n$trace"
    }

    /**
     * Crashes the handler above can't see (native code, a frozen app Android closed, a library
     * Voicebar depends on being updated underneath it): Android keeps a record of why the
     * process last ended, read on the next start. Each of these can be why Android switches the
     * Gboard button's service off.
     */
    fun checkExits(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = context.getSystemService(android.app.ActivityManager::class.java) ?: return
            val prefs = context.getSharedPreferences("crashlog", Context.MODE_PRIVATE)
            val seen = prefs.getLong("exit_seen", 0)
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 10).filter { it.timestamp > seen }
            prefs.edit().putLong("exit_seen", exits.maxOfOrNull { it.timestamp } ?: seen).apply()
            val bad = exits.firstOrNull { it.reason in REPORTED } ?: return
            // a Java crash was already written by the handler, with its stack: keep that one
            val javaLog = file(context)
            if (bad.reason == android.app.ApplicationExitInfo.REASON_CRASH && javaLog.exists() && javaLog.lastModified() >= bad.timestamp - 5000) return
            val why = when (bad.reason) {
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashed in native code (speech models)"
                android.app.ApplicationExitInfo.REASON_ANR -> "stopped responding, and Android closed it"
                android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "closed because something it depends on was updated or stopped"
                else -> "crashed"
            }
            val trace = runCatching { bad.traceInputStream?.bufferedReader()?.use { it.readText().take(6000) } }.getOrNull()
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(bad.timestamp))
            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            javaLog.writeText("Voicebar $version · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}\n" +
                "$time · process ${bad.processName}\n\nVoicebar $why: ${bad.description ?: ""}\n\n${trace ?: ""}")
        }
    }

    private val REPORTED = setOf(
        android.app.ApplicationExitInfo.REASON_CRASH,
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE,
        android.app.ApplicationExitInfo.REASON_ANR,
        android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED,
    )

    fun last(context: Context): String? = file(context).takeIf { it.exists() }?.readText()
    fun clear(context: Context) { file(context).delete() }
}

class VoicebarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        CrashLog.checkExits(this)
    }
}
