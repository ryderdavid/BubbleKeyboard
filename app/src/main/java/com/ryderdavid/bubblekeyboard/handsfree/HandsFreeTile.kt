package com.ryderdavid.bubblekeyboard.handsfree

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.ryderdavid.bubblekeyboard.R
import com.ryderdavid.bubblekeyboard.voice.HandsFreeStart

/** Quick Settings: start or stop hands-free mode. Without the accessibility service, opens its settings. */
class HandsFreeTile : TileService() {
    override fun onStartListening() { refresh() }

    override fun onClick() {
        when {
            HandsFreeStart.active -> HandsFreeStart.stop()
            HandsFreeStart.request() -> Unit
            else -> {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Build.VERSION.SDK_INT >= 34) {
                    startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
                } else {
                    @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated") startActivityAndCollapse(intent)
                }
            }
        }
        refresh()
    }

    private fun refresh() {
        val t = qsTile ?: return
        t.state = if (HandsFreeStart.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = getString(R.string.hf_tile)
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = getString(
            if (HandsFreeStart.active) R.string.hf_tile_on else if (HandsFreeStart.handler == null) R.string.hf_tile_needs else R.string.hf_tile_off)
        t.updateTile()
    }
}
