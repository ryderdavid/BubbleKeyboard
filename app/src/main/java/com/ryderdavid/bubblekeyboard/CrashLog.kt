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

    fun last(context: Context): String? = file(context).takeIf { it.exists() }?.readText()
    fun clear(context: Context) { file(context).delete() }
}

class VoicebarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
