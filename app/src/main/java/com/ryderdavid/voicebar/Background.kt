package com.ryderdavid.voicebar

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery optimization: Android (and Samsung's battery care more so) stops apps in the background
 * to save power, and a stopped Voicebar can lose its Gboard button service. Exempting it (Samsung
 * calls it "Unrestricted") keeps it running.
 */
object Background {
    fun unrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** Android's own one-tap "Let Voicebar always run in the background?"; the battery list if that's refused. */
    @SuppressLint("BatteryLife") // a sideloaded keyboard whose accessibility service must stay up
    fun ask(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        for (i in listOf(direct, list)) {
            if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }
}
