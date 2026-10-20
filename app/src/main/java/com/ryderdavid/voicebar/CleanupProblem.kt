package com.ryderdavid.voicebar

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The last time cleanup failed and the words went in as spoken, and why: shown in Settings › How to use it. */
object CleanupProblem {
    private fun prefs(context: Context) = context.getSharedPreferences("cleanup_problem", Context.MODE_PRIVATE)

    fun save(context: Context, why: String) {
        val time = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date())
        prefs(context).edit().putString("last", "$time · $why").apply()
    }

    fun last(context: Context): String? = prefs(context).getString("last", null)

    fun clear(context: Context) { prefs(context).edit().remove("last").apply() }
}
